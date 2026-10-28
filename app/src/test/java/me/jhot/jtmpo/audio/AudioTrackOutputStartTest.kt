package me.jhot.jtmpo.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class AudioTrackOutputStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val rhythm = Rhythm(120, TimeSignature(4, 4), ClickPattern(4))
    private val song = EffectiveSong(1, 1, "Song", null, rhythm, null, false, false, rhythm, null)

    private fun wire(factory: () -> AudioTrack): PlaybackController {
        val output = AudioTrackOutput(context, ClickRenderer(ShortArray(10) { 1000 }, 48000), factory)
        val playback = PlaybackController(output)
        output.onOutputLost = playback::pause
        playback.load(1, listOf(song), 0)
        return playback
    }

    @Suppress("DEPRECATION")
    private open inner class FakeTrack : AudioTrack(
        AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096, MODE_STREAM,
    ) {
        var released = false
        override fun release() {
            released = true
            super.release()
        }
    }

    @Test
    fun aTrackThatCannotBeBuiltLeavesPlaybackPaused() {
        val playback = wire { throw UnsupportedOperationException("no low-latency output") }
        playback.play() // must not throw on the UI thread
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(playback.state.value.isPlaying)
    }

    @Test
    fun aTrackThatCannotPlayIsReleasedAndPlaybackPaused() {
        val track = object : FakeTrack() {
            override fun play() = throw IllegalStateException("uninitialized")
        }
        val playback = wire { track }
        playback.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(track.released)
        assertFalse(playback.state.value.isPlaying)
    }

    @Test
    fun playWorksAgainAfterAFailedStart() {
        var fail = true
        val tracks = mutableListOf<FakeTrack>()
        val playback = wire { if (fail) throw IllegalStateException("busy") else FakeTrack().also { tracks += it } }
        playback.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(playback.state.value.isPlaying)

        fail = false
        playback.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("stays playing once the track starts", playback.state.value.isPlaying)
        playback.pause()
        assertTrue(tracks.single().released)
    }

    @Test
    fun stoppingLogsUnderrunsAndBufferSize() {
        val playback = wire { FakeTrack() }
        playback.play()
        shadowOf(Looper.getMainLooper()).idle()
        playback.pause()
        val lines = ShadowLog.getLogsForTag("jtmpo.audio").map { it.msg }
        assertTrue(lines.toString(), lines.any { "underruns=" in it && "bufferSizeInFrames=" in it })
    }
}
