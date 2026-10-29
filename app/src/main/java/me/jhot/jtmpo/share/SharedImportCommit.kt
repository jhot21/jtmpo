package me.jhot.jtmpo.share

import me.jhot.jtmpo.data.EntryDraft
import me.jhot.jtmpo.data.Repository

/**
 * Writes [resolved] in one transaction and returns the new setlist id; any failure (such as a
 * duplicate song) rolls everything back. Entries are mapped by file-local song id, never by title.
 */
suspend fun Repository.commitShared(resolved: ResolvedImport): Long = runInTransaction {
    for ((libraryId, shared) in resolved.updates) {
        val mine = checkNotNull(song(libraryId)) { "No song $libraryId" }
        updateSong(mine.copy(artist = shared.artist, rhythm = shared.rhythm, notes = shared.notes))
    }
    val created = resolved.newSongs.associate { shared ->
        shared.localId to createSong(shared.title, shared.artist, shared.rhythm, shared.notes)
    }
    // Chosen here, not earlier, so a setlist added meanwhile cannot make two share a name.
    val setlistId = createSetlist(unusedSetlistName(resolved.name, snapshot().setlists.map { it.name }))
    addEntries(
        setlistId,
        resolved.entries.map { entry ->
            val songId = resolved.reused[entry.localSongId] ?: created.getValue(entry.localSongId)
            EntryDraft(songId, entry.rhythmOverride, entry.notesOverride)
        },
    )
    setlistId
}

/** [name] itself if no setlist in [existing] has it (trimmed, ignoring case), else "Name (2)", "Name (3)", ... */
fun unusedSetlistName(name: String, existing: Collection<String>): String {
    val taken = existing.map { it.trim().lowercase() }.toSet()
    return generateSequence(1) { it + 1 }
        .map { if (it == 1) name else "$name ($it)" }
        .first { it.trim().lowercase() !in taken }
}
