package me.jhot.jtmpo.audio

import kotlin.math.ceil
import me.jhot.jtmpo.domain.TempoLimits

/** Told the buffer index of each click as it starts; a fun interface so the audio thread never boxes an Int. */
fun interface ClickListener {
    fun onClickStart(frameIndexInBuffer: Int)
}

/**
 * Renders a metronome click track into mono 16-bit buffers.
 *
 * Timing is kept as a fractional frame count so the click grid never drifts, whatever the
 * buffer sizes. A new rate applies when the next click is scheduled; if the pending click is
 * further away than one new interval (a big speed-up), it is pulled in to one new interval so
 * the change is never followed by a long gap. Overlapping clicks are mixed with saturation.
 *
 * [setClicksPerMinute] and [reset] may be called from any thread; [render] belongs to the
 * audio thread. Requests are picked up at the start of the next [render].
 */
class ClickRenderer(private val click: ShortArray, private val sampleRate: Int) {
    companion object {
        /** Floor for nonsense input; the slowest real rhythm is 20 bpm dotted-quarter in x/8 (~6.7 cpm). */
        const val MIN_CPM = 1.0
        private const val DEFAULT_CPM = 120.0
        private val NO_LISTENER = ClickListener {}
        private const val CLICK_EPSILON = 1e-6
    }

    @Volatile private var requestedInterval = intervalFor(DEFAULT_CPM)
    @Volatile private var resetRequested = false

    private var interval = requestedInterval
    private var framesUntilNextClick = 0.0

    // Read positions of sounding clicks; sized for the most that can overlap at MAX_CPM.
    private val voices = IntArray(ceil(click.size / intervalFor(TempoLimits.MAX_CPM)).toInt() + 1)
    private var voiceCount = 0

    fun setClicksPerMinute(cpm: Double) {
        if (cpm.isNaN()) return
        requestedInterval = intervalFor(cpm)
    }

    /** Silences sounding clicks and makes the next [render] click on its first frame. */
    fun reset() {
        resetRequested = true
    }

    fun render(out: ShortArray, frames: Int, listener: ClickListener = NO_LISTENER) {
        require(frames in 0..out.size) { "frames $frames out of range for buffer of ${out.size}" }
        if (resetRequested) {
            resetRequested = false
            voiceCount = 0
            framesUntilNextClick = 0.0
        }
        val requested = requestedInterval
        if (requested != interval) {
            interval = requested
            if (framesUntilNextClick > interval) framesUntilNextClick = interval
        }

        for (i in 0 until frames) {
            // The epsilon absorbs rounding in the running sum: a click ideally on a whole frame sounds
            // on that frame, not one late.
            if (framesUntilNextClick <= CLICK_EPSILON) {
                // += keeps the fractional remainder, so error never accumulates across clicks
                framesUntilNextClick += interval
                startVoice()
                listener.onClickStart(i)
            }
            framesUntilNextClick -= 1.0
            out[i] = mixFrame()
        }
    }

    private fun startVoice() {
        if (click.isEmpty()) return
        if (voiceCount == voices.size) dropVoice(0) // unreachable within MAX_CPM; keep newest
        voices[voiceCount++] = 0
    }

    private fun mixFrame(): Short {
        var sum = 0
        var v = 0
        while (v < voiceCount) {
            sum += click[voices[v]]
            if (++voices[v] >= click.size) dropVoice(v) else v++
        }
        return sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun dropVoice(index: Int) {
        voices.copyInto(voices, index, index + 1, voiceCount)
        voiceCount--
    }

    private fun intervalFor(cpm: Double) = sampleRate * 60.0 / cpm.coerceIn(MIN_CPM, TempoLimits.MAX_CPM)
}
