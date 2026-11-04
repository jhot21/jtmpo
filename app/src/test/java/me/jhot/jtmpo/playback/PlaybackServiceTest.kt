package me.jhot.jtmpo.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.jhot.jtmpo.JtmpoApp
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
    private val app = ApplicationProvider.getApplicationContext<JtmpoApp>()

    @Test
    fun doesNotStopItselfBeforeGoingForeground() {
        // Nothing loaded: the state collector sees no current song before onStartCommand runs.
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(controller.get())
        assertFalse("stopSelf before startForeground crashes the app", shadow.isStoppedBySelf)

        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        // Foreground was entered and then left (stopForeground clears the shadow's notification).
        assertTrue("left the foreground", shadow.isForegroundStopped)
        assertTrue("then stops, since nothing is loaded", shadow.isStoppedBySelf)
    }

    @Test
    fun staysForegroundWhilePracticeIsEngagedWithNoSetlist() {
        val practice = app.graph.practice
        practice.play()

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(controller.get())

        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("Practice still owns the click", shadow.isStoppedBySelf)
        assertFalse("the click stays in the foreground", shadow.isForegroundStopped)
        assertEquals("Practice", title(shadow.lastForegroundNotification))
        assertEquals("${practice.state.value.tempo} bpm", text(shadow.lastForegroundNotification))
    }

    @Test
    fun stopsWhenPracticeIsTakenOver() {
        val practice = app.graph.practice
        practice.play()
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(controller.get())
        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadow.isStoppedBySelf)

        // Nothing is loaded, so playback.play() returns before its take-over hook; the editor's
        // takeOver() is how a song ends the practice click with an empty player.
        practice.takeOver()
        await("the service to stop") { shadow.isStoppedBySelf }

        assertFalse(practice.state.value.engaged)
    }

    @Test
    fun aSongTakingOverLeavesTheSongNotification() {
        val practice = app.graph.practice
        val playback = app.graph.playback
        val (setlistId, entries) = runBlocking {
            val rhythm = Rhythm(96, TimeSignature(4, 4), ClickPattern(4))
            val songId = app.graph.repository.createSong("Valerie", null, rhythm, null)
            val listId = app.graph.repository.createSetlist("Gig")
            app.graph.repository.addSongs(listId, listOf(songId))
            listId to app.graph.repository.entries(listId).first()
        }
        practice.play()
        playback.load(setlistId, entries, 0)

        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Practice", title(notification(controller.get())))

        playback.play()
        await("the song notification") { title(notification(controller.get())) == "Valerie" }

        assertFalse("a song takes the output back from Practice", practice.state.value.engaged)
    }

    @Test
    fun toggleActionPausesPractice() {
        val practice = app.graph.practice
        practice.play()
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        shadowOf(Looper.getMainLooper()).idle()
        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(practice.state.value.isPlaying)

        val toggle = Intent(app, PlaybackService::class.java).setAction(PlaybackService.ACTION_TOGGLE)
        @Suppress("DEPRECATION") // withIntent is how a ServiceController takes the start intent.
        val toggled = controller.withIntent(toggle)
        toggled.startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("the notification toggle pauses Practice", practice.state.value.isPlaying)
        assertTrue("Practice keeps the service in the foreground", practice.state.value.engaged)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    private fun notification(service: Service): Notification? =
        shadowOf(service.getSystemService(NotificationManager::class.java)).allNotifications.lastOrNull()

    private fun title(notification: Notification?) =
        notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(notification: Notification?) =
        notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Idles the paused main looper until [condition] holds; Room's setlist flow emits from its own thread. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
