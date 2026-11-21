package com.termux.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Filesystem tools for the assistant.
 *
 * The design goal is to make *editing* cheap, because that is where a naive toolset wastes the most
 * tokens and the most time. A tool that can only "read whole file / write whole file" forces the model
 * to reproduce an entire file just to change one line, which is slow, expensive and error-prone.
 *
 * So the set is built around precise, local operations:
 *
 * - [ReadFileTool] returns a window of numbered lines, not a whole file.
 * - [EditFileTool] replaces an exact string, and reports how many matches there were so ambiguous
 *   edits fail loudly instead of silently corrupting a file.
 * - [MultiEditTool] applies several edits in one round trip, all-or-nothing.
 * - [WriteFileTool] is for creation and deliberate full rewrites.
 * - [ListFilesTool] lets the model orient itself before touching anything.
 * - [MoveFileTool] and [DeleteFileTool] cover the remaining structural operations.
 *
 * Every mutating tool backs the previous content up first (see [BackupStore]), because an assistant
 * that can edit files but cannot recover from a bad edit is not something a user should run.
 */
object FileTools {

    fun all(): List<AgentTool> = listOf(
        ReadFileTool(),
        ListFilesTool(),
        EditFileTool(),
        MultiEditTool(),
        WriteFileTool(),
        MoveFileTool(),
        DeleteFileTool(),
    )

    // ------------------------------------------------------------------ helpers

    private fun stringProperty(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun integerProperty(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun booleanProperty(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    /**
     * Resolves a path argument, translating a confinement violation into a refusal.
     *
     * A refusal (rather than an exception) matters: it tells the model the path is off limits, which is
     * actionable, whereas an exception surfaces as a generic error it may simply retry.
     */
    /**
     * Runs [block], turning confinement violations and bad arguments into refusals/failures.
     *
     * Tools call this around their bodies so that the behaviour is identical whether they are invoked
     * through [ToolRegistry] or directly (as the tests do).
     */
    private inline fun guarded(block: () -> ToolResult): ToolResult = try {
        block()
    } catch (e: FileSandbox.OutsideRootsException) {
        ToolResult.Denied(
            "`${e.requested}` is outside the permitted roots (${e.roots.joinToString()})",
        )
    } catch (e: IllegalArgumentException) {
        ToolResult.Failed(e.message ?: "invalid arguments")
    } catch (e: IOException) {
        ToolResult.Failed(e.message ?: "I/O error")
    }

    private fun resolveOrNull(arguments: JSONObject, context: ToolContext, key: String = "path"):
        Pair<File?, ToolResult.Denied?> {
        val raw = arguments.optString(key).trim()
        if (raw.isEmpty()) return null to ToolResult.Denied("`$key` must not be empty")
        return try {
            FileSandbox.resolve(raw, context.fileRoots) to null
        } catch (e: FileSandbox.OutsideRootsException) {
            null to ToolResult.Denied(
                "`$raw` is outside the permitted roots (${context.fileRoots.joinToString()})",
            )
        } catch (e: IOException) {
            null to ToolResult.Denied("cannot resolve `$raw`: ${e.message}")
        }
    }

    private fun resolve(arguments: JSONObject, context: ToolContext, key: String = "path"): File {
        val raw = arguments.optString(key).trim()
        if (raw.isEmpty()) throw IllegalArgumentException("`$key` must not be empty")
        return FileSandbox.resolve(raw, context.fileRoots)
    }

    /** Records the file's current contents so a bad edit can be undone. */
    private fun backup(file: File, context: ToolContext, reason: String): File? =
        BackupStore.backup(file, context.sessionId, reason)

    // -------------------------------------------------------------------- read

    /**
     * Reads a window of a text file.
     *
     * Line numbers are included so that later edits can target exact positions. Binary files are
     * refused with an explanation instead of being dumped as garbage.
     */
    class ReadFileTool : AgentTool {
        override val spec = ToolSpec(
            name = "read_file",
            description = "Read a text file. Returns lines with line numbers. Use offset/limit for " +
                "large files instead of reading everything. Refuses binary files.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", stringProperty("Absolute path, or a path relative to ~."))
                        .put(
                            "offset",
                            integerProperty("Zero-based line to start at. Use this to page through big files."),
                        )
                        .put("limit", integerProperty("Maximum number of lines to return.")),
                )
                .put("required", JSONArray().put("path")),
            risk = RiskLevel.SAFE,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val file = resolve(arguments, context)

                if (!file.exists()) return@withContext ToolResult.Failed("no such file: ${file.path}")
                if (file.isDirectory) {
                    return@withContext ToolResult.Failed(
                        "${file.path} is a directory; use list_files",
                    )
                }
                if (file.length() > FileSandbox.MAX_READ_BYTES) {
                    return@withContext ToolResult.Failed(
                        "file is too large (${FileSandbox.formatSize(file.length())}); " +
                            "read a range with offset/limit",
                    )
                }
                if (FileSandbox.isBinary(file)) {
                    return@withContext ToolResult.Failed(
                        "binary file (${FileSandbox.formatSize(file.length())}); not readable as text",
                    )
                }

                val offset = arguments.optInt("offset", 0)
                val limit = arguments.optInt("limit", FileSandbox.DEFAULT_READ_LINES)
                val window = FileSandbox.readWindow(file, offset, limit)

                if (window.lines.isEmpty()) {
                    return@withContext ToolResult.Ok(
                        "file is empty: ${file.path} (0 bytes)",
                    )
                }

                val header = buildString {
                    append(file.path)
                    append(" · ")
                    append(FileSandbox.formatSize(file.length()))
                    append(" · ")
                    append(window.totalLines?.let { "$it lines" } ?: "line count skipped (large file)")
                }
                val footer = buildString {
                    val lastShown = window.firstLineNumber + window.lines.size - 1
                    append("lines ${window.firstLineNumber}-$lastShown")
                    if (window.truncated) {
                        append("\n[truncated — call read_file again with offset=")
                        append(lastShown)
                        append(" for more]")
                    }
                }
                ToolResult.Ok("$header\n\n${FileSandbox.formatNumbered(window)}\n\n$footer")
                }
            }
    }

