package me.jhot.jtmpo.importer

import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.Rhythm

enum class ImportMode { SONGS_ONLY, SONGS_AND_SETLIST }

/** [existingId] is set for songs already in the library. */
data class ImportSong(val title: String, val rhythm: Rhythm, val existingId: Long? = null)

/** A setlist entry; [rhythmOverride] is set only where this copy differs from the library song. */
data class ImportEntry(val title: String, val rhythmOverride: Rhythm?)

/** A later copy whose rhythm differs from the one kept for the library. */
data class ImportConflict(val title: String, val kept: Rhythm, val other: Rhythm)

data class ImportPreview(
    val newSongs: List<ImportSong>,
    val reused: List<ImportSong>,
    val conflicts: List<ImportConflict>,
    val setlist: List<ImportEntry>?,
)

internal fun titleKey(title: String) = title.trim().lowercase()

/**
 * Plans an import without touching the database. Titles match trimmed and case-insensitively, and only
 * against library songs with no artist. The first copy wins for the library; a later copy that differs
 * is reported as a conflict (once per title) and, in setlist mode, becomes the entry's rhythm override.
 */
fun planImport(setlists: List<FztSetlist>, selected: List<Int>, mode: ImportMode, existing: List<Song>): ImportPreview {
    val library = HashMap<String, ImportSong>()
    for (song in existing.filter { it.artist == null }.sortedBy { it.id }) {
        library.putIfAbsent(titleKey(song.title), ImportSong(song.title, song.rhythm, song.id))
    }
    val newSongs = LinkedHashMap<String, ImportSong>()
    val reused = LinkedHashMap<String, ImportSong>()
    // One conflict per title (the first differing copy), however many copies differ.
    val conflicts = LinkedHashMap<String, ImportConflict>()
    val entries = mutableListOf<ImportEntry>()

    for (index in selected.distinct().sorted()) {
        for (fzt in setlists.getOrNull(index)?.songs.orEmpty()) {
            val title = fzt.title.trim()
            if (title.isEmpty()) continue
            val rhythm = mapFztSong(fzt)
            val key = titleKey(title)
            val kept = library[key] ?: ImportSong(title, rhythm).also { library[key] = it; newSongs[key] = it }
            if (kept.existingId != null) reused.putIfAbsent(key, kept)
            val differs = kept.rhythm != rhythm
            if (differs) conflicts.putIfAbsent(key, ImportConflict(kept.title, kept.rhythm, rhythm))
            entries += ImportEntry(kept.title, rhythm.takeIf { differs })
        }
    }
    return ImportPreview(
        newSongs.values.toList(),
        reused.values.toList(),
        conflicts.values.toList(),
        entries.takeIf { mode == ImportMode.SONGS_AND_SETLIST },
    )
}

/**
 * Applies a preview in one transaction: new songs, then (if the preview has a setlist) the setlist and its
 * entries. Any failure rolls everything back.
 */
suspend fun Repository.commitImport(preview: ImportPreview, setlistName: String?) = runInTransaction {
    val ids = HashMap<String, Long>()
    preview.reused.forEach { song -> ids[titleKey(song.title)] = checkNotNull(song.existingId) }
    preview.newSongs.forEach { ids[titleKey(it.title)] = createSong(it.title, null, it.rhythm, null) }
    val entries = preview.setlist ?: return@runInTransaction
    val setlistId = createSetlist(setlistName?.trim().takeUnless { it.isNullOrEmpty() } ?: "Tempo import")
    addEntries(
        setlistId,
        entries.map { (checkNotNull(ids[titleKey(it.title)]) { "Entry for unknown song ${it.title}" }) to it.rhythmOverride },
    )
}
