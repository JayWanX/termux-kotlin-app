package com.termux.app.ai

import java.io.File
import java.io.IOException

/**
 * Path confinement and file inspection shared by every filesystem tool.
 *
 * Two things matter here and both are easy to get wrong:
 *
 * 1. **Confinement.** A tool must not be able to read or write outside the roots the user agreed to.
 *    String prefix checks are not enough (`/home` vs `/home-evil`), so paths are canonicalised first.
 * 2. **Honest failure.** A denied path must produce a clear refusal, not a confusing "file not found",
 *    so the model does not retry the same thing forever.
 */
object FileSandbox {

    /** Files larger than this are not fully scanned for a line count. */
    private const val LINE_COUNT_SIZE_LIMIT: Long = 5L * 1024 * 1024

    /** How many leading bytes are inspected when guessing whether a file is binary. */
    private const val BINARY_PROBE_BYTES: Int = 8 * 1024

    /** Refuse to read anything larger than this, regardless of range, to bound memory and time. */
    const val MAX_READ_BYTES: Long = 32L * 1024 * 1024

    /** Default number of lines returned by a read. */
    const val DEFAULT_READ_LINES: Int = 2_000

    /** Hard cap on lines returned by a read. */
    const val MAX_READ_LINES: Int = 20_000

    /** Longest single line echoed back before truncation. */
    const val MAX_LINE_CHARS: Int = 2_000

    /**
     * Thrown when a path escapes the permitted roots.
     *
     * A dedicated type keeps the tools from having to translate null into prose at every call site.
     */
    class OutsideRootsException(val requested: String, val roots: List<String>) :
        IOException("`$requested` is outside the permitted roots: ${roots.joinToString()}")

    /**
     * Resolves [path] and proves it lies inside one of [roots].
     *
     * `~` is expanded to the first root (the home directory). Relative paths resolve against that same
     * root.
     *
     * Symlinks are a real escape route: a link inside the root can point anywhere. `canonicalFile`
     * follows links, but a **dangling or pre-existing** link whose target is outside must also be
     * caught, so after canonicalisation the *parent* directory is checked as well. That way
     * `<root>/link-to-outside/newfile.txt`, where `newfile.txt` does not exist yet, is still refused.
     *
     * @throws OutsideRootsException when the resolved path is not inside any root.
     */
    fun resolve(path: String, roots: List<String>): File {
        require(roots.isNotEmpty()) { "no permitted roots configured" }

        val trimmed = path.trim()
        require(trimmed.isNotEmpty()) { "path must not be empty" }

        val home = File(roots.first()).canonicalFile
        val expanded = if (trimmed == "~") home.path else trimmed.removePrefix("~")

        val candidate = if (File(expanded).isAbsolute) File(expanded) else File(home, expanded)
        val canonical = try {
            candidate.canonicalFile
        } catch (e: IOException) {
            throw IOException("cannot resolve `$path`: ${e.message}")
        }

        val canonicalRoots = roots.map { File(it).canonicalFile }
        if (canonicalRoots.none { contains(it, canonical) }) {
            throw OutsideRootsException(path, roots)
        }

        // `getCanonicalPath` follows symlinks on Linux/Android but NOT reliably on Windows, so a link
        // inside a root that points outside would slip through the check above. Walk the chain from
        // the candidate up to the root and refuse if any element is a link resolving elsewhere.
        assertNoEscapingSymlink(candidate, canonicalRoots, path, roots)

        // Guard the ancestor chain too. If the target does not exist yet, its parent is where a write
        // would land, and a symlinked parent could still point outside the roots.
        //
        // The walk stops as soon as it reaches a root, and never inspects anything above a root: an
        // ancestor of the root is by definition outside it, so checking further would reject every
        // legitimate path. When the target *is* a root there is nothing above it to check.
        val owningRoot = canonicalRoots.first { contains(it, canonical) }
        var ancestor = if (canonical == owningRoot) null else canonical.parentFile
        var depth = 0
        while (ancestor != null && depth < 64) {
            val resolvedAncestor = try {
                ancestor.canonicalFile
            } catch (e: IOException) {
                throw OutsideRootsException(path, roots)
            }
            val reachableRoot = canonicalRoots.firstOrNull { contains(it, resolvedAncestor) }
            if (reachableRoot == null) {
                throw OutsideRootsException(path, roots)
            }
            if (resolvedAncestor == reachableRoot) break
            ancestor = resolvedAncestor.parentFile
            depth++
        }

        return canonical
    }

