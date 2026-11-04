package me.jhot.jtmpo.practice

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingOutput : ClickOutput {
    val calls = CopyOnWriteArrayList<String>()
    private var listener: (() -> Unit)? = null

    override var onClickStart: (() -> Unit)?
        get() = listener
        set(value) { listener = value }

    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }

    /** One click the way the engine reports it, on the caller's thread. */
    fun click() {
        onClickStart?.invoke()
    }

    fun clicks(n: Int) = repeat(n) { click() }
    fun clear() = calls.clear()
}

/**
 * stop() waits for a click delivered from another thread, so a controller that holds the practice
 * lock across stop() either loses that click or stalls until the wait gives up.
 */
private class BlockingStopOutput : ClickOutput {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile private var listener: (() -> Unit)? = null

    override var onClickStart: (() -> Unit)?
        get() = listener
        set(value) { listener = value }

    override fun start() { calls += "start" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }

    override fun stop() {
        calls += "stop"
        val done = CountDownLatch(1)
        Thread {
            onClickStart?.invoke()
            done.countDown()
        }.apply {
            isDaemon = true
            start()
        }
        done.await(2, TimeUnit.SECONDS)
    }
}

private data class Rig(
    val out: RecordingOutput,
    val playback: PlaybackController,
    val practice: PracticeController,
)

