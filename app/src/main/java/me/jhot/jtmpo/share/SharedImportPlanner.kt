package me.jhot.jtmpo.share

import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.data.songKey
import me.jhot.jtmpo.data.titleKey
import me.jhot.jtmpo.domain.Rhythm

enum class Match { EXACT, NEW, CONFLICT_A, CONFLICT_B }

/** One distinct file song and what it maps to; [candidate] is the library song for every match but [Match.NEW]. */
data class SongPlan(val song: SharedSong, val match: Match, val candidate: Song?)

data class SharedImportPlan(val name: String, val songs: List<SongPlan>, val entries: List<SharedEntry>) {
    val conflicts: List<SongPlan> get() = songs.filter { it.match == Match.CONFLICT_A || it.match == Match.CONFLICT_B }
    val entryCount: Int get() = entries.size
    val exactCount: Int get() = songs.count { it.match == Match.EXACT }
    val newCount: Int get() = songs.count { it.match == Match.NEW }
}

enum class Choice { UPDATE, KEEP_MINE, NEW }

/** An entry keyed by the file-local song id, so songs sharing a title never collide at commit time. */
data class ResolvedEntry(val localSongId: Long, val rhythmOverride: Rhythm?, val notesOverride: String?)

data class ResolvedImport(
    /** Library song id to the file song whose library values replace it. */
    val updates: List<Pair<Long, SharedSong>>,
    val newSongs: List<SharedSong>,
    /** File-local song id to the library song id it reuses. */
    val reused: Map<Long, Long>,
    val entries: List<ResolvedEntry>,
    val name: String,
)

fun planSharedImport(file: SharedSetlist, library: List<Song>): SharedImportPlan {
    val byKey = library.associateBy { songKey(it.title, it.artist) }
    val claimed = mutableSetOf<Long>()

    // Exact matches claim first across the whole file, so a later exact twin is never taken by an earlier dialog.
    val exact = file.songs.mapNotNull { song -> byKey[songKey(song.title, song.artist)]?.let { song.localId to it } }.toMap()
    exact.values.forEach { claimed += it.id }

    val plans = file.songs.map { song ->
        exact[song.localId]?.let { return@map SongPlan(song, Match.EXACT, it) }
        val sameTitle = library.filter { it.id !in claimed && titleKey(it.title) == titleKey(song.title) }
        val plan = if (song.artist != null) {
            sameTitle.firstOrNull { it.artist.isNullOrBlank() }?.let { SongPlan(song, Match.CONFLICT_A, it) }
        } else {
            sameTitle.filter { !it.artist.isNullOrBlank() }.minByOrNull { it.id }?.let { SongPlan(song, Match.CONFLICT_B, it) }
        } ?: SongPlan(song, Match.NEW, null)
        plan.candidate?.let { claimed += it.id }
        plan
    }
    return SharedImportPlan(file.name, plans, file.entries)
}

fun resolveSharedImport(plan: SharedImportPlan, decisions: Map<Long, Choice>): ResolvedImport {
    val updates = mutableListOf<Pair<Long, SharedSong>>()
    val newSongs = mutableListOf<SharedSong>()
    val reused = mutableMapOf<Long, Long>()
    // The rhythm and notes each file song's entries are compared with: the library song as it will be after commit.
    val baseline = mutableMapOf<Long, Pair<Rhythm, String?>>()

    for (p in plan.songs) {
        val song = p.song
        val choice = when (p.match) {
            Match.EXACT -> Choice.KEEP_MINE
            Match.NEW -> Choice.NEW
            Match.CONFLICT_A, Match.CONFLICT_B -> {
                val c = requireNotNull(decisions[song.localId]) { "No decision for \"${song.title}\"" }
                require(!(p.match == Match.CONFLICT_B && c == Choice.UPDATE)) { "\"${song.title}\" cannot be updated" }
                c
            }
        }
        when (choice) {
            Choice.KEEP_MINE -> {
                val mine = p.candidate!!
                reused[song.localId] = mine.id
                baseline[song.localId] = mine.rhythm to mine.notes
            }
            Choice.UPDATE -> {
                val mine = p.candidate!!
                updates += mine.id to song
                reused[song.localId] = mine.id
                baseline[song.localId] = song.rhythm to song.notes
            }
            Choice.NEW -> {
                newSongs += song
                baseline[song.localId] = song.rhythm to song.notes
            }
        }
    }

    val entries = plan.entries.map { entry ->
        val (rhythm, notes) = baseline.getValue(entry.songId)
        ResolvedEntry(
            localSongId = entry.songId,
            rhythmOverride = entry.rhythm.takeIf { it != rhythm },
            // Blank file notes are no opinion, so the recipient's own notes are never hidden.
            notesOverride = entry.notes?.takeIf { it.isNotBlank() && it != (notes ?: "") },
        )
    }
    return ResolvedImport(updates, newSongs, reused, entries, plan.name)
}
