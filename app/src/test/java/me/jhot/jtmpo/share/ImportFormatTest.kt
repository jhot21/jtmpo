package me.jhot.jtmpo.share

import kotlin.random.Random
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.importer.sampleBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportFormatTest {
    private val rhythm = Rhythm(100, TimeSignature(4, 4), ClickPattern(4))
    private val jtmpo = SetlistFileCodec.encode(
        SharedSetlist(
            "Friday",
            listOf(SharedSong(1, "Blue Harbor", null, rhythm, null)),
            listOf(SharedEntry(1, rhythm, null)),
        ),
    ).toByteArray()

    @Test
    fun xmlPlistIsTempo() {
        assertEquals(ImportFormat.TEMPO, detectFormat(sampleBytes()))
    }

    @Test
    fun plistWithoutXmlDeclarationIsTempo() {
        assertEquals(ImportFormat.TEMPO, detectFormat("""  <plist version="1.0"><array/></plist>""".toByteArray()))
    }

    @Test
    fun setlistJsonIsJtmpo() {
        assertEquals(ImportFormat.JTMPO, detectFormat(jtmpo))
        // A byte-order mark and leading whitespace are still a setlist.
        assertEquals(ImportFormat.JTMPO, detectFormat(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\n ".toByteArray() + jtmpo))
    }

    @Test
    fun truncatedSetlistJsonIsStillJtmpo() {
        // So the user hears "could not be read as a jtmpo setlist", not "not a setlist file".
        assertEquals(ImportFormat.JTMPO, detectFormat(jtmpo.copyOf(jtmpo.size / 2)))
    }

    @Test
    fun backupJsonIsBackup() {
        assertEquals(ImportFormat.BACKUP, detectFormat("""{"version":1,"songs":[],"setlists":[],"entries":[]}""".toByteArray()))
    }

    @Test
    fun otherJsonAndXmlAreUnknown() {
        assertEquals(ImportFormat.UNKNOWN, detectFormat("""{"type":"something-else","setlists":[]}""".toByteArray()))
        assertEquals(ImportFormat.UNKNOWN, detectFormat("""{"setlists":{}}""".toByteArray()))
        assertEquals(ImportFormat.UNKNOWN, detectFormat("""[1,2,3]""".toByteArray()))
        assertEquals(ImportFormat.UNKNOWN, detectFormat("""<?xml version="1.0"?><html></html>""".toByteArray()))
        assertEquals(ImportFormat.UNKNOWN, detectFormat("hello".toByteArray()))
    }

    @Test
    fun emptyInputIsUnknown() {
        assertEquals(ImportFormat.UNKNOWN, detectFormat(ByteArray(0)))
        assertEquals(ImportFormat.UNKNOWN, detectFormat("   \n".toByteArray()))
    }

    @Test
    fun binaryInputIsUnknown() {
        val random = Random(42)
        repeat(50) { assertEquals(ImportFormat.UNKNOWN, detectFormat(random.nextBytes(1024))) }
        assertEquals(ImportFormat.UNKNOWN, detectFormat(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0)))
    }

    @Test
    fun oversizedInputIsUnknownWithoutScanningIt() {
        val big = ByteArray(6 * 1024 * 1024) { ' '.code.toByte() }
        big[big.size - 1] = '{'.code.toByte()
        val start = System.nanoTime()
        assertEquals(ImportFormat.UNKNOWN, detectFormat(big))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 200)
    }
}
