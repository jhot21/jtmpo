package me.jhot.jtmpo.practice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.jhot.jtmpo.audio.AudioTrackOutput
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.clicksPerMinute
import me.jhot.jtmpo.playback.PlaybackController

data class PracticeState(
    val settings: PracticeSettings,
    val tempo: Int,
    val bar: Int,
    val nextTempo: Int?,
    val isPlaying: Boolean,
    /** True from the first play() until a song or an editor Preview takes the output. */
    val engaged: Boolean,
)

/**
 * The free-form practice metronome, and the song player's other claimant on the click output.
 *
 * [lock] guards the ramp and the published [state], and is shared with the audio thread's click
 * callback. It is never held while calling [ClickOutput.start]/[stop] (stop() joins the audio
 * thread, which may be waiting for this lock) or into [PlaybackController].
 */
class PracticeController(
    private val output: ClickOutput,
    private val playback: PlaybackController,
    initial: PracticeSettings = PracticeSettings(),
) {
    private val lock = Any()
    private val ramp = PracticeRamp(initial)
    private var isPlaying = false
    private var engaged = false

    /** Set by every user action, so a cold-start restore cannot overwrite one. */
    private var acted = false

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<PracticeState> = _state.asStateFlow()

    private val clickListener: () -> Unit = { onOutputClick() }

    fun play() {
        playback.outputLent = true
        // A no-op unless a song or an editor Preview is sounding.
        playback.pause()
        output.onClickStart = clickListener
        val cpm = synchronized(lock) {
            acted = true
            engaged = true
            // A second play() during a session must not re-arm the ramp: the next click would be
            // taken for a downbeat and the bar count would drift by a click.
            if (!isPlaying) {
                isPlaying = true
                ramp.play()
                publish()
            }
            rate()
        }
        output.setClicksPerMinute(cpm)
        output.start()
    }

    fun pause() {
        val wasPlaying = synchronized(lock) {
            acted = true
            val was = isPlaying
            isPlaying = false
            publish()
            was
        }
        if (!wasPlaying) return
        output.stop()
        // Cleared after the stop so a click already in the writer reaches the controller (which
        // returns at once, the session being paused) instead of racing a half-cleared listener.
        output.onClickStart = null
    }

    fun toggle() {
        if (state.value.isPlaying) pause() else play()
    }

    fun reset() {
        val cpm = synchronized(lock) {
            acted = true
            ramp.reset()
            publish()
            if (isPlaying) rate() else null
        }
        if (cpm == null) return
        output.stop()
        output.setClicksPerMinute(cpm)
        output.start()
    }

    /** A song or an editor Preview is taking the output: end the practice click and the loan. */
    fun takeOver() {
        synchronized(lock) {
            isPlaying = false
            engaged = false
            publish()
        }
        output.onClickStart = null
        output.stop()
        playback.outputLent = false
    }

    /** The tempo control: moves the live tempo mid-ramp, else the start tempo too. */
    fun nudgeTempo(tempo: Int) {
        val cpm = synchronized(lock) {
            acted = true
            ramp.nudge(tempo)
            publish()
            if (isPlaying) rate() else null
        }
        if (cpm != null) output.setClicksPerMinute(cpm)
    }

    /** The Start tempo row: never changes the live tempo once the session has begun. */
    fun setStartTempo(tempo: Int) {
        synchronized(lock) {
            acted = true
            ramp.setStartTempo(tempo)
            publish()
        }
    }

    fun update(transform: (PracticeSettings) -> PracticeSettings) {
        val cpm = synchronized(lock) {
            acted = true
            val before = rate()
            ramp.apply(transform(ramp.settings))
            publish()
            val after = rate()
            if (isPlaying && after != before) after else null
        }
        if (cpm != null) output.setClicksPerMinute(cpm)
    }

    /** The stored settings at launch. Ignored once the player has acted, so it cannot undo one. */
    fun restore(settings: PracticeSettings) {
        synchronized(lock) {
            if (acted) return
            ramp.apply(settings)
            ramp.reset()
            publish()
        }
    }

    /**
     * One click start, on the audio thread. Takes the lock only to count the click and build the
     * snapshot when something changed, then sets the rate outside it.
     */
    private fun onOutputClick() {
        val cpm = synchronized(lock) {
            // A click already in the writer when pause() stopped it must not move a paused ramp.
            if (!isPlaying) return
            if (!ramp.onClick()) return
            publish()
            rate()
        }
        output.setClicksPerMinute(cpm)
    }

    private fun rate(): Double =
        Rhythm(ramp.tempo, ramp.settings.timeSignature, ramp.settings.pattern).clicksPerMinute()

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot() = PracticeState(
        settings = ramp.settings,
        tempo = ramp.tempo,
        bar = ramp.bar,
        nextTempo = ramp.nextTempo,
        isPlaying = isPlaying,
        engaged = engaged,
    )
}

/**
 * When the output device goes away, or the track fails to start or dies, both click owners stop:
 * whichever one holds the output must end up paused, not playing silently. Called on the main
 * thread or the audio thread; neither pause holds a lock across `stop()`.
 */
fun bindOutputLoss(output: AudioTrackOutput, playback: PlaybackController, practice: PracticeController) {
    output.onOutputLost = {
        playback.pause()
        practice.pause()
    }
}
