package me.jhot.jtmpo.practice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import me.jhot.jtmpo.audio.AudioTrackOutput
import me.jhot.jtmpo.audio.ClickRenderer
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.PlaybackController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OutputLossBindingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val rhythm = Rhythm(120, TimeSignature(4, 4), ClickPattern(4))
    private val song = EffectiveSong(1, 1, "Song", null, rhythm, null, false, false, rhythm, null)

    @Suppress("DEPRECATION")
    private open inner class FakeTrack : AudioTrack(
        AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096, MODE_STREAM,
    )

    private fun rig(): Triple<AudioTrackOutput, PlaybackController, PracticeController> {
        // A fresh track per start, as AudioTrackOutput builds in production; the write returns at
        // once so the writer keeps moving without blocking the test.
        val output = AudioTrackOutput(context, ClickRenderer(ShortArray(10) { 1000 }, 48000)) {
            object : FakeTrack() {
                override fun write(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int) = sizeInShorts
            }
        }
        val playback = PlaybackController(output)
        val practice = PracticeController(output, playback)
        bindOutputLoss(output, playback, practice)
        return Triple(output, playback, practice)
    }

    @Test
    fun outputLossPausesBothControllers() {
        val (output, playback, practice) = rig()

        practice.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(practice.state.value.isPlaying)

        output.onOutputLost?.invoke()
        assertFalse("Practice pauses with the output", practice.state.value.isPlaying)

        playback.load(1, listOf(song), 0)
        playback.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(playback.state.value.isPlaying)

        output.onOutputLost?.invoke()
        assertFalse("the song pauses with the output", playback.state.value.isPlaying)
    }
}
