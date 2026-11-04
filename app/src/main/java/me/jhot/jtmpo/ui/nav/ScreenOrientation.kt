package me.jhot.jtmpo.ui.nav

import android.content.pm.ActivityInfo

/**
 * Reverse portrait puts the USB-C port on top; with [playerOnly] the rest of the app stays upright.
 *
 * Practice is only ever the top screen on its own: while [practiceOpen] it follows the whole-app
 * flip ([flip] without [playerOnly]) inverted by its own button ([practiceFlipped]), so the button
 * always visibly turns it. If the player is also open, a navigation transition has both flags set,
 * and the player rule wins.
 */
fun requestedOrientation(
    flip: Boolean,
    playerOnly: Boolean,
    playerOpen: Boolean,
    practiceOpen: Boolean = false,
    practiceFlipped: Boolean = false,
): Int {
    val reverse = if (practiceOpen && !playerOpen) {
        (flip && !playerOnly) != practiceFlipped
    } else {
        flip && (playerOpen || !playerOnly)
    }
    return if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
