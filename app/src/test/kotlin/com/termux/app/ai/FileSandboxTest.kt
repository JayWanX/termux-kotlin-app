package com.termux.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Confinement tests for [FileSandbox].
 *
 * A tool that can read and write files is only acceptable if it cannot be walked out of, so these
 * cases concentrate on escapes rather than on the happy path.
 */
@RunWith(RobolectricTestRunner::class)
class FileSandboxConfinementTest {

    private val home: File = File(System.getProperty("java.io.tmpdir"), "sandbox-home")
        .canonicalFile.also { it.mkdirs() }

    private val roots = listOf(home.path)

    private fun resolve(path: String): File = FileSandbox.resolve(path, roots)

    private fun assertRefused(path: String) {
        val refused = try {
            resolve(path)
            false
        } catch (e: FileSandbox.OutsideRootsException) {
            true
        }
        assertTrue("expected `$path` to be refused", refused)
    }

    @Test
    fun `relative path resolves inside home`() {
        val resolved = resolve("notes.txt")
        assertTrue(resolved.path.startsWith(home.path))
    }

    @Test
    fun `absolute path inside home is allowed`() {
        assertNotNull(resolve(File(home, "projects/app").path))
    }

    @Test
    fun `home itself is allowed`() {
        assertEquals(home.path, resolve(home.path).path)
    }

    @Test
    fun `tilde expands to home`() {
        assertEquals(home.path, resolve("~").path)
    }

    @Test
    fun `tilde with subpath expands`() {
        assertTrue(resolve("~/docs").path.startsWith(home.path))
    }

    @Test
    fun `parent traversal is refused`() {
        assertRefused("../../../etc/passwd")
    }

    @Test
    fun `traversal through home is refused`() {
        assertRefused("../outside.txt")
    }

    @Test
    fun `absolute path outside home is refused`() {
        val outside = File(System.getProperty("java.io.tmpdir"), "definitely-outside.txt").canonicalPath
        assertRefused(outside)
    }

    @Test
    fun `sibling sharing a name prefix is refused`() {
        val sibling = home.path + "-evil" + File.separator + "secret"
        assertRefused(sibling)
    }

    @Test
    fun `symlink pointing outside is refused`() {
        val outsideDir = File(System.getProperty("java.io.tmpdir"), "outside-target").apply { mkdirs() }
        val outsideFile = File(outsideDir, "secret.txt").apply { writeText("secret") }
        val link = File(home, "sneaky-link")

        val created = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outsideFile.toPath())
            true
        } catch (e: Exception) {
            // Creating symlinks may be disallowed (e.g. on Windows without privilege).
            false
        }
        if (!created) return

        try {
            assertRefused("sneaky-link")
        } finally {
            link.delete()
            outsideFile.delete()
            outsideDir.delete()
        }
    }

    @Test
    fun `empty path is rejected`() {
        val rejected = try {
            resolve("   ")
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun `multiple roots are all honoured`() {
        val second = File(System.getProperty("java.io.tmpdir"), "sandbox-second")
            .canonicalFile.also { it.mkdirs() }
        val multi = listOf(home.path, second.path)
        assertNotNull(FileSandbox.resolve("a.txt", multi))
        assertNotNull(FileSandbox.resolve(File(second, "b.txt").path, multi))
    }

    @Test
    fun `contains accepts the root itself and its children only`() {
        assertTrue(FileSandbox.contains(home, home))
        assertTrue(FileSandbox.contains(home, File(home, "a/b")))
        assertFalse(FileSandbox.contains(home, File(home.path + "-evil")))
    }

    @Test
    fun `binary detection flags nul bytes`() {
        val binary = File(home, "bin.dat").apply { writeBytes(byteArrayOf(0x1, 0x0, 0x2, 0x3)) }
        try {
            assertTrue(FileSandbox.isBinary(binary))
        } finally {
            binary.delete()
        }
    }

    @Test
    fun `binary detection accepts ordinary text`() {
        val text = File(home, "plain.txt").apply { writeText("hello\nworld\n") }
        try {
            assertFalse(FileSandbox.isBinary(text))
        } finally {
            text.delete()
        }
    }

    @Test
    fun `empty file is not considered binary`() {
        val empty = File(home, "empty.txt").apply { writeText("") }
        try {
            assertFalse(FileSandbox.isBinary(empty))
        } finally {
            empty.delete()
        }
    }

    @Test
    fun `size formatting covers each magnitude`() {
        assertEquals("512 B", FileSandbox.formatSize(512))
        assertEquals("2 KB", FileSandbox.formatSize(2048))
        assertEquals("3 MB", FileSandbox.formatSize(3L * 1024 * 1024))
    }
}

/** Tests for the windowed reader: the point is to avoid loading whole files. */
@RunWith(RobolectricTestRunner::class)
class FileSandboxReadWindowTest {

    private val dir: File = File(System.getProperty("java.io.tmpdir"), "read-window")
        .canonicalFile.also { it.mkdirs() }

    private fun fileWith(vararg lines: String): File {
        val file = File(dir, "sample-${System.nanoTime()}.txt")
        file.writeText(lines.joinToString("\n"))
        return file
    }

    @Test
    fun `reads from the start by default`() {
        val file = fileWith("a", "b", "c")
        try {
            val window = FileSandbox.readWindow(file, offset = 0, limit = 10)
            assertEquals(listOf("a", "b", "c"), window.lines)
            assertEquals(1, window.firstLineNumber)
            assertEquals(3, window.totalLines)
            assertFalse(window.truncated)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `offset skips lines`() {
        val file = fileWith("a", "b", "c", "d")
        try {
            val window = FileSandbox.readWindow(file, offset = 2, limit = 10)
            assertEquals(listOf("c", "d"), window.lines)
            assertEquals(3, window.firstLineNumber)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `limit truncates and reports more remain`() {
        val file = fileWith("a", "b", "c", "d", "e")
        try {
            val window = FileSandbox.readWindow(file, offset = 0, limit = 2)
            assertEquals(listOf("a", "b"), window.lines)
            assertTrue(window.truncated)
            assertEquals(5, window.totalLines)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `offset beyond the end yields no lines`() {
        val file = fileWith("a")
        try {
            val window = FileSandbox.readWindow(file, offset = 99, limit = 5)
            assertTrue(window.lines.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `very long lines are truncated`() {
        val long = "x".repeat(FileSandbox.MAX_LINE_CHARS + 500)
        val file = fileWith(long)
        try {
            val window = FileSandbox.readWindow(file, offset = 0, limit = 5)
            assertEquals(1, window.lines.size)
            assertTrue(window.lines[0].length < long.length)
            assertTrue(window.lines[0].contains("line truncated"))
            assertTrue(window.truncated)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `numbered output aligns line numbers`() {
        val window = FileSandbox.ReadWindow(
            lines = listOf("a", "b"),
            totalLines = 2,
            firstLineNumber = 1,
            truncated = false,
        )
        val formatted = FileSandbox.formatNumbered(window)
        val lines = formatted.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("a"))
        assertTrue(lines[1].contains("b"))
        // The separator makes the number column unambiguous.
        assertTrue(lines[0].contains("│"))
    }

    @Test
    fun `empty window formats to empty string`() {
        val window = FileSandbox.ReadWindow(emptyList(), 0, 1, false)
        assertEquals("", FileSandbox.formatNumbered(window))
    }
}