    // -------------------------------------------------------------------- list

    /** Lists a directory so the model can orient itself before editing anything. */
    class ListFilesTool : AgentTool {
        override val spec = ToolSpec(
            name = "list_files",
            description = "List a directory. Directories are marked with a trailing slash. " +
                "Use this before reading or editing so you know what exists.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", stringProperty("Directory path; defaults to ~ if omitted."))
                        .put(
                            "recursive",
                            booleanProperty("Recurse into subdirectories. Use sparingly."),
                        )
                        .put("max_entries", integerProperty("Maximum entries to return.")),
                )
                .put("required", JSONArray()),
            risk = RiskLevel.SAFE,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val raw = arguments.optString("path").trim()
                val dir = if (raw.isEmpty()) {
                    File(context.homeDir).canonicalFile
                } else {
                    resolve(arguments, context)
                }

                if (!dir.exists()) return@withContext ToolResult.Failed("no such path: ${dir.path}")
                if (!dir.isDirectory) {
                    return@withContext ToolResult.Ok(
                        "${dir.path} is a file (${FileSandbox.formatSize(dir.length())})",
                    )
                }

                val recursive = arguments.optBoolean("recursive", false)
                val maxEntries = arguments.optInt("max_entries", 200).coerceIn(1, 2_000)

                val entries = ArrayList<String>(maxEntries)
                var overflow = false

                fun addEntry(file: File, depth: Int) {
                    if (entries.size >= maxEntries) {
                        overflow = true
                        return
                    }
                    val indent = "  ".repeat(depth)
                    val suffix = if (file.isDirectory) "/" else ""
                    val size = if (file.isFile) " (${FileSandbox.formatSize(file.length())})" else ""
                    entries += "$indent${file.name}$suffix$size"
                }

