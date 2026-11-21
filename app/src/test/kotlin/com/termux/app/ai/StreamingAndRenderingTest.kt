package com.termux.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the streamed tool-call assembly.
 *
 * This is the part of streaming most likely to be wrong: the wire format spreads one logical call over
 * many events, indexed but not ordered, with `arguments` arriving in arbitrary string chunks. Getting it
 * wrong silently truncates tool arguments.
 */
@RunWith(RobolectricTestRunner::class)
class ToolCallAccumulatorTest {

    private fun delta(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ): JSONObject = JSONObject().put("index", index).apply {
        id?.let { put("id", it) }
        val fn = JSONObject()
        name?.let { fn.put("name", it) }
        arguments?.let { fn.put("arguments", it) }
        if (fn.length() > 0) put("function", fn)
    }

    private fun arrayOf(vararg items: JSONObject): JSONArray = JSONArray().apply { items.forEach { put(it) } }

    @Test
    fun `single call assembled from fragments`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(0, id = "call_1", name = "read_file")))
        acc.add(arrayOf(delta(0, arguments = "{\"path\":")))
        acc.add(arrayOf(delta(0, arguments = "\"a.txt\"}")))

        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("{\"path\":\"a.txt\"}", calls[0].arguments)
    }

    @Test
    fun `two calls are kept separate by index`() {
        val acc = ToolCallAccumulator()
        acc.add(
            arrayOf(
                delta(0, id = "call_a", name = "read_terminal"),
                delta(1, id = "call_b", name = "send_command"),
            ),
        )
        acc.add(arrayOf(delta(0, arguments = "{}")))
        acc.add(arrayOf(delta(1, arguments = "{\"command\":\"ls\"}")))

        val calls = acc.build()
        assertEquals(2, calls.size)
        assertEquals("read_terminal", calls[0].name)
        assertEquals("send_command", calls[1].name)
        assertEquals("{\"command\":\"ls\"}", calls[1].arguments)
    }

    @Test
    fun `calls are ordered by index even when arrival order differs`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(1, id = "b", name = "second")))
        acc.add(arrayOf(delta(0, id = "a", name = "first")))
        val calls = acc.build()
        assertEquals("first", calls[0].name)
        assertEquals("second", calls[1].name)
    }

    @Test
    fun `missing arguments default to an empty object`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(0, id = "x", name = "read_terminal")))
        assertEquals("{}", acc.build()[0].arguments)
    }

    @Test
    fun `entries without a name are dropped`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(0, arguments = "partial")))
        assertTrue(acc.build().isEmpty())
    }

    @Test
    fun `missing id falls back to an index based id`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(2, name = "tool")))
        assertEquals("call_2", acc.build()[0].id)
    }

    @Test
    fun `empty accumulator builds nothing`() {
        assertTrue(ToolCallAccumulator().build().isEmpty())
        assertTrue(ToolCallAccumulator().isEmpty())
    }

    @Test
    fun `accumulator is not empty after a fragment`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(0, name = "t")))
        assertFalse(acc.isEmpty())
    }

    @Test
    fun `arguments concatenation preserves order exactly`() {
        val acc = ToolCallAccumulator()
        acc.add(arrayOf(delta(0, name = "t", arguments = "abc")))
        acc.add(arrayOf(delta(0, arguments = "def")))
        acc.add(arrayOf(delta(0, arguments = "ghi")))
        assertEquals("abcdefghi", acc.build()[0].arguments)
    }
}

/**
 * Tests for the plain-text Markdown fallback.
 *
 * The rich renderer is Markwon's responsibility, but the fallback must never show raw markup, so its
 * behaviour is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class PlainMarkdownTest {

    @Test
    fun `empty input stays empty`() {
        assertEquals("", PlainMarkdown.strip(""))
    }

    @Test
    fun `headings lose their hashes`() {
        assertEquals("Title", PlainMarkdown.strip("# Title"))
        assertEquals("Sub", PlainMarkdown.strip("### Sub"))
    }

    @Test
    fun `bold and italic markers are removed`() {
        assertEquals("hello world", PlainMarkdown.strip("**hello** *world*"))
        assertEquals("under", PlainMarkdown.strip("__under__"))
    }

    @Test
    fun `inline code loses backticks`() {
        assertEquals("run ls now", PlainMarkdown.strip("run `ls` now"))
    }

    @Test
    fun `links keep their label`() {
        assertEquals("docs", PlainMarkdown.strip("[docs](https://example.com)"))
    }

    @Test
    fun `bullets are normalised`() {
        val stripped = PlainMarkdown.strip("- one\n* two")
        assertTrue(stripped.contains("• one"))
        assertTrue(stripped.contains("• two"))
    }

    @Test
    fun `numbered lists become bullets`() {
        assertTrue(PlainMarkdown.strip("1. first").contains("• first"))
    }

    @Test
    fun `blockquotes lose the marker`() {
        assertEquals("quoted", PlainMarkdown.strip("> quoted"))
    }

    @Test
    fun `code fences are removed but content kept`() {
        val input = "before\n```\ncode here\n```\nafter"
        val stripped = PlainMarkdown.strip(input)
        assertFalse(stripped.contains("```"))
        assertTrue(stripped.contains("code here"))
        assertTrue(stripped.contains("before"))
        assertTrue(stripped.contains("after"))
    }

    @Test
    fun `plain text is unchanged`() {
        assertEquals("just text", PlainMarkdown.strip("just text"))
    }

    @Test
    fun `multiple lines are all processed`() {
        val stripped = PlainMarkdown.strip("# A\n**B**\n`C`")
        assertEquals(listOf("A", "B", "C"), stripped.lines())
    }
}
