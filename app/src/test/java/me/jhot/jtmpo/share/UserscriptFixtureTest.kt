package me.jhot.jtmpo.share

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `scripts/fixtures/sample.jtmpo` is what `scripts/pco-slist.user.js` writes for a fixed made-up plan
 * (`scripts/pco-slist.test.js` asserts that byte for byte). Decoding it here with the app's own codec
 * keeps the userscript and the app agreeing on the format.
 */
class UserscriptFixtureTest {
    // Gradle runs unit tests from the module directory, so the scripts folder is one level up.
    private val decoded = SetlistFileCodec.decode(File("../scripts/fixtures/sample.jtmpo").readText()).getOrThrow()

    private fun rhythm(tempo: Int, num: Int, den: Int, patDen: Int, dotted: Boolean = false) =
        Rhythm(tempo, TimeSignature(num, den), ClickPattern(patDen, dotted))

    @Test
    fun theAppReadsTheSetlistNameAndSongs() {
        assertEquals("Sample Set", decoded.name)
        assertEquals(
            listOf("Example Hymn", "Example Waltz", "Example Slow", "No Meter Song"),
            decoded.songs.map { it.title },
        )
        assertEquals(listOf("A. Writer", "B. Writer and C. Writer", null, null), decoded.songs.map { it.artist })
    }

    @Test
    fun rhythmsAreInTheAppsOwnUnits() {
        assertEquals(rhythm(90, 3, 4, 4), decoded.songs[0].rhythm)
        // Compound 6/8: tempo counts dotted quarters, so 156 eighths per minute is 52.
        assertEquals(rhythm(52, 6, 8, 4, dotted = true), decoded.songs[1].rhythm)
        assertEquals(rhythm(60, 6, 4, 4), decoded.songs[2].rhythm)
        assertEquals(rhythm(100, 4, 4, 4), decoded.songs[3].rhythm)
    }

    @Test
    fun aSongUsedTwiceIsOneSongWithTwoEntriesAtTheirOwnTempos() {
        assertEquals(5, decoded.entries.size)
        val hymn = decoded.songs[0].localId
        val hymnEntries = decoded.entries.filter { it.songId == hymn }
        assertEquals(listOf(90, 84), hymnEntries.map { it.rhythm.tempo })
        assertEquals(listOf("Key: D", "Key: E"), hymnEntries.map { it.notes })
    }

    @Test
    fun theFixtureImportsAsNewSongsIntoAnEmptyLibrary() {
        val plan = planSharedImport(decoded, emptyList())
        assertEquals(decoded.songs.size, plan.newCount)
        assertEquals(decoded.entries.size, plan.entryCount)
    }
}
