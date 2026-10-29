package me.jhot.jtmpo.data

import java.util.Locale

internal fun titleKey(title: String): String = title.trim().lowercase(Locale.ROOT)

internal fun songKey(title: String, artist: String?): String =
    titleKey(title) + "\u0000" + titleKey(artist ?: "")
