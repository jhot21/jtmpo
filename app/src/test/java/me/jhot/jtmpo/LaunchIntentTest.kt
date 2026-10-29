package me.jhot.jtmpo

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntentTest {
    @Test
    fun aFreshLaunchIsReceived() {
        assertTrue(shouldReceiveLaunchIntent(flags = 0, restored = false))
    }

    @Test
    fun aRecreatedActivityIsNotReceivedAgain() {
        assertFalse(shouldReceiveLaunchIntent(flags = 0, restored = true))
    }

    @Test
    fun aRelaunchFromRecentsIsNotReceivedAgain() {
        assertFalse(shouldReceiveLaunchIntent(flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, restored = false))
        assertFalse(
            shouldReceiveLaunchIntent(
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
                restored = false,
            ),
        )
    }
}
