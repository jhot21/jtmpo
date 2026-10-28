package me.jhot.jtmpo.audio

/**
 * Turns click start frames (when a click was written) into visual pulses (when it is heard).
 *
 * The writer records each click's absolute frame with [onClick]; [poll] is given the frame now
 * reaching the speaker and reports whether to pulse. Every click at or before the playhead is
 * consumed, but they collapse into at most one pulse (the newest), and pulses closer together
 * than 1/[maxPulsesPerSecond] of audio time are skipped, so fast subdivisions don't strobe.
 *
 * Not thread-safe and allocation-free after construction: owned by the audio writer thread.
 */
class PulseScheduler(sampleRate: Int, maxPulsesPerSecond: Int = 12, capacity: Int = 64, var leadFrames: Long = 0) {
    init {
        require(sampleRate > 0 && maxPulsesPerSecond > 0 && capacity > 0)
    }

    private val minGap = sampleRate.toLong() / maxPulsesPerSecond
    private val queue = LongArray(capacity)
    private var head = 0
    private var size = 0
    private var lastPulse = NONE

    fun onClick(frame: Long) {
        if (size == queue.size) drop() // keep the newest; old ones would be stale anyway
        queue[(head + size) % queue.size] = frame
        size++
    }

    /**
     * True if a pulse should be shown now that [playhead] frames have been heard. A pulse fires
     * [leadFrames] early to make up for the poll interval and the time the screen takes to draw it.
     */
    fun poll(playhead: Long): Boolean {
        var due = NONE
        while (size > 0 && queue[head] <= playhead + leadFrames) {
            due = queue[head]
            drop()
        }
        if (due == NONE) return false
        if (lastPulse != NONE && due - lastPulse < minGap) return false
        lastPulse = due
        return true
    }

    fun reset() {
        head = 0
        size = 0
        lastPulse = NONE
    }

    private fun drop() {
        head = (head + 1) % queue.size
        size--
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
