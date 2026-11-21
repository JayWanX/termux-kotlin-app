package com.termux.app.ui.compose.docs

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads the bundled command documentation from `assets/cmd`.
 *
 * Every entry is a Markdown file named after the command, e.g. `ls.md`.
 */
class CommandDocRepository(private val context: Context) {

    companion object {
        private const val ASSET_DIR = "cmd"
        private const val EXTENSION = ".md"
    }

    @Volatile
    private var cachedCommands: List<String>? = null

    /** Command name -> one-line description, populated lazily. */
    private val summaryCache = HashMap<String, String>()

    /** Command name -> full markdown, populated lazily. */
    private val markdownCache = HashMap<String, String>()

    /**
     * All available command names, sorted alphabetically.
     */
    fun getCommands(): List<String> {
        val existing = cachedCommands
        if (existing != null) return existing

        var commands: List<String> = ArrayList()
        try {
            val entries = context.assets.list(ASSET_DIR)
            if (entries != null) {
                val list = ArrayList<String>()
                for (entry in entries) {
                    if (entry.endsWith(EXTENSION, ignoreCase = true)) {
                        list.add(entry.substring(0, entry.length - EXTENSION.length))
                    }
                }
                list.sortWith(compareBy { it.lowercase() })
                commands = list
            }
        } catch (e: Exception) {
            commands = ArrayList()
        }

        cachedCommands = commands
        return commands
    }

    /**
     * Load the Markdown source for a single command, or null when unavailable.
     */
    @Synchronized
    fun loadMarkdown(command: String): String? {
        if (markdownCache.containsKey(command)) return markdownCache[command]

        var content: String? = null
        try {
            context.assets.open("$ASSET_DIR/$command$EXTENSION").use { stream ->
                content = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        } catch (e: Exception) {
            content = null
        }
        markdownCache[command] = content ?: ""
        return content
    }

    /**
     * First non-empty, non-heading line of a command document, used as a short
     * one-line description in the list.
     */
    @Synchronized
    fun loadSummary(command: String): String {
        val cached = summaryCache[command]
        if (cached != null) return cached

        val markdown = loadMarkdown(command) ?: return ""
        val lines = markdown.replace("\r\n", "\n").split('\n')

        // Skip the leading "name" + "===" setext heading and blank lines.
        var index = 0
        if (index < lines.size && lines[index].trim().isNotEmpty() && !lines[index].trim().startsWith("#")) {
            val next = if (index + 1 < lines.size) lines[index + 1].trim() else ""
            if (next.isNotEmpty() && next.all { it == '=' }) index += 2
        }

        var result = ""
        while (index < lines.size) {
            val line = lines[index].trim()
            val isUnderline = line.isNotEmpty() && (line.all { it == '=' } || line.all { it == '-' })
            if (line.isNotEmpty() &&
                !line.startsWith("#") &&
                !line.startsWith("```") &&
                !isUnderline
            ) {
                result = MarkdownParser.toPlainText(line)
                if (result.length > 120) result = result.substring(0, 120)
                break
            }
            index++
        }
        summaryCache[command] = result
        return result
    }

    /**
     * Filter command names by a query. Command name matches come first, then
     * commands whose cached description matches.
     */
    fun search(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getCommands()

        val lower = trimmed.lowercase()
        val byName = ArrayList<String>()
        val byContent = ArrayList<String>()

        for (command in getCommands()) {
            if (command.lowercase().contains(lower)) {
                byName.add(command)
            } else {
                val cached = summaryCache[command]
                if (cached != null && cached.lowercase().contains(lower)) {
                    byContent.add(command)
                }
            }
        }

        val result = ArrayList<String>(byName.size + byContent.size)
        result.addAll(byName)
        result.addAll(byContent)
        return result
    }
}
