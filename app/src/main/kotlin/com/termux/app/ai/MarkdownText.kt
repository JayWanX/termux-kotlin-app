package com.termux.app.ai

import android.text.Spanned
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Renders Markdown inside a Compose layout.
 *
 * Markwon produces a [Spanned], which Compose's Text cannot display with formatting, so the text is
 * hosted in a [TextView] via [AndroidView]. A cache keyed on the content avoids re-parsing on every
 * frame, which matters because streaming updates this component continuously.
 *
 * The text colour is applied to the view rather than baked into spans so that it follows the current
 * theme instead of whatever colour happened to be active when the Markwon instance was created.
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Markwon instances are expensive; one per screen is enough.
    val markwon = remember(context) {
        Markwon.builder(context)
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
