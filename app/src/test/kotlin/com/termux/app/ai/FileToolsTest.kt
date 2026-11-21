package com.termux.app.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Behavioural tests for the filesystem tools.
 *
 * These exercise the tools the way the model would: through their argument objects, against a real
 * temporary directory. The point is to prove that editing is precise and that ambiguous or dangerous
 * operations fail loudly rather than silently doing the wrong thing.
 */
@RunWith(RobolectricTestRunner::class)
class FileToolsTest {

    private lateinit var home: File
    private lateinit var context: ToolContext

    private class TestContext(
        override val fileRoots: List<String>,
    ) : ToolContext {
        override val terminal: com.termux.shared.terminal.TerminalControl
            get() = throw UnsupportedOperationException("terminal not used by file tools")

        override val sessionId: String = "test"

        override suspend fun requestConfirmation(summary: String): Boolean = true
    }

    @Before
    fun setUp() {
        home = File(System.getProperty("java.io.tmpdir"), "file-tools-${System.nanoTime()}")
            .canonicalFile.also { it.mkdirs() }
        context = TestContext(listOf(home.path))
        BackupStore.setRootForTesting(File(home.parentFile, "backups-${System.nanoTime()}"))
    }

    private fun args(vararg pairs: Pair<String, Any>): JSONObject {
        val obj = JSONObject()
        pairs.forEach { (key, value) ->
            when (value) {
                is JSONArray -> obj.put(key, value)
                else -> obj.put(key, value)
            }
        }
        return obj
    }