                fun walk(directory: File, depth: Int) {
                    if (overflow) return
                    val children = directory.listFiles()?.sortedWith(
                        compareBy({ !it.isDirectory }, { it.name.lowercase() }),
                    ) ?: return
                    for (child in children) {
                        addEntry(child, depth)
                        if (overflow) return
                        if (recursive && child.isDirectory && depth < 8) {
                            walk(child, depth + 1)
                        }
                    }
                }

                walk(dir, 0)

                if (entries.isEmpty()) {
                    return@withContext ToolResult.Ok("${dir.path} is empty")
                }

                val text = buildString {
                    appendLine(dir.path)
                    entries.forEach { appendLine(it) }
                    if (overflow) append("[more entries omitted — increase max_entries or narrow the path]")
                }.trim()
                ToolResult.Ok(text)
                }
            }
    }

    // -------------------------------------------------------------------- edit

    /**
     * Replaces an exact string in a file.
     *
     * Exact matching is used rather than line numbers because it is robust to the file changing
     * between a read and an edit. `replace_all` decides what happens when the needle occurs more than
     * once; by default a duplicate match is an error, because "which one did you mean" is not something
     * a tool should guess.
     */
    class EditFileTool : AgentTool {
        override val spec = ToolSpec(
            name = "edit_file",
            description = "Edit a text file by replacing an exact string. This is the preferred way to " +
                "change files: it is precise and does not require rewriting the whole file. " +
                "If old_string occurs more than once, either make it unique or set replace_all.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", stringProperty("File to edit."))
                        .put("old_string", stringProperty("Exact text to replace, copied verbatim from read_file."))
                        .put("new_string", stringProperty("Replacement text."))
                        .put(
                            "replace_all",
                            booleanProperty("Replace every occurrence instead of requiring a unique match."),
                        )
                        .put(
                            "create_if_missing",
                            booleanProperty("Create the file (with new_string) when it does not exist."),
                        ),
                )
                .put("required", JSONArray().put("path").put("old_string").put("new_string")),
            risk = RiskLevel.MUTATING,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val file = resolve(arguments, context)
                val oldString = arguments.optString("old_string")
                val newString = arguments.optString("new_string")
                val replaceAll = arguments.optBoolean("replace_all", false)
                val createIfMissing = arguments.optBoolean("create_if_missing", false)

                if (oldString.isEmpty()) {
                    return@withContext ToolResult.Failed(
                        "old_string must not be empty; use write_file to create a file",
                    )
                }
                if (oldString == newString) {
                    return@withContext ToolResult.Failed("old_string and new_string are identical")
                }

                if (!file.exists()) {
                    if (!createIfMissing) {
                        return@withContext ToolResult.Failed("no such file: ${file.path}")
                    }
                    file.parentFile?.mkdirs()
                    file.writeText(newString)
                    return@withContext ToolResult.Ok("created ${file.path} (${FileSandbox.formatSize(file.length())})")
                }

                if (file.isDirectory) {
                    return@withContext ToolResult.Failed("${file.path} is a directory")
                }
                if (FileSandbox.isBinary(file)) {
                    return@withContext ToolResult.Failed("binary file; refusing to edit as text")
                }

                val original = file.readText()
                val occurrences = countOccurrences(original, oldString)

                when {
                    occurrences == 0 -> return@withContext ToolResult.Failed(
                        "old_string was not found in ${file.path}; re-read the file and copy the text exactly",
                    )

                    occurrences > 1 && !replaceAll -> return@withContext ToolResult.Failed(
                        "old_string occurs $occurrences times in ${file.path}; add surrounding context " +
                            "to make it unique, or set replace_all=true",
                    )
                }

                backup(file, context, "edit")
                val updated = if (replaceAll) {
                    original.replace(oldString, newString)
                } else {
                    original.replaceFirst(oldString, newString)
                }
                file.writeText(updated)

                val delta = updated.length - original.length
                val sign = if (delta >= 0) "+" else ""
                ToolResult.Ok(
                    "edited ${file.path}: replaced $occurrences occurrence(s), " +
                        "$sign$delta bytes",
                )
                }
            }

        /** Counts non-overlapping occurrences without regex semantics. */
        fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                count++
                index = haystack.indexOf(needle, index + needle.length)
            }
            return count
        }
    }

    /**
     * Applies several edits to one file in a single call.
     *
     * Batching matters: a model fixing five places in a file should not need five round trips. The
     * operation is all-or-nothing — if any edit fails to apply, the file is left untouched — so a
     * partially applied set can never leave the file half-changed.
     */
    class MultiEditTool : AgentTool {
        override val spec = ToolSpec(
            name = "multi_edit",
            description = "Apply several exact-string replacements to one file in a single call, " +
                "all-or-nothing. Prefer this over repeated edit_file calls when changing multiple " +
                "places in the same file.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", stringProperty("File to edit."))
                        .put(
                            "edits",
                            JSONObject()
                                .put("type", "array")
                                .put("description", "Replacements applied in order.")
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties",
                                            JSONObject()
                                                .put("old_string", stringProperty("Text to replace."))
                                                .put("new_string", stringProperty("Replacement.")),
                                        )
                                        .put("required", JSONArray().put("old_string").put("new_string")),
                                ),
                        ),
                )
                .put("required", JSONArray().put("path").put("edits")),
            risk = RiskLevel.MUTATING,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val file = resolve(arguments, context)
                val edits = arguments.optJSONArray("edits")
                    ?: return@withContext ToolResult.Failed("`edits` must be an array")
                if (edits.length() == 0) {
                    return@withContext ToolResult.Failed("`edits` must not be empty")
                }

                if (!file.isFile) {
                    return@withContext ToolResult.Failed("no such file: ${file.path}")
                }
                if (FileSandbox.isBinary(file)) {
                    return@withContext ToolResult.Failed("binary file; refusing to edit as text")
                }

                val original = file.readText()
                var working = original
                val problems = mutableListOf<String>()

                for (i in 0 until edits.length()) {
                    val edit = edits.getJSONObject(i)
                    val oldString = edit.optString("old_string")
                    val newString = edit.optString("new_string")

                    if (oldString.isEmpty()) {
                        problems += "edit #${i + 1}: old_string is empty"
                        continue
                    }
                    val occurrences = EditFileTool().countOccurrences(working, oldString)
                    when {
                        occurrences == 0 ->
                            problems += "edit #${i + 1}: old_string not found (after earlier edits)"
                        occurrences > 1 ->
                            problems += "edit #${i + 1}: old_string occurs $occurrences times; make it unique"
                        else -> working = working.replaceFirst(oldString, newString)
                    }
                }

                if (problems.isNotEmpty()) {
                    return@withContext ToolResult.Failed(
                        "no changes applied; ${problems.size} edit(s) could not be applied:\n" +
                            problems.joinToString("\n"),
                    )
                }

                backup(file, context, "multi_edit")
                file.writeText(working)

                val delta = working.length - original.length
                val sign = if (delta >= 0) "+" else ""
                ToolResult.Ok(
                    "applied ${edits.length()} edit(s) to ${file.path}, $sign$delta bytes",
                )
                }
            }
    }

    // ------------------------------------------------------------------- write

    /**
     * Writes a whole file.
     *
     * Kept available for creating new files and for deliberate full rewrites, but the description
     * steers the model towards [EditFileTool] for changes, since rewriting a large file to tweak one
     * line is exactly the waste this toolset is designed to avoid.
     */
    class WriteFileTool : AgentTool {
        override val spec = ToolSpec(
            name = "write_file",
            description = "Create or fully overwrite a file. For changing part of an existing file " +
                "prefer edit_file, which is cheaper and safer. Creates parent directories as needed.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", stringProperty("File to write."))
                        .put("content", stringProperty("Full file contents."))
                        .put(
                            "make_executable",
                            booleanProperty("Set the executable bit (for scripts)."),
                        ),
                )
                .put("required", JSONArray().put("path").put("content")),
            risk = RiskLevel.MUTATING,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val file = resolve(arguments, context)
                val content = arguments.optString("content")

                if (file.isDirectory) {
                    return@withContext ToolResult.Failed("${file.path} is a directory")
                }

                val existed = file.isFile
                val previousSize = if (existed) file.length() else 0L

                if (existed) backup(file, context, "write")
                file.parentFile?.mkdirs()

                // Write to a sibling temp file then move, so an interrupted write cannot leave a
                // half-written file behind.
                val temp = File(file.parentFile, ".${file.name}.tmp")
                temp.writeText(content)
                try {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (e: Exception) {
                    temp.delete()
                    return@withContext ToolResult.Failed("failed to write ${file.path}: ${e.message}")
                }

                if (arguments.optBoolean("make_executable", false)) {
                    runCatching {
                        Files.setPosixFilePermissions(
                            file.toPath(),
                            PosixFilePermissions.fromString("rwxr-xr-x"),
                        )
                    }.onFailure { ignored ->
                        // Non-POSIX filesystem (e.g. shared storage); not fatal.
                    }
                }

                val action = if (existed) "overwrote" else "created"
                ToolResult.Ok(
                    "$action ${file.path}: ${FileSandbox.formatSize(file.length())}" +
                        (if (existed) " (was ${FileSandbox.formatSize(previousSize)})" else ""),
                )
                }
            }
    }

    // -------------------------------------------------------------------- move

    /** Moves or renames a file or directory. */
    class MoveFileTool : AgentTool {
        override val spec = ToolSpec(
            name = "move_file",
            description = "Move or rename a file or directory.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("source", stringProperty("Path to move."))
                        .put("destination", stringProperty("Target path."))
                        .put("overwrite", booleanProperty("Replace the destination when it exists.")),
                )
                .put("required", JSONArray().put("source").put("destination")),
            risk = RiskLevel.DESTRUCTIVE,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val source = resolve(arguments, context, "source")
                val destination = resolve(arguments, context, "destination")
                val overwrite = arguments.optBoolean("overwrite", false)

                if (!source.exists()) return@withContext ToolResult.Failed("no such path: ${source.path}")
                if (source == destination) {
                    return@withContext ToolResult.Failed("source and destination are the same")
                }
                if (destination.exists() && !overwrite) {
                    return@withContext ToolResult.Failed(
                        "destination exists: ${destination.path}; set overwrite=true to replace",
                    )
                }
                // Refuse moving a directory into itself, which would recurse forever.
                if (source.isDirectory && FileSandbox.contains(source, destination)) {
                    return@withContext ToolResult.Failed("cannot move a directory into itself")
                }

                destination.parentFile?.mkdirs()
                val moved = if (overwrite && destination.exists()) {
                    destination.deleteRecursively() && source.renameTo(destination)
                } else {
                    source.renameTo(destination)
                }
                if (!moved) {
                    // renameTo fails across filesystems; fall back to copy + delete.
                    return@withContext try {
                        source.copyRecursively(destination, overwrite = overwrite)
                        source.deleteRecursively()
                        ToolResult.Ok("moved ${source.path} → ${destination.path}")
                    } catch (e: Exception) {
                        ToolResult.Failed("move failed: ${e.message}")
                    }
                }
                ToolResult.Ok("moved ${source.path} → ${destination.path}")
                }
            }
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes a file or directory.
     *
     * This is the one operation with no undo through the backup store for directories, so it is marked
     * destructive and the loop will always confirm it with the user first.
     */
    class DeleteFileTool : AgentTool {
        override val spec = ToolSpec(
            name = "delete_file",
            description = "Delete a file or directory. Always requires user approval.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("path", stringProperty("Path to delete."))
                        .put(
                            "recursive",
                            booleanProperty("Required to delete a non-empty directory."),
                        ),
                )
                .put("required", JSONArray().put("path")),
            risk = RiskLevel.DESTRUCTIVE,
        )

        override suspend fun invoke(arguments: JSONObject, context: ToolContext): ToolResult =
            withContext(Dispatchers.IO) { guarded {
                val file = resolve(arguments, context)
                val recursive = arguments.optBoolean("recursive", false)

                if (!file.exists()) return@withContext ToolResult.Failed("no such path: ${file.path}")

                // Never allow deleting a permitted root itself.
                val isRoot = context.fileRoots.any {
                    FileSandbox.contains(File(it).canonicalFile, file) &&
                        File(it).canonicalFile == file
                }
                if (isRoot) {
                    return@withContext ToolResult.Denied("refusing to delete a root directory: ${file.path}")
                }

                if (file.isDirectory) {
                    val isEmpty = file.list()?.isEmpty() != false
                    if (!isEmpty && !recursive) {
                        return@withContext ToolResult.Failed(
                            "${file.path} is not empty; set recursive=true to delete it and its contents",
                        )
                    }
                    val deleted = file.deleteRecursively()
                    return@withContext if (deleted) {
                        ToolResult.Ok("deleted directory ${file.path}")
                    } else {
                        ToolResult.Failed("could not delete ${file.path}")
                    }
                }

                val deleted = file.delete()
                if (deleted) {
                    ToolResult.Ok("deleted ${file.path}")
                } else {
                    ToolResult.Failed("could not delete ${file.path}")
                }
                }
            }
    }
}

