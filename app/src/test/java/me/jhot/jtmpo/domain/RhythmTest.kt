package me.jhot.jtmpo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmTest {
    private val ts44 = TimeSignature(4, 4)
    private val ts34 = TimeSignature(3, 4)
    private val ts68 = TimeSignature(6, 8)
    private val ts78 = TimeSignature(7, 8)
    private val ts22 = TimeSignature(2, 2)
    private val ts1216 = TimeSignature(12, 16)

    private fun p(n: Int, dotted: Boolean = false) = ClickPattern(n, dotted)

    @Test
    fun quarterIn44Is120Cpm() {
        assertEquals(120.0, Rhythm(120, ts44, p(4)).clicksPerMinute(), 1e-9)
        assertEquals(240.0, Rhythm(120, ts44, p(8)).clicksPerMinute(), 1e-9)
        assertEquals(480.0, Rhythm(120, ts44, p(16)).clicksPerMinute(), 1e-9)
    }

    @Test
    fun tempoInCompoundTimeCountsTheDottedBeat() {
        // Abide: 44 dotted quarters/min is 132 eighth clicks/min, 44 dotted-quarter clicks/min.
        assertEquals(132.0, Rhythm(44, ts68, p(8)).clicksPerMinute(), 1e-9)
        assertEquals(44.0, Rhythm(44, ts68, p(4, true)).clicksPerMinute(), 1e-9)
        assertEquals(264.0, Rhythm(44, ts68, p(16)).clicksPerMinute(), 1e-9)
        assertEquals(132.0, Rhythm(44, TimeSignature(9, 8), p(8)).clicksPerMinute(), 1e-9)
        assertEquals(44.0, Rhythm(44, TimeSignature(12, 8), p(4, true)).clicksPerMinute(), 1e-9)
        assertEquals(132.0, Rhythm(44, ts1216, p(16)).clicksPerMinute(), 1e-9)
        assertEquals(44.0, Rhythm(44, ts1216, p(8, true)).clicksPerMinute(), 1e-9)
    }

    @Test
    fun otherSignaturesKeepTheBottomNoteAsTheUnit() {
        assertEquals(74.0, Rhythm(74, ts78, p(8)).clicksPerMinute(), 1e-9)
        assertEquals(74.0, Rhythm(74, TimeSignature(5, 8), p(8)).clicksPerMinute(), 1e-9)
        assertEquals(74.0, Rhythm(74, TimeSignature(3, 8), p(8)).clicksPerMinute(), 1e-9)
        assertEquals(74.0, Rhythm(74, ts22, p(2)).clicksPerMinute(), 1e-9)
    }

    @Test
    fun tempoUnitPerSignature() {
        assertEquals(p(4, true), ts68.tempoUnit)
        assertEquals(p(4, true), TimeSignature(9, 8).tempoUnit)
        assertEquals(p(4, true), TimeSignature(12, 8).tempoUnit)
        assertEquals(p(8, true), ts1216.tempoUnit)
        assertEquals(p(4), ts44.tempoUnit)
        assertEquals(p(8), ts78.tempoUnit)
        assertEquals(p(8), TimeSignature(3, 8).tempoUnit)
        assertEquals(p(2), ts22.tempoUnit)
    }

    @Test
    fun changingPatternNeverChangesTempo() {
        val r = Rhythm(74, ts68, p(8))
        assertEquals(74, r.copy(pattern = p(4, true)).tempo)
    }

    @Test
    fun cpmIsCappedAt600() {
        assertEquals(600.0, Rhythm(300, ts44, p(16)).clicksPerMinute(), 1e-9)
    }

    @Test
    fun timeSignatureToString() {
        assertEquals("6/8", ts68.toString())
    }

    @Test
    fun patternsFor44() {
        assertEquals(listOf(p(4), p(8), p(16)), patternsFor(ts44))
    }

    @Test
    fun patternsFor34() {
        assertEquals(listOf(p(4), p(8), p(16)), patternsFor(ts34))
    }

    @Test
    fun patternsFor68() {
        assertEquals(listOf(p(8), p(4, true), p(16)), patternsFor(ts68))
    }

    @Test
    fun patternsFor78() {
        assertEquals(listOf(p(8), p(16)), patternsFor(ts78))
    }

    @Test
    fun patternsFor22() {
        assertEquals(listOf(p(2), p(4), p(8)), patternsFor(ts22))
    }

    @Test
    fun patternsFor1216OffersSixteenthAndDottedEighth() {
        assertEquals(listOf(p(16), p(8, true)), patternsFor(ts1216))
        assertEquals(listOf(p(16)), patternsFor(TimeSignature(7, 16)))
    }

    @Test
    fun commonSignatures() {
        assertEquals(
            listOf("4/4", "3/4", "6/8", "5/4", "2/4", "12/8", "7/8"),
            COMMON_SIGNATURES.map { it.toString() },
        )
    }

    @Test
    fun remapPatternExamples() {
        assertEquals(p(8), remapPattern(p(8), ts44, ts68))
        assertEquals(p(4), remapPattern(p(4, true), ts68, ts44))
        assertEquals(p(16), remapPattern(p(16), ts68, ts44))
    }

    @Test
    fun remapResultAlwaysInPatternsForTarget() {
        for (from in COMMON_SIGNATURES + ts22 + ts1216) {
            for (to in COMMON_SIGNATURES + ts22 + ts1216) {
                for (old in patternsFor(from)) {
                    assertTrue("$old $from->$to", remapPattern(old, from, to) in patternsFor(to))
                }
            }
        }
    }
}
