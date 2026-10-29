package me.jhot.jtmpo.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun writeShareFileOrNullReportsAnUnwritableCacheAsNull() {
        // A regular file where the cache directory should be: the share subdirectory cannot exist.
        val notADir = tmp.newFile("cache")
        assertNull(writeShareFileOrNull(notADir, "Friday Gig.jtmpo", "{}"))
        val cache = tmp.newFolder("ok")
        assertEquals("{}", writeShareFileOrNull(cache, "Friday Gig.jtmpo", "{}")?.readText())
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
    fun pruneKeepsRecentFilesAndDeletesOldOnes() {
        val cache = tmp.newFolder("cache2")
        val now = 10L * DAY
        val old = writeShareFile(cache, "old.jtmpo", "1").apply { setLastModified(now - DAY) }
        val almost = writeShareFile(cache, "almost.jtmpo", "2").apply { setLastModified(now - DAY + 1) }
        val fresh = writeShareFile(cache, "fresh.jtmpo", "3").apply { setLastModified(now - 1000) }
        pruneShareFiles(cache, nowMillis = now)
        assertFalse(old.exists())
        assertTrue(almost.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun pruneWithMissingDirectoryIsFine() {
        pruneShareFiles(tmp.newFolder("empty"), nowMillis = 10L * DAY)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
