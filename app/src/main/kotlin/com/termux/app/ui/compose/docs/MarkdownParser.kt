package com.termux.app.ui.compose.docs

/**
 * A deliberately small Markdown model tailored to the bundled command
 * documentation files under assets/cmd.
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class BulletItem(val indent: Int, val text: String) : MdBlock()
    data class NumberedItem(val indent: Int, val number: Int, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    object Rule : MdBlock()
}

/** Inline span kinds produced by [MarkdownParser.parseInline]. */
sealed class MdSpan {
    data class Normal(val text: String) : MdSpan()
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    data class Code(val text: String) : MdSpan()
    data class Link(val text: String, val url: String) : MdSpan()
}

object MarkdownParser {

    private val BULLET_REGEX = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED_REGEX = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val HTML_COMMENT_REGEX = Regex("(?s)<!--.*?-->")

    private fun isUnderline(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        var allEquals = true
        var allDashes = true
        for (c in t) {
            if (c != '=') allEquals = false
            if (c != '-') allDashes = false
        }
        return allEquals || allDashes
    }

    /**
     * Parse Markdown source into a list of blocks.
     */
    fun parse(source: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        // Strip HTML comments (the bundled docs end with an HTML comment) and
        // normalise line endings.
        val withoutComments = HTML_COMMENT_REGEX.replace(source, "")
        val normalized = withoutComments.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')

        val paragraph = StringBuilder()
        val code = StringBuilder()
        var inCodeFence = false

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()

            // Fenced code block boundaries.
            if (line.trimStart().startsWith("```")) {
                if (inCodeFence) {
                    blocks.add(MdBlock.CodeBlock(code.toString().trimEnd('\n')))
                    code.setLength(0)
                    inCodeFence = false
                } else {
                    if (paragraph.isNotEmpty()) {
                        blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                        paragraph.setLength(0)
                    }
                    inCodeFence = true
                }
                i++
                continue
            }

            if (inCodeFence) {
                code.append(line).append('\n')
                i++
                continue
            }

            val trimmed = line.trim()

            // Setext heading: the line itself followed by === or ---.
            if (trimmed.isNotEmpty() && i + 1 < lines.size && isUnderline(lines[i + 1])) {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                val level = if (lines[i + 1].trim().startsWith("=")) 1 else 2
                blocks.add(MdBlock.Heading(level, trimmed))
                i += 2
                continue
            }

            if (trimmed.isEmpty()) {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                i++
                continue
            }

            // ATX heading.
            if (trimmed.startsWith("#")) {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                var level = 0
                while (level < trimmed.length && level < 6 && trimmed[level] == '#') level++
                if (level == 0) level = 1
                val text = trimmed.substring(level).trim()
                blocks.add(MdBlock.Heading(level, text))
                i++
                continue
            }

            // Horizontal rule.
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                blocks.add(MdBlock.Rule)
                i++
                continue
            }

            // Blockquote.
            if (trimmed.startsWith(">")) {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                blocks.add(MdBlock.Quote(trimmed.substring(1).trim()))
                i++
                continue
            }

            // Bullet list item.
            val bullet = BULLET_REGEX.find(line)
            if (bullet != null) {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                val indent = bullet.groupValues[1].length / 2
                blocks.add(MdBlock.BulletItem(indent, bullet.groupValues[2].trim()))
                i++
                continue
            }

            // Numbered list item.
            val numbered = NUMBERED_REGEX.find(line)
            if (numbered != null) {
                if (paragraph.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                    paragraph.setLength(0)
                }
                val indent = numbered.groupValues[1].length / 2
                val number = numbered.groupValues[2].toIntOrNull() ?: 1
                blocks.add(MdBlock.NumberedItem(indent, number, numbered.groupValues[3].trim()))
                i++
                continue
            }

            // Regular paragraph text.
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(trimmed)
            i++
        }

        if (inCodeFence && code.isNotEmpty()) {
            blocks.add(MdBlock.CodeBlock(code.toString().trimEnd('\n')))
        }
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        }

        return blocks
    }

    /**
     * Split a line into inline spans (bold, italic, inline code, links).
     */
    fun parseInline(text: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        val plain = StringBuilder()
        var i = 0

        while (i < text.length) {
            val c = text[i]

            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    if (plain.isNotEmpty()) {
                        spans.add(MdSpan.Normal(plain.toString()))
                        plain.setLength(0)
                    }
                    spans.add(MdSpan.Code(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    if (plain.isNotEmpty()) {
                        spans.add(MdSpan.Normal(plain.toString()))
                        plain.setLength(0)
                    }
                    spans.add(MdSpan.Bold(text.substring(i + 2, end)))
                    i = end + 2
                    continue
                }
            }

            if (c == '*') {
                val end = text.indexOf('*', i + 1)
                if (end > i) {
                    if (plain.isNotEmpty()) {
                        spans.add(MdSpan.Normal(plain.toString()))
                        plain.setLength(0)
                    }
                    spans.add(MdSpan.Italic(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            if (c == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket) {
                        if (plain.isNotEmpty()) {
                            spans.add(MdSpan.Normal(plain.toString()))
                            plain.setLength(0)
                        }
                        spans.add(
                            MdSpan.Link(
                                text.substring(i + 1, closeBracket),
                                text.substring(closeBracket + 2, closeParen)
                            )
                        )
                        i = closeParen + 1
                        continue
                    }
                }
            }

            plain.append(c)
            i++
        }

        if (plain.isNotEmpty()) spans.add(MdSpan.Normal(plain.toString()))
        return spans
    }

    /**
     * Strip inline markup, used for list previews and search matching.
     */
    fun toPlainText(text: String): String {
        val spans = parseInline(text)
        val builder = StringBuilder()
        for (span in spans) {
            when (span) {
                is MdSpan.Normal -> builder.append(span.text)
                is MdSpan.Bold -> builder.append(span.text)
                is MdSpan.Italic -> builder.append(span.text)
                is MdSpan.Code -> builder.append(span.text)
                is MdSpan.Link -> builder.append(span.text)
            }
        }
        return builder.toString()
    }
}
