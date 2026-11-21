package com.termux.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for `~` expansion, written so they fail on any host when the rule regresses.
 *
 * The bug these cover was invisible on Windows. The old rule was:
 *
 * ```
 * expanded  = path.removePrefix("~")              // "~/docs" -> "/docs"
 * candidate = if (File(expanded).isAbsolute) ...  // POSIX: true   Windows: false
 * ```
 *
 * On POSIX `/docs` is absolute, so `~/docs` resolved to the filesystem root and was refused as outside
 * the sandbox. On Windows the same string is *not* absolute, so it was joined to home and worked. A
 * test written only against [FileSandbox.resolve] therefore passed on a Windows development machine
 * and failed in Linux CI, which is the worst possible arrangement.
 *
 * [FileSandbox.expand] takes the platform filesystem out of the question: it decides on the string, so
 * asserting its output pins the rule identically everywhere.
 */
class TildeExpansionTest {

    private val home = File(System.getProperty("java.io.tmpdir"), "tilde-home").canonicalFile

    @Test
    fun `tilde alone is the home directory`() {
        assertEquals(home, FileSandbox.expand("~", home))
    }

    @Test
    fun `tilde slash is the home directory`() {
        assertEquals(home, FileSandbox.expand("~/", home))
    }

    @Test
    fun `the old strip-then-reparse rule disagrees with the correct one`() {
        // The regression in executable form, and deliberately host-independent.
        //
        // The old rule turned `~/docs` into the string `/docs` and then asked whether that was
        // absolute. On POSIX it is, so it escaped home; on Windows it is not, so it happened to agree.
        // Rather than branch on the host, this asserts the *rule* the fix encodes — `~/x` is always a
        // sub-path of home — which the old rule violates on the platform that matters.
        val homePath = home.path
        assertEquals(
            "`~/docs` must be home/docs, not a re-interpreted absolute path",
            File(home, "docs").path,
            FileSandbox.expand("~/docs", home).path,
        )

        // And explicitly: the expanded form must not be the bare stripped string on any host.
        val stripped = "~/docs".removePrefix("~")
        assertFalse(
            "`~/docs` must not expand to `$stripped`",
            FileSandbox.expand("~/docs", home).path == stripped,
        )
        // Sanity: the expectation above is anchored to the real home path.
        assertTrue(FileSandbox.expand("~/docs", home).path.startsWith(homePath))
    }

    @Test
    fun `tilde subpath joins onto home`() {
        // This is the assertion that fails under the old rule on POSIX hosts.
        assertEquals(File(home, "docs"), FileSandbox.expand("~/docs", home))
    }

    @Test
    fun `tilde subpath keeps every component`() {
        assertEquals(
            File(home, "a/b/c.txt"),
            FileSandbox.expand("~/a/b/c.txt", home),
        )
    }

    @Test
    fun `tilde subpath never becomes absolute`() {
        // The heart of the bug: whatever the resolved string looks like, it must stay under home.
        listOf("~/a", "~/a/b", "~/a/b/c.txt", "~/", "~/a/../b").forEach { path ->
            val expanded = FileSandbox.expand(path, home).path
            assertTrue(
                "`$path` expanded to `$expanded`, outside `$home`",
                expanded == home.path || expanded.startsWith(home.path + File.separator),
            )
        }
    }

    @Test
    fun `tilde subpath matches the relative spelling`() {
        assertEquals(
            FileSandbox.expand("docs/notes.txt", home),
            FileSandbox.expand("~/docs/notes.txt", home),
        )
    }

    @Test
    fun `an absolute path is left alone`() {
        val absolute = File(home, "already/absolute.txt").path
        assertTrue(File(absolute).isAbsolute)
        assertEquals(File(absolute), FileSandbox.expand(absolute, home))
    }

    @Test
    fun `a plain relative path joins onto home`() {
        assertEquals(File(home, "notes.txt"), FileSandbox.expand("notes.txt", home))
    }
}
