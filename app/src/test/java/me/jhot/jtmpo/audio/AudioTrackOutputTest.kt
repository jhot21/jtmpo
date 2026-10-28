package me.jhot.jtmpo.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackOutputTest {
    private val rate = 48000

    /** Polls [s] at each playhead and returns the playheads at which a pulse was emitted. */
    private fun pulsesAt(s: PulseScheduler, playheads: List<Long>) = playheads.filter { s.poll(it) }

    @Test
    fun pulseLeadFiresBeforeTheClickReachesTheOutput() {
        val s = PulseScheduler(rate, leadFrames = 1920) // 40 ms
        s.onClick(10_000)
        assertFalse(s.poll(10_000 - 1921))
        assertTrue(s.poll(10_000 - 1920))
    }

    @Test
    fun pulseQueueDropsClicksOlderThanPlayheadAndCapsRate() {
        val s = PulseScheduler(rate, maxPulsesPerSecond = 12)

        // Nothing is due until the playhead reaches the click.
        s.onClick(1000)
        assertFalse(s.poll(999))
        assertTrue(s.poll(1000))
        assertFalse("a click pulses once", s.poll(1001))

        // Several clicks passed between polls collapse into one pulse; the older ones are dropped.
        s.onClick(50_000)
        s.onClick(60_000)
        s.onClick(70_000)
        assertTrue(s.poll(75_000))
        assertFalse(s.poll(80_000))

        // A future click stays queued while older ones are consumed.
        s.onClick(90_000)
        s.onClick(200_000)
        assertTrue(s.poll(95_000))
        assertFalse(s.poll(199_999))
        assertTrue(s.poll(200_000))

        // Twenty clicks a second (sixteenths at 300 bpm) are thinned to at most 12 pulses a second.
        val t = PulseScheduler(rate, maxPulsesPerSecond = 12)
        val spacing = rate / 20L
        val clicks = (0 until 20).map { 1_000_000L + it * spacing }
        clicks.forEach(t::onClick)
        val emitted = pulsesAt(t, clicks)
        assertTrue("${emitted.size} pulses", emitted.size in 1..12)
        emitted.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= rate / 12) }
    }

    @Test
    fun everyClickPulsesUpToTheMaximumClickRate() {
        // 600 cpm (the app's cap) is 10 a second: under 12, so nothing is thinned.
        val s = PulseScheduler(rate)
        val clicks = (0 until 50).map { it * (rate * 60L / 600) }
        clicks.forEach(s::onClick)
        assertEquals(clicks, pulsesAt(s, clicks))
    }

    @Test
    fun resetForgetsQueuedClicksAndTheRateHistory() {
        val s = PulseScheduler(rate)
        s.onClick(10_000)
        assertTrue(s.poll(10_000))
        s.onClick(20_000)
        s.reset()
        assertFalse(s.poll(30_000))
        // Playhead restarts at 0 after a reset; a click right away still pulses.
        s.onClick(0)
        assertTrue(s.poll(0))
    }

    @Test
    fun queueOverflowKeepsTheNewestClicks() {
        val s = PulseScheduler(rate, capacity = 4)
        (1..10).forEach { s.onClick(it * 100_000L) }
        assertFalse("oldest were dropped", s.poll(600_000))
        assertTrue(s.poll(700_000))
    }

    @Test
    fun appNeverTouchesAudioFocusOrSystemVolume() {
        // Gradle runs unit tests from the module directory.
        val main = File("src/main")
        assertTrue("sources not found at ${main.absolutePath}", main.isDirectory)
        val forbidden = Regex(
            "requestAudioFocus|abandonAudioFocus|AudioFocusRequest|OnAudioFocusChangeListener|" +
                "setStreamVolume|adjustStreamVolume|adjustVolume|setAudioFocus|handleAudioFocus|" +
                // Player libraries that manage focus on our behalf.
                "\\bMediaPlayer\\b|ExoPlayer|media3",
        )
        val hits = main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (forbidden.containsMatchIn(l)) "$f:${i + 1}: $l" else null } }
            .toList()
        assertEquals("audio focus / volume calls found:\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }
}
