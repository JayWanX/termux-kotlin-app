package com.termux.app.terminal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named session presets: a name, a starting directory and an optional first command.
 *
 * The motivation is mundane but real: the same three sessions get opened every time (a project shell, a
 * log tail, a REPL), each requiring the same `cd` afterwards. A template collapses that to one tap.
 *
 * Templates are stored as JSON so they survive restarts and can be edited by hand if the user prefers.
 */
object SessionTemplates {

    /** One preset. */
    data class Template(
        val id: String,
        val name: String,
        val workingDirectory: String? = null,
        val initialCommand: String? = null,
        /** True for the presets shipped with the app, which cannot be deleted. */
        val isBuiltin: Boolean = false,
    ) {
        /** Converts to JSON for persistence. */
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .apply {
                workingDirectory?.let { put("cwd", it) }
                initialCommand?.let { put("cmd", it) }
                put("builtin", isBuiltin)
            }

        companion object {
            /** Parses a template, returning null when required fields are missing. */
            fun fromJson(json: JSONObject): Template? {
                val id = json.optString("id").trim()
                val name = json.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) return null
                return Template(
                    id = id,
                    name = name,
                    workingDirectory = json.optString("cwd").trim().takeIf { it.isNotEmpty() },
                    initialCommand = json.optString("cmd").trim().takeIf { it.isNotEmpty() },
                    isBuiltin = json.optBoolean("builtin", false),
                )
            }
        }
    }

    /** Presets that exist on first launch, covering the common Termux entry points. */
    val BUILTINS: List<Template> = listOf(
        Template(
            id = "home",
            name = "Home",
            workingDirectory = "~",
            isBuiltin = true,
        ),
        Template(
            id = "downloads",
            name = "Downloads",
            workingDirectory = "/sdcard/Download",
            isBuiltin = true,
        ),
        Template(
            id = "storage",
            name = "Shared storage",
            workingDirectory = "/sdcard",
            isBuiltin = true,
        ),
    )

    /** Sanitises a user-supplied template id, returning null when nothing usable remains. */
    fun sanitizeId(raw: String): String? {
        val normalized = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        if (normalized.isEmpty() || normalized.length > 48) return null
        return normalized
    }

    /** Validates a template before it is saved, returning a human-readable problem or null. */
    fun validate(template: Template): String? {
        if (template.name.isBlank()) return "name must not be empty"
        if (template.name.length > 64) return "name is too long"

        val cwd = template.workingDirectory
        if (cwd != null && cwd.isNotBlank()) {
            // `~` is expanded by the shell, so only absolute-looking paths can be checked here.
            val expanded = if (cwd.startsWith("~")) cwd.removePrefix("~").ifEmpty { "/" } else cwd
            if (!expanded.startsWith("/")) {
                return "working directory must be an absolute path (or start with ~)"
            }
        }

        template.initialCommand?.let { command ->
            if (command.contains('\n')) return "initial command must be a single line"
            if (command.length > 1_000) return "initial command is too long"
        }
        return null
    }

    /**
     * The command to type after the session opens, if any.
     *
     * A leading `cd` is produced when a directory is set, so a template with both fields behaves as the
     * user expects: change directory, then run the command.
     */
    fun launchCommand(template: Template): String? {
        val parts = mutableListOf<String>()
        template.workingDirectory?.takeIf { it.isNotBlank() }?.let {
            parts += "cd " + shellQuote(it)
        }
        template.initialCommand?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (parts.isEmpty()) return null
        return parts.joinToString(" && ")
    }

    /** Quotes a path for safe interpolation into a shell command. */
    fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        val safe = value.all { it.isLetterOrDigit() || it in "/._-~" }
        if (safe) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

/**
 * Persists [SessionTemplates.Template] entries to a JSON file.
 *
 * Built-in templates are always present and cannot be removed; user templates are merged with them on
 * load, so an upgrade that adds a built-in makes it appear without wiping the user's own entries.
 */
class SessionTemplateStore(private val file: File) {

    /** Loads templates: built-ins first, then user entries, de-duplicated by id. */
    fun load(): List<SessionTemplates.Template> {
        val userTemplates = readUserTemplates()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SessionTemplates.Template>()

        for (builtin in SessionTemplates.BUILTINS) {
            if (seen.add(builtin.id)) result += builtin
        }
        for (template in userTemplates) {
            if (seen.add(template.id)) result += template
        }
        return result
    }

    /** Adds or replaces a user template. Returns false when the template is invalid or built-in. */
    fun save(template: SessionTemplates.Template): Boolean {
        if (SessionTemplates.validate(template) != null) return false
        if (SessionTemplates.BUILTINS.any { it.id == template.id }) return false

        val current = readUserTemplates().toMutableList()
        val index = current.indexOfFirst { it.id == template.id }
        if (index >= 0) current[index] = template else current += template
        return writeUserTemplates(current)
    }

    /**
     * Deletes a user template.
     *
     * Built-ins are refused: removing them would make the list feel broken after a reinstall, and they
     * are three entries the user can simply ignore.
     */
    fun delete(templateId: String): Boolean {
        if (SessionTemplates.BUILTINS.any { it.id == templateId }) return false
        val current = readUserTemplates().filterNot { it.id == templateId }
        return writeUserTemplates(current)
    }

    /** Reads only the user-defined templates, tolerating a corrupt or missing file. */
    fun readUserTemplates(): List<SessionTemplates.Template> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val text = file.readText()
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                SessionTemplates.Template.fromJson(array.getJSONObject(index))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeUserTemplates(templates: List<SessionTemplates.Template>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        templates.forEach { array.put(it.toJson()) }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(array.toString(2))
            temp.delete()
        }
        true
    }.getOrDefault(false)
}
