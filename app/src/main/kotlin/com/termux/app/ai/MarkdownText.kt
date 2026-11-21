package com.termux.app.ai

import android.text.Spanned
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Renders Markdown inside a Compose layout.
 *
 * Markwon produces a [Spanned], which Compose's Text cannot display with formatting, so the text is
 * hosted in a [TextView] via [AndroidView]. A cache keyed on the content avoids re-parsing on every
 * frame, which matters because streaming updates this component continuously.
 *
 * Colours are applied to the view and to a per-theme [Markwon] instance rather than baked into spans, so
 * the output follows the active theme instead of whatever colours were current when the instance was
 * built. Markwon's defaults are built for a white page: inline code came out as dark text on a pale grey
 * box, which reads as a highlighter smear inside a dark panel.
 *
 * @param color the body text colour.
 * @param codeBackground background for inline code and fenced blocks.
 * @param codeColor text colour inside code.
 * @param linkColor colour for links and list bullets.
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    codeBackground: Color = color.copy(alpha = 0.10f),
    codeColor: Color = color,
    linkColor: Color = color,
) {
    val context = LocalContext.current

    // Rebuilt when the palette changes, which happens on a theme switch and not per frame.
    val markwon = remember(context, codeBackground, codeColor, linkColor) {
        Markwon.builder(context)
            .usePlugin(
                // Markwon has no public builder for a whole theme; the supported hook is to adjust the
                // shared builder from a plugin. CorePlugin itself is still added by Markwon, so this
                // only changes the values it uses.
                object : AbstractMarkwonPlugin() {
                    override fun configureTheme(builder: MarkwonTheme.Builder) {
                        val density = context.resources.displayMetrics.density
                        builder
                            .codeBackgroundColor(codeBackground.toArgb())
                            .codeBlockBackgroundColor(codeBackground.toArgb())
                            .codeTextColor(codeColor.toArgb())
                            .codeBlockTextColor(codeColor.toArgb())
                            .listItemColor(linkColor.toArgb())
                            .linkColor(linkColor.toArgb())
                            // Markwon's default margins are generous for a chat bubble; roughly halved.
                            .blockMargin((6 * density).toInt())
                    }
                },
            )
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

    val spanned: Spanned = remember(markdown, markwon) {
        // toMarkdown always returns a Spanned; the fallback keeps the compiler happy without changing
        // behaviour when parsing fails.
        runCatching { markwon.toMarkdown(markdown) }
            .getOrElse { android.text.SpannableString(markdown) }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.15f)
                textSize = 13f
            }
        },
        update = { view ->
            view.setTextColor(color.toArgb())
            markwon.setParsedMarkdown(view, spanned)
        },
    )
}

/**
 * A minimal Markdown renderer used when Markwon is unavailable.
 *
 * Kept as a pure function so the fallback behaviour is testable: producing readable plain text is far
 * better than showing raw `**` markers if the rich renderer ever fails.
 */
object PlainMarkdown {

    /** Strips the most common Markdown syntax, keeping the content readable. */
    fun strip(markdown: String): String {
        if (markdown.isEmpty()) return ""
        val builder = StringBuilder(markdown.length)
        var inFence = false

        for (rawLine in markdown.lines()) {
            val line = rawLine
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                // Code blocks keep their content but lose the fence markers.
                builder.appendLine(line)
                continue
            }
            var processed = line
            // Headings: drop the leading #'s.
            processed = processed.replace(Regex("^#{1,6}\\s+"), "")
            // List markers: keep the bullet, normalise it.
            processed = processed.replace(Regex("^\\s*[-*+]\\s+"), "• ")
            processed = processed.replace(Regex("^\\s*\\d+\\.\\s+"), "• ")
            // Emphasis and inline code markers.
            processed = processed.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            processed = processed.replace(Regex("__(.+?)__"), "$1")
            processed = processed.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
            processed = processed.replace(Regex("`([^`]+)`"), "$1")
            // Links: keep the label, drop the URL.
            processed = processed.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            // Blockquote markers.
            processed = processed.replace(Regex("^>\\s?"), "")
            builder.appendLine(processed)
        }
        return builder.toString().trim()
    }
}