/**
 * Keeps the most recent version of every edited file so a bad edit can be recovered.
 *
 * Backups live outside the user's roots (in the app's cache) so they never pollute the project tree,
 * and only the newest few per file are kept so the store cannot grow without bound.
 */
object BackupStore {

    /** How many backups to retain per file. */
    private const val MAX_PER_FILE: Int = 5

    @Volatile
    private var rootOverride: File? = null

    /** Overrides the storage location; used by tests. */
    fun setRootForTesting(dir: File?) {
        rootOverride = dir
    }

    private fun root(): File {
        rootOverride?.let { return it }
        // App-private cache, so backups vanish with the app and never appear in `~`.
        val base = System.getProperty("java.io.tmpdir") ?: "/tmp"
        return File(base, "termux-assistant-backups")
    }

    /**
     * Copies [file] aside before it is modified.
     *
     * Returns null when no backup was made (for example the file did not exist yet).
     */
    fun backup(file: File, sessionId: String, reason: String): File? {
        if (!file.isFile) return null
        return try {
            val dir = File(root(), sanitize(sessionId)).apply { mkdirs() }
            prune(dir, file.name)
            val stamp = System.currentTimeMillis()
            val target = File(dir, "${sanitize(file.name)}.$stamp.$reason.bak")
            file.copyTo(target, overwrite = true)
            target
        } catch (e: Exception) {
            // A failed backup must not block the edit; the user still has the confirmation step.
            null
        }
    }

    /** Lists backups for [fileName] in [sessionId], newest first. */
    fun list(sessionId: String, fileName: String? = null): List<File> {
        val dir = File(root(), sanitize(sessionId))
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { fileName == null || it.name.startsWith(sanitize(fileName)) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    private fun prune(dir: File, fileName: String) {
        val existing = dir.listFiles()
            ?.filter { it.name.startsWith(sanitize(fileName)) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        existing.drop(MAX_PER_FILE - 1).forEach { it.delete() }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "unnamed" }
}