    private fun write(name: String, content: String): File =
        File(home, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    // ------------------------------------------------------------------ write

    @Test
    fun `write_file creates a new file`() = runBlocking {
        val result = FileTools.WriteFileTool().invoke(
            args("path" to "notes/hello.txt", "content" to "hello world"),
            context,
        )
        assertTrue(result.asToolText().contains("created"))
        assertEquals("hello world", File(home, "notes/hello.txt").readText())
    }

    @Test
    fun `write_file overwrites and reports the previous size`() = runBlocking {
        write("existing.txt", "old content here")
        val result = FileTools.WriteFileTool().invoke(
            args("path" to "existing.txt", "content" to "new"),
            context,
        )
        val text = result.asToolText()
        assertTrue(text.contains("overwrote"))
        assertTrue(text.contains("was"))
        assertEquals("new", File(home, "existing.txt").readText())
    }

    @Test
    fun `write_file refuses an outside path`() = runBlocking {
        val result = FileTools.WriteFileTool().invoke(
            args("path" to "../escape.txt", "content" to "nope"),
            context,
        )
        assertTrue(result is ToolResult.Denied || result.asToolText().contains("Error"))
        assertFalse(File(home.parentFile, "escape.txt").exists())
    }

    @Test
    fun `write_file makes scripts executable when asked`() = runBlocking {
        FileTools.WriteFileTool().invoke(
            args("path" to "run.sh", "content" to "#!/bin/sh\n", "make_executable" to true),
            context,
        )
        val file = File(home, "run.sh")
        assertTrue(file.exists())
        // Executable bit is best-effort on non-POSIX filesystems; content must still be written.
        assertEquals("#!/bin/sh\n", file.readText())
    }

    // ------------------------------------------------------------------- read

    @Test
    fun `read_file numbers lines`() = runBlocking {
        write("n.txt", "alpha\nbeta")
        val result = FileTools.ReadFileTool().invoke(args("path" to "n.txt"), context)
        val text = result.asToolText()
        assertTrue(text.contains("alpha"))
        assertTrue(text.contains("beta"))
        assertTrue(text.contains("│"))
        assertTrue(text.contains("2 lines"))
    }

    @Test
    fun `read_file honours offset and limit`() = runBlocking {
        write("many.txt", (1..100).joinToString("\n") { "line$it" })
        val result = FileTools.ReadFileTool().invoke(
            args("path" to "many.txt", "offset" to 10, "limit" to 5),
            context,
        )
        val text = result.asToolText()
        assertTrue(text.contains("line11"))
        assertTrue(text.contains("line15"))
        assertFalse(text.contains("line16"))
        assertTrue(text.contains("truncated"))
    }

    @Test
    fun `read_file rejects a directory`() = runBlocking {
        File(home, "adir").mkdirs()
        val result = FileTools.ReadFileTool().invoke(args("path" to "adir"), context)
        assertTrue(result is ToolResult.Failed)
        assertTrue(result.asToolText().contains("list_files"))
    }

    @Test
    fun `read_file rejects binary content`() = runBlocking {
        File(home, "blob.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3))
        val result = FileTools.ReadFileTool().invoke(args("path" to "blob.bin"), context)
        assertTrue(result is ToolResult.Failed)
        assertTrue(result.asToolText().contains("binary"))
    }

    @Test
    fun `read_file reports missing files clearly`() = runBlocking {
        val result = FileTools.ReadFileTool().invoke(args("path" to "ghost.txt"), context)
        assertTrue(result is ToolResult.Failed)
        assertTrue(result.asToolText().contains("no such file"))
    }

    // ------------------------------------------------------------------- list

    @Test
    fun `list_files marks directories and sizes`() = runBlocking {
        File(home, "sub").mkdirs()
        write("a.txt", "12345")
        val result = FileTools.ListFilesTool().invoke(JSONObject(), context)
        val text = result.asToolText()
        assertTrue(text.contains("sub/"))
        assertTrue(text.contains("a.txt"))
        assertTrue(text.contains("B"))
    }

    @Test
    fun `list_files recurses when asked`() = runBlocking {
        File(home, "deep/nested").mkdirs()
        write("deep/nested/leaf.txt", "x")
        val result = FileTools.ListFilesTool().invoke(
            args("path" to "deep", "recursive" to true),
            context,
        )
        assertTrue(result.asToolText().contains("leaf.txt"))
    }

    @Test
    fun `list_files caps entries`() = runBlocking {
        (1..30).forEach { write("f$it.txt", "x") }
        val result = FileTools.ListFilesTool().invoke(args("max_entries" to 5), context)
        assertTrue(result.asToolText().contains("omitted"))
    }

    // ------------------------------------------------------------------- edit

    @Test
    fun `edit_file replaces a unique string`() = runBlocking {
        write("e.txt", "before\nTARGET\nafter")
        val result = FileTools.EditFileTool().invoke(
            args("path" to "e.txt", "old_string" to "TARGET", "new_string" to "REPLACED"),
            context,
        )
        assertTrue(result is ToolResult.Ok)
        assertEquals("before\nREPLACED\nafter", File(home, "e.txt").readText())
    }

    @Test
    fun `edit_file refuses an ambiguous match`() = runBlocking {
        write("dup.txt", "same\nsame\nsame")
        val result = FileTools.EditFileTool().invoke(
            args("path" to "dup.txt", "old_string" to "same", "new_string" to "other"),
            context,
        )
        assertTrue(result is ToolResult.Failed)
        assertTrue(result.asToolText().contains("3 times"))
        // Nothing must have changed.
        assertEquals("same\nsame\nsame", File(home, "dup.txt").readText())
    }

    @Test
    fun `edit_file replaces all when asked`() = runBlocking {
        write("dup2.txt", "x\ny\nx")
        FileTools.EditFileTool().invoke(
            args("path" to "dup2.txt", "old_string" to "x", "new_string" to "z", "replace_all" to true),
            context,
        )
        assertEquals("z\ny\nz", File(home, "dup2.txt").readText())
    }

    @Test
    fun `edit_file reports a missing needle without changing the file`() = runBlocking {
        write("keep.txt", "content")
        val result = FileTools.EditFileTool().invoke(
            args("path" to "keep.txt", "old_string" to "absent", "new_string" to "y"),
            context,
        )
        assertTrue(result is ToolResult.Failed)
        assertTrue(result.asToolText().contains("not found"))
        assertEquals("content", File(home, "keep.txt").readText())
    }

    @Test
    fun `edit_file rejects identical old and new strings`() = runBlocking {
        write("same.txt", "abc")
        val result = FileTools.EditFileTool().invoke(
            args("path" to "same.txt", "old_string" to "abc", "new_string" to "abc"),
            context,
        )
        assertTrue(result is ToolResult.Failed)
    }

    @Test
    fun `edit_file can create when missing and asked`() = runBlocking {
        val result = FileTools.EditFileTool().invoke(
            args(
                "path" to "brand-new.txt",
                "old_string" to "placeholder",
                "new_string" to "fresh content",
                "create_if_missing" to true,
            ),
            context,
        )
        assertTrue(result is ToolResult.Ok)
        assertEquals("fresh content", File(home, "brand-new.txt").readText())
    }

    @Test
    fun `edit_file refuses a missing file by default`() = runBlocking {
        val result = FileTools.EditFileTool().invoke(
            args("path" to "nope.txt", "old_string" to "a", "new_string" to "b"),
            context,
        )
        assertTrue(result is ToolResult.Failed)
        assertFalse(File(home, "nope.txt").exists())
    }

    // -------------------------------------------------------------- multi_edit

    @Test
    fun `multi_edit applies several changes in one call`() = runBlocking {
        write("m.txt", "alpha beta gamma")
        val edits = JSONArray()
            .put(JSONObject().put("old_string", "alpha").put("new_string", "A"))
            .put(JSONObject().put("old_string", "gamma").put("new_string", "G"))
        val result = FileTools.MultiEditTool().invoke(
            args("path" to "m.txt", "edits" to edits),
            context,
        )
        assertTrue(result is ToolResult.Ok)
        assertEquals("A beta G", File(home, "m.txt").readText())
    }

    @Test
    fun `multi_edit is all or nothing`() = runBlocking {
        write("atomic.txt", "keep me")
        val edits = JSONArray()
            .put(JSONObject().put("old_string", "keep").put("new_string", "KEEP"))
            .put(JSONObject().put("old_string", "absent").put("new_string", "x"))
        val result = FileTools.MultiEditTool().invoke(
            args("path" to "atomic.txt", "edits" to edits),
            context,
        )
        assertTrue(result is ToolResult.Failed)
        // The first edit must not have been applied.
        assertEquals("keep me", File(home, "atomic.txt").readText())
    }

    @Test
    fun `multi_edit reports which edit failed`() = runBlocking {
        write("report.txt", "aaa")
        val edits = JSONArray()
            .put(JSONObject().put("old_string", "aaa").put("new_string", "bbb"))
            .put(JSONObject().put("old_string", "zzz").put("new_string", "y"))
        val result = FileTools.MultiEditTool().invoke(
            args("path" to "report.txt", "edits" to edits),
            context,
        )
        assertTrue(result.asToolText().contains("edit #2"))
    }

    @Test
    fun `multi_edit rejects an empty edit list`() = runBlocking {
        write("e2.txt", "x")
        val result = FileTools.MultiEditTool().invoke(
            args("path" to "e2.txt", "edits" to JSONArray()),
            context,
        )
        assertTrue(result is ToolResult.Failed)
    }

    // ------------------------------------------------------------------- move

    @Test
    fun `move_file renames a file`() = runBlocking {
        write("old.txt", "data")
        val result = FileTools.MoveFileTool().invoke(
            args("source" to "old.txt", "destination" to "new.txt"),
            context,
        )
        assertTrue(result is ToolResult.Ok)
        assertFalse(File(home, "old.txt").exists())
        assertEquals("data", File(home, "new.txt").readText())
    }

    @Test
    fun `move_file refuses to overwrite without permission`() = runBlocking {
        write("a.txt", "a")
        write("b.txt", "b")
        val result = FileTools.MoveFileTool().invoke(
            args("source" to "a.txt", "destination" to "b.txt"),
            context,
        )
        assertTrue(result is ToolResult.Failed)
        assertTrue(result.asToolText().contains("overwrite"))
        assertEquals("b", File(home, "b.txt").readText())
    }

    @Test
    fun `move_file refuses moving a directory into itself`() = runBlocking {
        File(home, "dir/inner").mkdirs()
        val result = FileTools.MoveFileTool().invoke(
            args("source" to "dir", "destination" to "dir/inner/loop"),
            context,
        )
        assertTrue(result is ToolResult.Failed)
    }

    // ----------------------------------------------------------------- delete

    @Test
    fun `delete_file removes a file`() = runBlocking {
        write("gone.txt", "x")
        val result = FileTools.DeleteFileTool().invoke(args("path" to "gone.txt"), context)
        assertTrue(result is ToolResult.Ok)
        assertFalse(File(home, "gone.txt").exists())
    }

    @Test
    fun `delete_file requires recursive for non-empty directories`() = runBlocking {
        File(home, "full").mkdirs()
        write("full/inside.txt", "x")
        val refused = FileTools.DeleteFileTool().invoke(args("path" to "full"), context)
        assertTrue(refused is ToolResult.Failed)

        val allowed = FileTools.DeleteFileTool().invoke(
            args("path" to "full", "recursive" to true),
            context,
        )
        assertTrue(allowed is ToolResult.Ok)
        assertFalse(File(home, "full").exists())
    }

    @Test
    fun `delete_file refuses a root directory`() = runBlocking {
        val result = FileTools.DeleteFileTool().invoke(args("path" to home.path), context)
        assertTrue(result is ToolResult.Denied)
        assertTrue(home.exists())
    }

    @Test
    fun `delete_file refuses an outside path`() = runBlocking {
        val result = FileTools.DeleteFileTool().invoke(args("path" to "../outside"), context)
        assertTrue(result is ToolResult.Denied || result is ToolResult.Failed)
    }

    // ---------------------------------------------------------------- backups

    @Test
    fun `editing creates a backup of the previous content`() = runBlocking {
        write("backed.txt", "original content")
        FileTools.EditFileTool().invoke(
            args("path" to "backed.txt", "old_string" to "original", "new_string" to "changed"),
            context,
        )
        val backups = BackupStore.list("test", "backed.txt")
        assertTrue("a backup should exist", backups.isNotEmpty())
        assertEquals("original content", backups.first().readText())
    }

    @Test
    fun `overwriting creates a backup`() = runBlocking {
        write("ow.txt", "previous")
        FileTools.WriteFileTool().invoke(
            args("path" to "ow.txt", "content" to "current"),
            context,
        )
        val backups = BackupStore.list("test", "ow.txt")
        assertTrue(backups.isNotEmpty())
        assertEquals("previous", backups.first().readText())
    }

    // --------------------------------------------------------------- registry

    @Test
    fun `all file tools are registered with distinct names`() {
        val tools = FileTools.all()
        val names = tools.map { it.spec.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf("read_file", "write_file", "edit_file", "multi_edit")))
    }

    @Test
    fun `mutating file tools declare a non-safe risk`() {
        val byName = FileTools.all().associateBy { it.spec.name }
        assertEquals(RiskLevel.MUTATING, byName.getValue("edit_file").spec.risk)
        assertEquals(RiskLevel.MUTATING, byName.getValue("multi_edit").spec.risk)
        assertEquals(RiskLevel.MUTATING, byName.getValue("write_file").spec.risk)
        assertEquals(RiskLevel.DESTRUCTIVE, byName.getValue("delete_file").spec.risk)
        assertEquals(RiskLevel.DESTRUCTIVE, byName.getValue("move_file").spec.risk)
        assertEquals(RiskLevel.SAFE, byName.getValue("read_file").spec.risk)
    }

    @Test
    fun `occurrence counting skips past each match`() {
        val tool = FileTools.EditFileTool()
        // "aaaa" contains four separate "a" matches; the scan advances past each one.
        assertEquals(4, tool.countOccurrences("aaaa", "a"))
        assertEquals(2, tool.countOccurrences("abcabc", "abc"))
        assertEquals(0, tool.countOccurrences("abc", ""))
        assertEquals(0, tool.countOccurrences("abc", "z"))
    }

    @Test
    fun `occurrence counting handles adjacent distinct needles`() {
        val tool = FileTools.EditFileTool()
        assertEquals(1, tool.countOccurrences("abc", "abc"))
        assertEquals(2, tool.countOccurrences("abab", "ab"))
    }
}
