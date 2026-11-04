package me.jhot.jtmpo.practice

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class PracticeSettingsTest {
    private val ts44 = TimeSignature(4, 4)
    private val ts68 = TimeSignature(6, 8)
    private val ts78 = TimeSignature(7, 8)
    private val ts54 = TimeSignature(5, 4)
    private val ts128 = TimeSignature(12, 8)

    private fun p(n: Int, dotted: Boolean = false) = ClickPattern(n, dotted)

    @Test
    fun clicksPerBarCountsClicksInOneBar() {
        assertEquals(4, clicksPerBar(ts44, p(4)))
        assertEquals(8, clicksPerBar(ts44, p(8)))
        assertEquals(6, clicksPerBar(ts68, p(8)))
        assertEquals(2, clicksPerBar(ts68, p(4, dotted = true)))
        assertEquals(14, clicksPerBar(ts78, p(16)))
        assertEquals(4, clicksPerBar(ts128, p(4, dotted = true)))
        assertEquals(20, clicksPerBar(ts54, p(16)))
        assertEquals(1, clicksPerBar(TimeSignature(1, 4), p(4)))
    }

    @Test
    fun maxPracticeTempoStopsWhereTheClickRateHitsTheCap() {
        assertEquals(300, maxPracticeTempo(ts44, p(4)))
        assertEquals(150, maxPracticeTempo(ts44, p(16)))
        assertEquals(100, maxPracticeTempo(ts68, p(16)))
    }

    @Test
    fun sanitizedClampsEverythingIntoRange() {
        val low = PracticeSettings(startTempo = -5, stepBpm = 0, everyBars = 0).sanitized()
        assertEquals(20, low.startTempo)
        assertEquals(1, low.stepBpm)
        assertEquals(1, low.everyBars)

        val high = PracticeSettings(startTempo = 1000, stepBpm = 99, everyBars = 500).sanitized()
        assertEquals(300, high.startTempo)
        assertEquals(50, high.stepBpm)
        assertEquals(64, high.everyBars)
    }

    @Test
    fun sanitizedFallsBackForAnUnsupportedSignatureOrPattern() {
        val badSignature = PracticeSettings(timeSignature = TimeSignature(4, 3)).sanitized()
        assertEquals(ts44, badSignature.timeSignature)
        assertEquals(p(4), badSignature.pattern)

        val badPattern = PracticeSettings(pattern = p(2)).sanitized()
        assertEquals(ts44, badPattern.timeSignature)
        assertEquals(p(4), badPattern.pattern)
    }

    @Test
    fun sanitizedClampsTheStartTempoToTheClickRateCap() {
        val settings = PracticeSettings(startTempo = 200, timeSignature = ts44, pattern = p(16)).sanitized()
        assertEquals(150, settings.startTempo)
    }
}
