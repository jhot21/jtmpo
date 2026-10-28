package me.jhot.jtmpo.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun songXml(title: String, tempo: Int) = """<dict><key>title</key><string>$title</string>
    <key>tempo</key><integer>$tempo</integer><key>beatCode</key><integer>1</integer>
    <key>numBeats</key><integer>4</integer><key>numSubBeats</key><integer>2</integer></dict>"""

/**
 * A full export of three setlists, built so the planner has something to merge: 12 distinct titles
 * in 17 entries. "Born to Be Wild" and "Hey Joe" repeat with a different tempo, so they are the two
 * conflicts; the other repeats keep the same rhythm. "Friday Night" is the largest setlist.
 */
private val FIXTURE = listOf(
    "Friday Night" to listOf(
        "Hotel California" to 74, "Sweet Home Alabama" to 98, "Sunshine of Your Love" to 112,
        "Born to Be Wild" to 145, "Hey Joe" to 80, "Bad Moon Rising" to 178, "Gimme Shelter" to 122,
        "Black Dog" to 80,
    ),
    "Rehearsal" to listOf(
        "Hotel California" to 74, "Sweet Home Alabama" to 98, "Born to Be Wild" to 150,
        "Layla" to 104, "Roadhouse Blues" to 112,
    ),
    "Acoustic" to listOf("Hey Joe" to 84, "Layla" to 104, "Wish You Were Here" to 62, "Landslide" to 69),
)

internal fun sampleBytes(): ByteArray =
    FIXTURE.joinToString(prefix = """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><array>""", postfix = "</array></plist>", separator = "") { (name, songs) ->
        "<dict><key>title</key><string>$name</string><key>songs</key><array>" +
            songs.joinToString("") { (title, tempo) -> songXml(title, tempo) } + "</array></dict>"
    }.toByteArray()

class FztParserTest {
    @Test
    fun parsesSampleIntoThreeSetlistsAndSeventeenSongs() {
        val lists = parseTempoExport(sampleBytes()).getOrThrow()
        assertEquals(listOf("Friday Night", "Rehearsal", "Acoustic"), lists.map { it.title })
        assertEquals(17, lists.sumOf { it.songs.size })
        assertTrue(lists.flatMap { it.songs }.all { it.title.isNotBlank() && it.tempo > 0 && it.numBeats > 0 })
    }

    @Test
    fun nonPlistInputFails() {
        assertTrue(parseTempoExport("not xml at all".toByteArray()).isFailure)
        assertTrue(parseTempoExport("<html><body/></html>".toByteArray()).isFailure)
        assertTrue(parseTempoExport(ByteArray(0)).isFailure)
    }

    @Test
    fun emptyArrayParsesToEmptyList() {
        val xml = """<?xml version='1.0' encoding='UTF-8' ?><plist version="1.0"><array></array></plist>"""
        assertEquals(emptyList<FztSetlist>(), parseTempoExport(xml.toByteArray()).getOrThrow())
    }

    @Test
    fun duplicateKeysAreTolerated() {
        val xml = """<plist version="1.0"><array><dict><key>title</key><string>L</string><key>songs</key><array>
            <dict><key>title</key><string>S</string><key>tempo</key><integer>90</integer>
            <key>barTotal</key><integer>0</integer><key>barTotal</key><integer>2</integer>
            <key>beatCode</key><integer>1</integer><key>numBeats</key><integer>4</integer>
            <key>numSubBeats</key><integer>2</integer></dict></array></dict></array></plist>"""
        val song = parseTempoExport(xml.toByteArray()).getOrThrow().single().songs.single()
        assertEquals(FztSong("S", 90, 4, 2, 1), song)
    }

    @Test
    fun songMissingRequiredFieldFails() {
        val xml = """<plist version="1.0"><array><dict><key>title</key><string>L</string><key>songs</key><array>
            <dict><key>title</key><string>S</string></dict></array></dict></array></plist>"""
        assertTrue(parseTempoExport(xml.toByteArray()).isFailure)
    }

    @Test
    fun doctypeIsRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE plist SYSTEM "http://example.com/x.dtd"><plist version="1.0"><array/></plist>"""
        assertTrue(parseTempoExport(xml.toByteArray()).isFailure)
        assertTrue(parseTempoExport(xml.lowercase().toByteArray()).isFailure)
    }

    @Test
    fun entityDeclarationIsRejected() {
        val xml = """<plist version="1.0"><!ENTITY a "b"><array/></plist>"""
        assertTrue(parseTempoExport(xml.toByteArray()).isFailure)
    }

    @Test
    fun singleDictRootParsesToOneSetlist() {
        val xml = slistXml("Gig", songXml("One", 90), songXml("Two", 120))
        val lists = parseTempoExport(xml.toByteArray()).getOrThrow()
        assertEquals(listOf(FztSetlist("Gig", listOf(FztSong("One", 90, 4, 2, 1), FztSong("Two", 120, 4, 2, 1)))), lists)
    }

    @Test
    fun arrayRootStillParsesSeveralSetlists() {
        val set = { name: String -> "<dict><key>title</key><string>$name</string><key>songs</key><array>${songXml("S", 100)}</array></dict>" }
        val xml = """<plist version="1.0"><array>${set("A")}${set("B")}</array></plist>"""
        assertEquals(listOf("A", "B"), parseTempoExport(xml.toByteArray()).getOrThrow().map { it.title })
    }

    @Test
    fun dictRootWithoutSongsFails() {
        val xml = """<plist version="1.0"><dict><key>title</key><string>Gig</string><key>loop</key><false/></dict></plist>"""
        assertTrue(parseTempoExport(xml.toByteArray()).isFailure)
    }

    @Test
    fun rootThatIsNeitherDictNorArrayFails() {
        assertTrue(parseTempoExport("""<plist version="1.0"><string>x</string></plist>""".toByteArray()).isFailure)
        assertTrue(parseTempoExport("""<plist version="1.0"></plist>""".toByteArray()).isFailure)
    }

    @Test
    fun doctypeInASingleSetlistFileIsRejected() {
        val xml = """<?xml version="1.0"?><!DOCTYPE plist SYSTEM "http://example.com/x.dtd">""" +
            slistXml("Gig", songXml("One", 90)).substringAfter("?>")
        assertTrue(parseTempoExport(xml.toByteArray()).isFailure)
    }
}

/** A `.slist`-shaped export: the root is one setlist dict, not an array of them. */
internal fun slistXml(title: String, vararg songs: String): String =
    """<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>title</key><string>$title</string>
    <key>loop</key><false/><key>autoAdvance</key><false/><key>songs</key><array>${songs.joinToString("")}</array></dict></plist>"""
