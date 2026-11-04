package me.jhot.jtmpo.ui.nav

import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationTest {
    @Test
    fun uprightWhenFlipIsOff() {
        for (playerOnly in listOf(true, false)) for (playerOpen in listOf(true, false)) {
            assertEquals(SCREEN_ORIENTATION_PORTRAIT, requestedOrientation(flip = false, playerOnly = playerOnly, playerOpen = playerOpen))
        }
    }

    @Test
    fun wholeAppFlipsWhenNotLimitedToPlayer() {
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, requestedOrientation(flip = true, playerOnly = false, playerOpen = false))
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, requestedOrientation(flip = true, playerOnly = false, playerOpen = true))
    }

    @Test
    fun playerOnlyFlipsJustThePlayer() {
        assertEquals(SCREEN_ORIENTATION_REVERSE_PORTRAIT, requestedOrientation(flip = true, playerOnly = true, playerOpen = true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, requestedOrientation(flip = true, playerOnly = true, playerOpen = false))
    }

    @Test
    fun practiceStaysUprightWhenTheWholeAppFlipIsOff() {
        assertEquals(
            SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientation(flip = false, playerOnly = false, playerOpen = false, practiceOpen = true, practiceFlipped = false),
        )
    }

    @Test
    fun practiceFlipButtonTurnsItUpsideDownWhenTheWholeAppDoesNot() {
        assertEquals(
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            requestedOrientation(flip = false, playerOnly = false, playerOpen = false, practiceOpen = true, practiceFlipped = true),
        )
    }

    @Test
    fun practiceFollowsTheWholeAppFlipAndItsButtonInvertsIt() {
        assertEquals(
            "flip on, player only: practice follows the whole-app flip",
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            requestedOrientation(flip = true, playerOnly = false, playerOpen = false, practiceOpen = true),
        )
        assertEquals(
            SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientation(
                flip = true, playerOnly = false, playerOpen = false, practiceOpen = true, practiceFlipped = true,
            ),
        )
    }

    @Test
    fun playerOnlyFlipLeavesPracticeUprightUntilItsButtonTurnsIt() {
        assertEquals(
            SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientation(flip = true, playerOnly = true, playerOpen = false, practiceOpen = true),
        )
        assertEquals(
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            requestedOrientation(
                flip = true, playerOnly = true, playerOpen = false, practiceOpen = true, practiceFlipped = true,
            ),
        )
    }

    @Test
    fun anOpenPlayerWinsOverAnOpenPractice() {
        // A navigation transition has both flags set for a moment: the player rule is the one that shows.
        assertEquals(
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            requestedOrientation(
                flip = true, playerOnly = true, playerOpen = true, practiceOpen = true, practiceFlipped = false,
            ),
        )
        assertEquals(
            SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            requestedOrientation(
                flip = true, playerOnly = false, playerOpen = true, practiceOpen = true, practiceFlipped = true,
            ),
        )
    }
}
