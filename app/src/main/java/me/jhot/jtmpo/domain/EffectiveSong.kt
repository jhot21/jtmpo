package me.jhot.jtmpo.domain

data class EffectiveSong(
    val entryId: Long,
    val songId: Long,
    val title: String,
    val artist: String?,
    val rhythm: Rhythm,
    val notes: String?,
    val rhythmOverridden: Boolean,
    val notesOverridden: Boolean,
    val libraryRhythm: Rhythm,
    val libraryNotes: String?,
)
