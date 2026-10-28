package me.jhot.jtmpo.playback

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
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
}
