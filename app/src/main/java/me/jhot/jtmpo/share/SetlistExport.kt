package me.jhot.jtmpo.share

import kotlinx.coroutines.flow.first
import me.jhot.jtmpo.data.Repository

/**
 * Builds the shareable model of a setlist: songs carry their library values and are listed once in
 * first-use order, entries carry the effective rhythm and notes. Null when missing or empty.
 */
suspend fun Repository.exportSetlist(setlistId: Long): SharedSetlist? {
    val name = setlistName(setlistId) ?: return null
    val rows = entries(setlistId).first()
    if (rows.isEmpty()) return null
    val localIds = LinkedHashMap<Long, SharedSong>()
    val entries = rows.map { row ->
        val song = localIds.getOrPut(row.songId) {
            SharedSong(localIds.size + 1L, row.title, row.artist, row.libraryRhythm, row.libraryNotes)
        }
        SharedEntry(song.localId, row.rhythm, row.notes)
    }
    return SharedSetlist(name, localIds.values.toList(), entries)
}