    /**
     * Refuses when [candidate] is, or sits beneath, a symbolic link whose real target lies outside
     * every root.
     *
     * On Android `canonicalFile` already resolves links, making this redundant; on other platforms it
     * does not, and without this check `<root>/link-to-outside/file` would be accepted.
     */
    private fun assertNoEscapingSymlink(
        candidate: File,
        canonicalRoots: List<File>,
        requested: String,
        roots: List<String>,
    ) {
        var current: File? = candidate
        var depth = 0
        while (current != null && depth < 64) {
            if (isSymlink(current)) {
                val realTarget = try {
                    current.toPath().toRealPath().toFile()
                } catch (e: IOException) {
                    // A dangling link: its destination does not exist, so treat it as suspicious.
                    throw OutsideRootsException(requested, roots)
                }
                if (canonicalRoots.none { contains(it, realTarget) }) {
                    throw OutsideRootsException(requested, roots)
                }
            }
            if (canonicalRoots.any { it == current }) break
            current = current.parentFile
            depth++
        }
    }

    /** True when [file] exists and is a symbolic link. */
    fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    /** True when [target] is [root] itself or lives beneath it. */
    fun contains(root: File, target: File): Boolean {
        val rootPath = root.path
        val targetPath = target.path
        return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }

    /**
     * Guesses whether a file is binary by looking for NUL bytes and counting unprintable characters.
     *
     * Text tools should refuse binary input rather than hand a model a wall of mojibake.
     */
    fun isBinary(file: File): Boolean {
        if (!file.isFile) return false
        val probe = try {
            file.inputStream().use { stream -> stream.readNBytes(BINARY_PROBE_BYTES) }
        } catch (e: IOException) {
            return false
        }
        if (probe.isEmpty()) return false
        if (probe.any { it == 0.toByte() }) return true

        var suspicious = 0
        for (byte in probe) {
            val value = byte.toInt() and 0xFF
            // Allow tab, newline, form feed and carriage return; everything else below 0x20 is noise.
            val printable = value >= 0x20 || value == 0x09 || value == 0x0A || value == 0x0C || value == 0x0D
            if (!printable) suspicious++
        }
        return suspicious * 100 / probe.size > 10
    }

    /** Formats a byte count for humans and models alike. */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024L * 1024 * 1024)} GB"
    }

    /**
     * The slice of a file a read returned.
     *
     * @property lines numbered lines, already formatted for display.
     * @property totalLines total in the file, or null when the file was too large to count.
     * @property truncated whether the requested window was cut short.
     */
    data class ReadWindow(
        val lines: List<String>,
        val totalLines: Int?,
        val firstLineNumber: Int,
        val truncated: Boolean,
    )

    /**
     * Reads a window of lines from [file].
     *
     * A single streaming pass is used, so memory stays proportional to the window rather than to the
     * file. The full line count is only computed for files below [LINE_COUNT_SIZE_LIMIT], because
     * counting a multi-hundred-megabyte log is not worth the latency.
     *
     * @param offset zero-based index of the first line to return.
     * @param limit maximum number of lines to return.
     */
    fun readWindow(file: File, offset: Int, limit: Int): ReadWindow {
        val effectiveOffset = offset.coerceAtLeast(0)
        val effectiveLimit = limit.coerceIn(1, MAX_READ_LINES)
        val counted = file.length() <= LINE_COUNT_SIZE_LIMIT

        val collected = ArrayList<String>(minOf(effectiveLimit, 1024))
        var index = 0
        var truncatedLine = false

        file.bufferedReader().useLines { sequence ->
            for (rawLine in sequence) {
                if (index >= effectiveOffset && collected.size < effectiveLimit) {
                    if (rawLine.length > MAX_LINE_CHARS) {
                        truncatedLine = true
                        collected += rawLine.take(MAX_LINE_CHARS) + " …[line truncated]"
                    } else {
                        collected += rawLine
                    }
                }
                index++
                // Once the window is full and we do not need a total, stop early.
                if (collected.size >= effectiveLimit && !counted) break
            }
        }

        val total = if (counted) index else null
        val moreRemain = when {
            total != null -> effectiveOffset + collected.size < total
            else -> collected.size >= effectiveLimit
        }

        return ReadWindow(
            lines = collected,
            totalLines = total,
            firstLineNumber = effectiveOffset + 1,
            truncated = moreRemain || truncatedLine,
        )
    }

    /**
     * Renders lines with right-aligned line numbers.
     *
     * Numbers make precise edits possible: the model can refer to an exact position instead of
     * paraphrasing content and hoping it matches.
     */
    fun formatNumbered(window: ReadWindow): String {
        if (window.lines.isEmpty()) return ""
        val width = (window.firstLineNumber + window.lines.size).toString().length.coerceAtLeast(4)
        return window.lines.mapIndexed { index, line ->
            val number = (window.firstLineNumber + index).toString().padStart(width)
            "$number│$line"
        }.joinToString("\n")
    }
}
