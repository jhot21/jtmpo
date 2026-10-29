package me.jhot.jtmpo.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun shareFileNameStripsPathCharactersEmojiAndLength() {
        assertEquals("Friday Gig.jtmpo", shareFileName("Friday Gig"))
        val slashed = shareFileName("a/b\\c:d*e?f\"g<h>i|j")
        assertEquals("a_b_c_d_e_f_g_h_i_j.jtmpo", slashed)
        val dots = shareFileName("../../etc/passwd")
        assertFalse("/" in dots)
        assertFalse(dots.startsWith("."))
        val emoji = shareFileName("Set 🎵 Night")
        assertTrue(emoji.all { it.code in 32..126 })
        assertTrue(emoji.endsWith(".jtmpo"))
        assertEquals(80 + ".jtmpo".length, shareFileName("x".repeat(300)).length)
        assertEquals("setlist.jtmpo", shareFileName("   "))
        assertEquals("setlist.jtmpo", shareFileName(""))
        assertEquals("setlist.jtmpo", shareFileName(".."))
        assertFalse(shareFileName("tab\there\nnew").any { it.isISOControl() })
    }

    @Test
    fun writeShareFileUsesShareSubdirectory() {
        val cache = tmp.newFolder("cache")
        val file = writeShareFile(cache, "Friday Gig.jtmpo", "{}")
        assertEquals(File(File(cache, "share"), "Friday Gig.jtmpo"), file)
        assertEquals("{}", file.readText())
        assertEquals("[]", writeShareFile(cache, "Friday Gig.jtmpo", "[]").readText())
    }

    @Test
    fun pruneRemovesOldFiles() {
        val cache = tmp.newFolder("cache2")
        writeShareFile(cache, "a.jtmpo", "1")
        writeShareFile(cache, "b.jtmpo", "2")
        pruneShareFiles(cache)
        assertEquals(0, File(cache, "share").listFiles()?.size ?: 0)
        pruneShareFiles(tmp.newFolder("empty")) // no share directory is fine
    }
}
