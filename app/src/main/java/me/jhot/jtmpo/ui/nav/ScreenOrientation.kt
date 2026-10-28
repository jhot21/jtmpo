package me.jhot.jtmpo.ui.nav

import android.content.pm.ActivityInfo

/** Reverse portrait puts the USB-C port on top; with [playerOnly] the rest of the app stays upright. */
fun requestedOrientation(flip: Boolean, playerOnly: Boolean, playerOpen: Boolean): Int =
    if (flip && (playerOpen || !playerOnly)) ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
