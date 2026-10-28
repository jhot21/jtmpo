package me.jhot.jtmpo.importer

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.patternsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FztMapperTest {
    private fun song(tempo: Int = 100, beats: Int, sub: Int, beatCode: Int = 1) = FztSong("t", tempo, beats, sub, beatCode)

    @Test
    fun fourFourWithSubdivisionIsEighth() =
        assertEquals(Rhythm(100, TimeSignature(4, 4), ClickPattern(8)), mapFztSong(song(beats = 4, sub = 2)))

    @Test
    fun fourFourWithoutSubdivisionIsQuarter() =
        assertEquals(Rhythm(100, TimeSignature(4, 4), ClickPattern(4)), mapFztSong(song(beats = 4, sub = 1)))

    @Test
    fun threeFourWithSubdivisionIsEighth() =
        assertEquals(Rhythm(100, TimeSignature(3, 4), ClickPattern(8)), mapFztSong(song(beats = 3, sub = 2)))

    @Test
    fun sixEightSubdividedClicksEighths() =
        assertEquals(
            Rhythm(100, TimeSignature(6, 8), ClickPattern(8)),
            mapFztSong(song(beats = 6, sub = 1, beatCode = 1)),
        )

    @Test
    fun sixEightUnsubdividedClicksDottedQuarters() =
        assertEquals(
            Rhythm(133, TimeSignature(6, 8), ClickPattern(4, dotted = true)),
            mapFztSong(song(133, beats = 6, sub = 1, beatCode = 0)),
        )

    @Test
    fun fiveFourWithSubdivisionIsEighth() =
        assertEquals(Rhythm(100, TimeSignature(5, 4), ClickPattern(8)), mapFztSong(song(beats = 5, sub = 2)))

    @Test
    fun tempoIsNeverAltered() {
        for (tempo in listOf(35, 53, 75, 158)) for ((b, s) in listOf(4 to 2, 4 to 1, 6 to 1, 3 to 2)) {
            assertEquals(tempo, mapFztSong(song(tempo, b, s)).tempo)
        }
    }

    @Test
    fun mappedValuesAreAlwaysValidForPatternsFor() {
        for (beats in -1..16) for (sub in -1..5) {
            val rhythm = mapFztSong(song(beats = beats, sub = sub))
            assertTrue(rhythm.timeSignature.numerator >= 1)
            assertTrue("$rhythm", rhythm.pattern in patternsFor(rhythm.timeSignature))
        }
    }

    @Test
    fun outOfRangeTempoIsClampedToLimits() {
        // Two different out-of-range tempos can clamp to the same value and then no longer conflict; that is fine.
        val expected = mapOf(0 to 20, -40 to 20, 5 to 20, 20 to 20, 300 to 300, 9999 to 300)
        for ((raw, clamped) in expected) assertEquals(raw.toString(), clamped, mapFztSong(song(raw, 4, 2)).tempo)
        assertEquals(300, mapFztSong(song(9999, 6, 1)).tempo)
    }

    @Test
    fun sixEightTempoIsCopiedUnchangedForBothBeatCodes() {
        assertEquals(53, mapFztSong(song(53, 6, 1, beatCode = 1)).tempo)
        assertEquals(120, mapFztSong(song(120, 6, 1, beatCode = 0)).tempo)
    }
}