class PracticeControllerTest {
    private fun rhythm(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private fun song(id: Long, tempo: Int) = EffectiveSong(
        entryId = id, songId = id * 10, title = "s$id", artist = null, rhythm = rhythm(tempo),
        notes = null, rhythmOverridden = false, notesOverridden = false,
        libraryRhythm = rhythm(tempo), libraryNotes = null,
    )

    private val a = song(1, 100)
    private val b = song(2, 120)

    private fun rig(
        settings: PracticeSettings = PracticeSettings(),
        entries: List<EffectiveSong> = emptyList(),
    ): Rig {
        val out = RecordingOutput()
        val playback = PlaybackController(out)
        val practice = PracticeController(out, playback, settings)
        playback.onTakeOutput = practice::takeOver
        if (entries.isNotEmpty()) playback.load(1L, entries, 0)
        out.clear()
        return Rig(out, playback, practice)
    }

    private fun rampUp(everyBars: Int = 8) =
        PracticeSettings(direction = RampDirection.Up, stepBpm = 5, everyBars = everyBars)

    private fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun playStartsTheOutputAtTheStartTempo() {
        val (out, _, practice) = rig()

        practice.play()

        assertEquals(listOf("cpm:60", "start"), out.calls)
        assertTrue(practice.state.value.isPlaying)
        assertTrue(practice.state.value.engaged)
    }

    @Test
    fun playStopsASongAndASongStopsPractice() {
        val (out, playback, practice) = rig(entries = listOf(a, b))
        playback.play()
        assertTrue(playback.state.value.isPlaying)

        practice.play()

        assertFalse(playback.state.value.isPlaying)
        assertTrue(practice.state.value.isPlaying)

        playback.play()

        assertTrue(playback.state.value.isPlaying)
        assertFalse(practice.state.value.isPlaying)
        assertFalse(practice.state.value.engaged)
        assertNull(out.onClickStart)

        practice.play()
        assertFalse(playback.state.value.isPlaying)
        out.clear()

        playback.startAudition(rhythm(90))

        assertFalse(practice.state.value.isPlaying)
        assertFalse(practice.state.value.engaged)
        assertNull(out.onClickStart)
    }

    @Test
    fun practiceDoesNotTouchTheLoadedSetlist() {
        val (_, playback, practice) = rig(entries = listOf(a, b))
        val before = playback.state.value

        practice.play()
        practice.pause()

        val after = playback.state.value
        assertEquals(before.setlistId, after.setlistId)
        assertEquals(before.entries, after.entries)
        assertEquals(before.currentIndex, after.currentIndex)
    }

    @Test
    fun firingClicksStepsTheTempoOnTheBarLine() {
        val (out, _, practice) = rig(rampUp(everyBars = 1))
        practice.play()
        out.clear()

        out.clicks(4)

        assertEquals(60, practice.state.value.tempo)
        assertEquals(1, practice.state.value.bar)
        assertTrue(out.calls.isEmpty())

        out.click()

        assertEquals(65, practice.state.value.tempo)
        assertEquals(1, practice.state.value.bar)
        assertEquals(70, practice.state.value.nextTempo)
        assertEquals(listOf("cpm:65"), out.calls)
    }

    @Test
    fun pauseFreezesAndResumeKeepsTheBar() {
        val (out, _, practice) = rig(rampUp())
        practice.play()
        out.clicks(6)
        assertEquals(2, practice.state.value.bar)
        out.clear()

        practice.pause()

        assertFalse(practice.state.value.isPlaying)
        assertEquals(2, practice.state.value.bar)
        assertEquals(60, practice.state.value.tempo)
        assertEquals(listOf("stop"), out.calls)

        practice.play()
        assertEquals(2, practice.state.value.bar)

        out.clicks(4)
        assertEquals(2, practice.state.value.bar)
        out.click()
        assertEquals(3, practice.state.value.bar)
    }

    @Test
    fun resetWhilePlayingRestartsAtTheStartTempo() {
        val (out, _, practice) = rig(rampUp(everyBars = 1))
        practice.play()
        out.clicks(5)
        assertEquals(65, practice.state.value.tempo)
        out.clear()

        practice.reset()

        assertEquals(listOf("stop", "cpm:60", "start"), out.calls)
        assertEquals(60, practice.state.value.tempo)
        assertEquals(1, practice.state.value.bar)
        assertTrue(practice.state.value.isPlaying)
    }

    @Test
    fun resetWhilePausedJustResets() {
        val (out, _, practice) = rig(rampUp(everyBars = 1))
        practice.play()
        out.clicks(5)
        practice.pause()
        out.clear()

        practice.reset()

        assertTrue(out.calls.isEmpty())
        assertEquals(60, practice.state.value.tempo)
        assertEquals(1, practice.state.value.bar)
        assertFalse(practice.state.value.isPlaying)
    }

    @Test
    fun nudgeAndStartTempoFollowTheSpecRules() {
        val (out, _, practice) = rig(rampUp())

        practice.nudgeTempo(90)
        assertEquals(90, practice.state.value.tempo)
        assertEquals(90, practice.state.value.settings.startTempo)
        assertTrue(out.calls.isEmpty())

        practice.play()
        assertEquals(listOf("cpm:90", "start"), out.calls)
        out.clear()

        practice.nudgeTempo(100)
        assertEquals(100, practice.state.value.tempo)
        assertEquals(90, practice.state.value.settings.startTempo)
        assertEquals(listOf("cpm:100"), out.calls)
        out.clear()

        practice.setStartTempo(80)
        assertEquals(80, practice.state.value.settings.startTempo)
        assertEquals(100, practice.state.value.tempo)
        assertTrue(out.calls.isEmpty())

        practice.pause()
        out.clear()
        practice.nudgeTempo(95)
        assertEquals(95, practice.state.value.tempo)
        assertEquals(80, practice.state.value.settings.startTempo)
        assertTrue(out.calls.isEmpty())

        practice.reset()
        assertEquals(80, practice.state.value.tempo)
        assertEquals(1, practice.state.value.bar)
    }

    @Test
    fun changingTheSubdivisionWhilePlayingUpdatesTheRate() {
        val (out, _, practice) = rig()
        practice.play()
        out.clear()

        practice.update { it.copy(pattern = ClickPattern(8)) }

        assertEquals(listOf("cpm:120"), out.calls)
        assertEquals(ClickPattern(8), practice.state.value.settings.pattern)
        assertEquals(1, practice.state.value.bar)
    }

    @Test
    fun concurrentNudgesAndClicksDoNotDeadlockOrLoseTheLastWrite() {
        val (out, _, practice) = rig(rampUp(everyBars = 64))
        practice.play()

        val clicking = Thread { repeat(10_000) { out.click() } }
        clicking.isDaemon = true
        clicking.start()
        repeat(1_000) { practice.nudgeTempo(100) }
        clicking.join(5_000)

        assertFalse("the click thread did not finish", clicking.isAlive)
        val tempo = practice.state.value.tempo
        assertTrue(
            "tempo $tempo is neither the last nudge nor a later step from it",
            tempo >= 100 && (tempo - 100) % 5 == 0,
        )
    }

    @Test
    fun selectingASongWhilePracticePlaysDoesNotChangeTheRate() {
        val (out, playback, practice) = rig(entries = listOf(a, b))
        practice.play()
        out.clear()

        playback.select(1)
        playback.preview(rhythm(200))

        assertEquals(1, playback.state.value.currentIndex)
        assertTrue(out.calls.isEmpty())
        assertTrue(practice.state.value.isPlaying)
    }

    @Test
    fun takeOverClearsEngagedAndTheLoan() {
        val (out, playback, practice) = rig()
        practice.play()
        out.clear()

        practice.takeOver()

        assertFalse(practice.state.value.engaged)
        assertFalse(practice.state.value.isPlaying)
        assertNull(out.onClickStart)
        assertFalse(playback.outputLent)
        assertEquals(listOf("stop"), out.calls)
    }

    @Test
    fun restoreIsIgnoredOnceThePlayerHasActed() {
        val (out, _, practice) = rig()
        practice.play()
        out.clear()

        practice.restore(PracticeSettings(startTempo = 120))

        assertEquals(60, practice.state.value.settings.startTempo)
        assertTrue(out.calls.isEmpty())

        val fresh = rig(PracticeSettings(startTempo = 40))
        fresh.practice.restore(PracticeSettings(startTempo = 120, direction = RampDirection.Up))

        assertEquals(
            PracticeSettings(startTempo = 120, direction = RampDirection.Up),
            fresh.practice.state.value.settings,
        )
        assertEquals(120, fresh.practice.state.value.tempo)
        assertFalse(fresh.practice.state.value.engaged)
    }

    @Test
    fun pauseAndResetDoNotHoldTheLockWhileTouchingTheOutput() {
        val out = BlockingStopOutput()
        val playback = PlaybackController(out)
        val practice = PracticeController(out, playback)
        practice.play()

        val paused = millis { practice.pause() }
        assertTrue("pause took ${paused}ms", paused < 2_000)

        practice.play()
        val reset = millis { practice.reset() }
        assertTrue("reset took ${reset}ms", reset < 2_000)
    }
}
