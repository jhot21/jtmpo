package me.jhot.jtmpo.practice

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeRampTest {
    private fun p(n: Int, dotted: Boolean = false) = ClickPattern(n, dotted)

    private fun ramp(
        settings: PracticeSettings = PracticeSettings(direction = RampDirection.Up),
        play: Boolean = true,
    ): PracticeRamp = PracticeRamp(settings).also { if (play) it.play() }

    private fun PracticeRamp.clicks(n: Int) {
        repeat(n) { onClick() }
    }

    @Test
    fun startsAtTheStartTempo() {
        val ramp = ramp(play = false)

        assertEquals(60, ramp.tempo)
        assertEquals(1, ramp.bar)
        assertEquals(65, ramp.nextTempo)
        assertFalse(ramp.begun)
    }

    @Test
    fun barAdvancesOnTheDownbeat() {
        val ramp = ramp()

        ramp.clicks(1)
        assertEquals(1, ramp.bar)

        ramp.clicks(4)
        assertEquals(2, ramp.bar)
    }

    @Test
    fun stepsOnTheDownbeatAfterTheInterval() {
        val ramp = ramp()
        ramp.clicks(32)
        assertEquals(60, ramp.tempo)
        assertEquals(8, ramp.bar)

        val stepped = ramp.onClick()
        assertTrue(stepped)
        assertEquals(65, ramp.tempo)
        assertEquals(1, ramp.bar)
        assertEquals(70, ramp.nextTempo)
    }

    @Test
    fun rampsDown() {
        val ramp = ramp(PracticeSettings(direction = RampDirection.Down))

        ramp.clicks(33)
        assertEquals(55, ramp.tempo)
        assertEquals(1, ramp.bar)
    }

    @Test
    fun clampsAtTheAppMaximumAndStaysThere() {
        val ramp = ramp(PracticeSettings(startTempo = 298, direction = RampDirection.Up))

        ramp.clicks(33)
        assertEquals(300, ramp.tempo)
        assertNull(ramp.nextTempo)

        val bars = mutableSetOf<Int>()
        repeat(100) {
            ramp.onClick()
            bars += ramp.bar
        }
        assertEquals(300, ramp.tempo)
        assertEquals((1..8).toSet(), bars)
    }

    @Test
    fun clampsAtTheAppMinimum() {
        val ramp = ramp(PracticeSettings(startTempo = 22, direction = RampDirection.Down))

        ramp.clicks(33)
        assertEquals(20, ramp.tempo)
        assertNull(ramp.nextTempo)
    }

    @Test
    fun stopsWhereTheClickRateCaps() {
        val ramp = ramp(
            PracticeSettings(startTempo = 148, pattern = p(16), direction = RampDirection.Up),
        )

        ramp.clicks(128)
        assertEquals(148, ramp.tempo)
        assertEquals(8, ramp.bar)

        ramp.clicks(1)
        assertEquals(150, ramp.tempo)
        assertNull(ramp.nextTempo)
    }

    @Test
    fun startAndCurrentTempoClampToTheCap() {
        val ramp = ramp(PracticeSettings(startTempo = 200, direction = RampDirection.Up), play = false)
        assertEquals(200, ramp.tempo)

        ramp.apply(ramp.settings.copy(pattern = p(16)))
        assertEquals(150, ramp.tempo)
        assertEquals(150, ramp.settings.startTempo)

        ramp.nudge(250)
        assertEquals(150, ramp.tempo)
    }

    @Test
    fun offNeverChangesAnything() {
        val ramp = ramp(PracticeSettings(direction = RampDirection.Off))

        repeat(100) { assertFalse(ramp.onClick()) }
        assertEquals(60, ramp.tempo)
        assertNull(ramp.nextTempo)
    }

    @Test
    fun onClickReportsChanges() {
        val ramp = ramp()

        repeat(4) { assertFalse(ramp.onClick()) }
        assertTrue(ramp.onClick())
        assertEquals(2, ramp.bar)
        assertFalse(ramp.onClick())
    }

    @Test
    fun resumeKeepsTheBar() {
        val ramp = ramp()
        ramp.clicks(6)
        assertEquals(2, ramp.bar)

        ramp.play()
        ramp.clicks(1)
        assertEquals(2, ramp.bar)

        ramp.clicks(4)
        assertEquals(3, ramp.bar)
    }

    @Test
    fun resetReturnsToTheStartTempo() {
        val ramp = ramp()
        ramp.clicks(33)
        assertEquals(65, ramp.tempo)

        ramp.reset()
        assertEquals(60, ramp.tempo)
        assertEquals(1, ramp.bar)
        assertFalse(ramp.begun)

        ramp.clicks(1)
        assertEquals(1, ramp.bar)
    }

    @Test
    fun changingTheSignatureOrPatternRestartsTheBarCount() {
        val ramp = ramp()
        ramp.clicks(6)
        assertEquals(2, ramp.bar)

        ramp.apply(ramp.settings.copy(pattern = p(8)))
        assertEquals(1, ramp.bar)
        assertEquals(60, ramp.tempo)
        ramp.clicks(1)
        assertEquals(1, ramp.bar)

        ramp.clicks(8)
        assertEquals(2, ramp.bar)
        ramp.apply(ramp.settings.copy(timeSignature = TimeSignature(3, 4)))
        assertEquals(1, ramp.bar)
        assertEquals(60, ramp.tempo)
        ramp.clicks(1)
        assertEquals(1, ramp.bar)
    }

    @Test
    fun changingStepOrEveryKeepsTheBarAndAppliesNextBar() {
        val ramp = ramp()
        ramp.clicks(6)
        assertEquals(2, ramp.bar)

        ramp.apply(ramp.settings.copy(stepBpm = 10))
        assertEquals(2, ramp.bar)

        ramp.clicks(27)
        assertEquals(70, ramp.tempo)
        assertEquals(1, ramp.bar)

        ramp.clicks(15)
        assertEquals(4, ramp.bar)
        ramp.apply(ramp.settings.copy(everyBars = 3))
        assertEquals(4, ramp.bar)

        ramp.clicks(1)
        assertEquals(1, ramp.bar)
        assertEquals(80, ramp.tempo)
    }

    @Test
    fun nudgeAfterPlayingChangesOnlyTheCurrentTempo() {
        val ramp = ramp()
        ramp.clicks(6)
        val bar = ramp.bar
        assertTrue(ramp.begun)

        ramp.nudge(77)
        assertEquals(77, ramp.tempo)
        assertEquals(60, ramp.settings.startTempo)
        assertEquals(bar, ramp.bar)

        ramp.clicks(27)
        assertEquals(82, ramp.tempo)
    }

    @Test
    fun nudgeBeforePlayingOrWithRampOffChangesTheStartTempo() {
        val fresh = ramp(play = false)
        fresh.nudge(90)
        assertEquals(90, fresh.settings.startTempo)
        assertEquals(90, fresh.tempo)

        val off = ramp(PracticeSettings(direction = RampDirection.Off))
        off.nudge(90)
        assertEquals(90, off.settings.startTempo)
        assertEquals(90, off.tempo)

        off.nudge(TempoLimits.MAX + 100)
        assertEquals(TempoLimits.MAX, off.settings.startTempo)
        off.nudge(1)
        assertEquals(TempoLimits.MIN, off.settings.startTempo)
    }

    @Test
    fun setStartTempoMidRampLeavesTheCurrentTempo() {
        val ramp = ramp()
        ramp.clicks(6)

        ramp.setStartTempo(100)
        assertEquals(60, ramp.tempo)
        assertEquals(100, ramp.settings.startTempo)

        ramp.reset()
        assertEquals(100, ramp.tempo)
    }

    @Test
    fun oneClickPerBarStillSteps() {
        val ramp = ramp(
            PracticeSettings(
                timeSignature = TimeSignature(1, 4),
                pattern = p(4),
                direction = RampDirection.Up,
                everyBars = 2,
            ),
        )

        ramp.clicks(2)
        assertEquals(60, ramp.tempo)
        assertEquals(2, ramp.bar)

        ramp.clicks(1)
        assertEquals(65, ramp.tempo)
        assertEquals(1, ramp.bar)
    }
}
