package me.jhot.jtmpo.practice

import me.jhot.jtmpo.domain.TempoLimits

/**
 * Bar counting and tempo steps for the practice ramp, driven by the click stream.
 * Not thread-safe: the controller locks around it.
 */
class PracticeRamp(settings: PracticeSettings = PracticeSettings()) {
    var settings: PracticeSettings = settings.sanitized()
        private set

    var tempo: Int = this.settings.startTempo
        private set

    /** 1-based bar within the current interval. */
    var bar: Int = 1
        private set

    var begun: Boolean = false
        private set

    /** Tempo the next step reaches, or null with the ramp off or at a limit. */
    val nextTempo: Int?
        get() {
            if (settings.direction == RampDirection.Off) return null
            return clamp(tempo + step).takeIf { it != tempo }
        }

    private var clickInBar = 0
    private var skipAdvance = false

    /** The next click is the downbeat of the current bar; the bar count is kept. */
    fun play() {
        begun = true
        skipAdvance = true
    }

    /** Called once per click start; true when [tempo] or [bar] changed. */
    fun onClick(): Boolean {
        if (settings.direction == RampDirection.Off) return false
        val beforeTempo = tempo
        val beforeBar = bar
        if (skipAdvance) {
            skipAdvance = false
            clickInBar = 1
        } else {
            clickInBar++
            if (clickInBar > clicksInBar()) {
                clickInBar = 1
                bar++
                if (bar > settings.everyBars) {
                    bar = 1
                    tempo = clamp(tempo + step)
                }
            }
        }
        return tempo != beforeTempo || bar != beforeBar
    }

    /** Back to the start tempo, stopped, at bar 1. */
    fun reset() {
        tempo = clamp(settings.startTempo)
        bar = 1
        clickInBar = 0
        skipAdvance = false
        begun = false
    }

    /**
     * Mid-ramp (begun, ramp on) this moves the live tempo and leaves the interval alone;
     * otherwise it moves the start tempo, since the session sits at its start.
     */
    fun nudge(tempo: Int) {
        val next = clamp(tempo)
        if (begun && settings.direction != RampDirection.Off) {
            this.tempo = next
        } else {
            settings = settings.copy(startTempo = next)
            this.tempo = next
        }
    }

    /**
     * Moves the set point. While the session is at its start (not [begun]) the live tempo
     * follows it; once begun, the live tempo takes it up on the next [reset].
     */
    fun setStartTempo(tempo: Int) {
        val next = clamp(tempo)
        settings = settings.copy(startTempo = next)
        this.tempo = if (begun) clamp(this.tempo) else next
    }

    /**
     * A changed signature or pattern restarts the bar count, and so does turning the ramp on from
     * Off: the count was frozen while the grid ran on, so the next click is re-anchored as a
     * downbeat. Anything else keeps it.
     */
    fun apply(settings: PracticeSettings) {
        val next = settings.sanitized()
        val gridChanged =
            next.timeSignature != this.settings.timeSignature || next.pattern != this.settings.pattern
        val rampStarted =
            this.settings.direction == RampDirection.Off && next.direction != RampDirection.Off
        this.settings = next
        tempo = clamp(tempo)
        if (gridChanged || rampStarted) {
            bar = 1
            clickInBar = 0
            skipAdvance = true
        }
    }

    private val step: Int
        get() = when (settings.direction) {
            RampDirection.Up -> settings.stepBpm
            RampDirection.Down -> -settings.stepBpm
            RampDirection.Off -> 0
        }

    private fun clamp(value: Int): Int = value.coerceIn(TempoLimits.MIN, maxTempo())

    private fun clicksInBar(): Int = clicksPerBar(settings.timeSignature, settings.pattern)

    private fun maxTempo(): Int =
        minOf(TempoLimits.MAX, maxPracticeTempo(settings.timeSignature, settings.pattern))
}
