package me.jhot.jtmpo.playback

/** "Setlist · 2/5"; the name is left out while it is unknown. */
fun positionText(setlistName: String?, index: Int, size: Int): String =
    listOfNotNull(setlistName?.ifEmpty { null }, "${index + 1}/$size").joinToString(" · ")

/** The second line of the notification and media session: a song with no artist shows where it sits instead of "null". */
fun nowPlayingSubtitle(artist: String?, position: String): String = artist?.takeIf { it.isNotBlank() } ?: position
