package me.jhot.jtmpo

import android.content.Context
import me.jhot.jtmpo.audio.AudioTrackOutput
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.LastPositionStore
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.SettingsStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.jhot.jtmpo.playback.PlaybackBinding
import me.jhot.jtmpo.playback.LastPositionTracker
import me.jhot.jtmpo.playback.PlaybackController

/** App-wide singletons, built once in [JtmpoApp]. */
class AppGraph(context: Context) {
    val repository = Repository(JtmpoDatabase.create(context))
    val audioOutput = AudioTrackOutput(context, AudioTrackOutput.loadClickRenderer(context))
    val playback = PlaybackController(audioOutput)
    val settings = SettingsStore(context)

    /** True while the player screen is showing; it keeps the screen awake even when the click is paused. */
    val playerOpen = MutableStateFlow(false)

    /** A file another app opened in jtmpo, read into memory; the import screen takes it and clears it. */
    val incoming = MutableStateFlow<ByteArray?>(null)

    init {
        // Unplugged headphones pause through the controller, so the UI and session see it.
        audioOutput.onOutputLost = playback::pause
        MainScope().launch { settings.pulseLeadMs.collect { audioOutput.pulseLeadMs = it } }
        PlaybackBinding(repository, playback, MainScope()).start()
        // Puts the player back on the last setlist and song, paused; it never starts the click.
        LastPositionTracker(playback, repository, LastPositionStore(context), MainScope()).start()
    }
}
