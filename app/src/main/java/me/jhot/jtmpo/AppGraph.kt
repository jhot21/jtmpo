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
import me.jhot.jtmpo.practice.PracticeController
import me.jhot.jtmpo.practice.PracticePersistence
import me.jhot.jtmpo.practice.bindOutputLoss

/** App-wide singletons, built once in [JtmpoApp]. */
class AppGraph(context: Context) {
    val repository = Repository(JtmpoDatabase.create(context))
    val audioOutput = AudioTrackOutput(context, AudioTrackOutput.loadClickRenderer(context))
    val playback = PlaybackController(audioOutput)
    val practice = PracticeController(audioOutput, playback)
    val settings = SettingsStore(context)

    /** True while the player screen is showing; it keeps the screen awake even when the click is paused. */
    val playerOpen = MutableStateFlow(false)

    /** True while the practice screen is showing, for its own keep-awake and flip. */
    val practiceOpen = MutableStateFlow(false)

    /** A file another app opened in jtmpo, read into memory; the import screen takes it and clears it. */
    val incoming = MutableStateFlow<ByteArray?>(null)

    init {
        // Unplugged headphones pause both click owners through the controllers, so the UI and session see it.
        bindOutputLoss(audioOutput, playback, practice)
        // A song or an editor Preview takes the output back from Practice.
        playback.onTakeOutput = practice::takeOver
        MainScope().launch { settings.pulseLeadMs.collect { audioOutput.pulseLeadMs = it } }
        PlaybackBinding(repository, playback, MainScope()).start()
        PracticePersistence(settings, practice, MainScope()).start()
        // Puts the player back on the last setlist and song, paused; it never starts the click.
        LastPositionTracker(playback, repository, LastPositionStore(context), MainScope()).start()
    }
}
