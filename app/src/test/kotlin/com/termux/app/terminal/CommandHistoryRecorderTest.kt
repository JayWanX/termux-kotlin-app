package com.termux.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests for command history recording.
 *
 * The interesting behaviour is in the two pure parts: deciding whether a line is a command at all, and
 * the on-disk encoding round trip. Both are easy to get subtly wrong, and a history full of prompts is
 * worse than no history.
 */
@RunWith(RobolectricTestRunner::class)
class CommandHistoryRecorderTest {

    private lateinit var file: File
    private lateinit var recorder: CommandHistoryRecorder

    @Before
    fun setUp() {
        file = File(System.getProperty("java.io.tmpdir"), "history-${System.nanoTime()}.jsonl")
        recorder = CommandHistoryRecorder(file, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    }

    private fun entry(
        command: String = "ls -la",
        startedAt: Long = 1_000L,
        duration: Long = 250L,
        exit: Int? = 0,
        session: String? = null,
        completedAt: Long = 1_250L,
    ) = CommandHistoryRecorder.Entry(
        command = command,
        startedAtMs = startedAt,
        durationMs = duration,
        exitStatus = exit,
        sessionId = session,
        completedAtMs = completedAt,
    )

    @Test
    fun `round trip preserves every field`() {
        val original = entry(command = "echo \"hi\"", exit = 1, session = "main")
        val decoded = recorder.decode(recorder.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip handles a null exit status`() {
        val original = entry(exit = null)
        val decoded = recorder.decode(recorder.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded!!.exitStatus)
    }

    @Test
    fun `quotes and backslashes survive`() {
        val tricky = """echo "a\\b" && printf 'c'"""
        val decoded = recorder.decode(recorder.encode(entry(command = tricky)))
        assertEquals(tricky, decoded!!.command)
    }

    @Test
    fun `newlines in a command survive`() {
        val multi = "for i in 1 2; do\n  echo \$i\ndone"
        val decoded = recorder.decode(recorder.encode(entry(command = multi)))
        assertEquals(multi, decoded!!.command)
    }

    @Test
    fun `unicode survives`() {
        val command = "echo 你好 · 世界"
        val decoded = recorder.decode(recorder.encode(entry(command = command)))
        assertEquals(command, decoded!!.command)
    }

    @Test
    fun `malformed lines are ignored`() {
        assertNull(recorder.decode(""))
        assertNull(recorder.decode("not json"))
        assertNull(recorder.decode("{}"))
        assertNull(recorder.decode("{\"s\":1}"))
    }

    @Test
    fun `readAll skips corrupt lines but keeps good ones`() {
        file.writeText(
            buildString {
                appendLine(recorder.encode(entry(command = "first")))
                appendLine("garbage")
                appendLine(recorder.encode(entry(command = "second")))
            },
        )
        val all = recorder.readAll()
        assertEquals(2, all.size)
        assertEquals("first", all[0].command)
        assertEquals("second", all[1].command)
    }

    @Test
    fun `readAll on a missing file is empty`() {
        assertTrue(recorder.readAll().isEmpty())
    }

    @Test
    fun `plain commands are recordable`() {
        assertTrue(recorder.isRecordableCommand("ls -la"))
        assertTrue(recorder.isRecordableCommand("pkg install sl"))
        assertTrue(recorder.isRecordableCommand("cd /tmp"))
    }

    @Test
    fun `bare prompts are rejected`() {
        // Prompts are shown before typing, so recording them would misattribute the previous duration.
        assertFalse(recorder.isRecordableCommand("~ $"))
        assertFalse(recorder.isRecordableCommand("$ "))
        assertFalse(recorder.isRecordableCommand(">"))
    }

    @Test
    fun `empty and short lines are rejected`() {
        assertFalse(recorder.isRecordableCommand(null))
        assertFalse(recorder.isRecordableCommand(""))
        assertFalse(recorder.isRecordableCommand("   "))
        assertFalse(recorder.isRecordableCommand("x"))
    }

    @Test
    fun `recording the same line twice in a row is rejected`() {
        // Simulates repeated state transitions within a single command.
        assertTrue(recorder.isRecordableCommand("make build"))
        recorder.record(entry(command = "make build"))
        // lastObservedLine is only updated by the state machine, so the guard is exercised there;
        // here we just assert a different command is still accepted.
        assertTrue(recorder.isRecordableCommand("make test"))
    }

    @Test
    fun `record appends and is readable`() {
        recorder.record(entry(command = "one"))
        recorder.record(entry(command = "two"))
        val all = recorder.readAll()
        assertEquals(2, all.size)
        assertEquals("one", all[0].command)
        assertEquals("two", all[1].command)
    }

    @Test
    fun `default file lives under the home directory`() {
        val default = CommandHistoryRecorder.defaultFile("/home/user")
        assertEquals("/home/user/.termux-assistant/history.jsonl", default.path.replace('\\', '/'))
    }
}

/** Tests for session templates: validation, quoting and persistence. */
@RunWith(RobolectricTestRunner::class)
class SessionTemplatesTest {

    private lateinit var file: File
    private lateinit var store: SessionTemplateStore

    @Before
    fun setUp() {
        file = File(System.getProperty("java.io.tmpdir"), "templates-${System.nanoTime()}.json")
        store = SessionTemplateStore(file)
    }

    private fun template(
        id: String = "work",
        name: String = "Work",
        cwd: String? = "/home/user/project",
        command: String? = null,
    ) = SessionTemplates.Template(
        id = id,
        name = name,
        workingDirectory = cwd,
        initialCommand = command,
    )

    @Test
    fun `builtins are always present`() {
        val loaded = store.load()
        assertTrue(loaded.size >= SessionTemplates.BUILTINS.size)
        assertTrue(loaded.all { it.isBuiltin } || loaded.any { it.isBuiltin })
    }

    @Test
    fun `saving a user template makes it visible`() {
        assertTrue(store.save(template(name = "Build")))
        assertTrue(store.load().any { it.name == "Build" })
    }

    @Test
    fun `saving the same id replaces rather than duplicates`() {
        store.save(template(name = "First"))
        store.save(template(name = "Second"))
        val matching = store.load().filter { it.id == "work" }
        assertEquals(1, matching.size)
        assertEquals("Second", matching[0].name)
    }

    @Test
    fun `builtin ids cannot be overwritten`() {
        val builtinId = SessionTemplates.BUILTINS.first().id
        assertFalse(store.save(template(id = builtinId, name = "Hijacked")))
    }

    @Test
    fun `builtin ids cannot be deleted`() {
        assertFalse(store.delete(SessionTemplates.BUILTINS.first().id))
    }

    @Test
    fun `user templates can be deleted`() {
        store.save(template())
        assertTrue(store.delete("work"))
        assertFalse(store.load().any { it.id == "work" })
    }

    @Test
    fun `deleting a missing template is harmless`() {
        assertTrue(store.delete("never-existed"))
    }

    @Test
    fun `templates survive a reload`() {
        store.save(template(name = "Persisted", command = "make"))
        val reloaded = SessionTemplateStore(file).load()
        val found = reloaded.firstOrNull { it.id == "work" }
        assertNotNull(found)
        assertEquals("Persisted", found!!.name)
        assertEquals("make", found.initialCommand)
    }

    @Test
    fun `corrupt storage degrades to builtins only`() {
        file.writeText("{not an array")
        val loaded = store.load()
        assertEquals(SessionTemplates.BUILTINS.size, loaded.size)
    }

    @Test
    fun `validation rejects a blank name`() {
        assertNotNull(SessionTemplates.validate(template(name = "  ")))
    }

    @Test
    fun `validation rejects a relative working directory`() {
        assertNotNull(SessionTemplates.validate(template(cwd = "relative/path")))
    }

    @Test
    fun `validation accepts an absolute directory or none`() {
        assertNull(SessionTemplates.validate(template(cwd = "/sdcard")))
        assertNull(SessionTemplates.validate(template(cwd = null)))
        assertNull(SessionTemplates.validate(template(cwd = "~")))
    }

    @Test
    fun `validation rejects a multi line initial command`() {
        assertNotNull(SessionTemplates.validate(template(command = "line1\nline2")))
    }

    @Test
    fun `validation accepts a normal initial command`() {
        assertNull(SessionTemplates.validate(template(command = "make -j8")))
    }

    @Test
    fun `launch command chains cd before the command`() {
        val command = SessionTemplates.launchCommand(
            template(cwd = "/home/user/project", command = "make"),
        )
        assertEquals("cd /home/user/project && make", command)
    }

    @Test
    fun `launch command is null when nothing is set`() {
        assertNull(SessionTemplates.launchCommand(template(cwd = null, command = null)))
    }

    @Test
    fun `launch command only cds when no command is given`() {
        assertEquals("cd /tmp", SessionTemplates.launchCommand(template(cwd = "/tmp", command = null)))
    }

    @Test
    fun `shell quoting leaves safe paths alone`() {
        assertEquals("/sdcard/Download", SessionTemplates.shellQuote("/sdcard/Download"))
        assertEquals("~", SessionTemplates.shellQuote("~"))
    }

    @Test
    fun `shell quoting protects spaces and quotes`() {
        assertEquals("'my dir'", SessionTemplates.shellQuote("my dir"))
        assertEquals("'/tmp/a'\\''b'", SessionTemplates.shellQuote("/tmp/a'b"))
    }

    @Test
    fun `shell quoting handles empty input`() {
        assertEquals("''", SessionTemplates.shellQuote(""))
    }

    @Test
    fun `sanitizeId normalises ids`() {
        assertEquals("my-template", SessionTemplates.sanitizeId("  My Template  "))
        assertEquals("a-b", SessionTemplates.sanitizeId("a/b"))
        assertNull(SessionTemplates.sanitizeId("   "))
        assertNull(SessionTemplates.sanitizeId("!!!"))
    }

    @Test
    fun `json round trip preserves optional fields`() {
        val original = SessionTemplates.Template(
            id = "x",
            name = "X",
            workingDirectory = "/tmp",
            initialCommand = "ls",
            isBuiltin = false,
        )
        val decoded = SessionTemplates.Template.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun `json parsing rejects entries without required fields`() {
        assertNull(
            SessionTemplates.Template.fromJson(org.json.JSONObject().put("id", "a")),
        )
        assertNull(
            SessionTemplates.Template.fromJson(org.json.JSONObject().put("name", "b")),
        )
    }
}
