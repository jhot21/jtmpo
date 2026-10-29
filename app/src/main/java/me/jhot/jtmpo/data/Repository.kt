package me.jhot.jtmpo.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature

data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val rhythm: Rhythm,
    val notes: String?,
    val createdAt: Long,
)

data class SetlistSummary(val id: Long, val name: String, val songCount: Int, val lastPlayedAt: Long?)

/** The full deleted row, so [Repository.restoreEntry] can put it back at the same id and position. */
data class RemovedEntry(val row: EntryEntity)

/** A deleted library song plus every setlist entry that referenced it, for [Repository.restoreSong]. */
data class RemovedSong(val song: SongEntity, val entries: List<EntryEntity>)

/** A deleted setlist row plus all its entries (ids, overrides, positions), for [Repository.restoreSetlist]. */
data class RemovedSetlist(val setlist: SetlistEntity, val entries: List<EntryEntity>)

@Serializable
data class BackupSong(
    val id: Long,
    val title: String,
    val artist: String?,
    val tempo: Int,
    val tsNum: Int,
    val tsDen: Int,
    val patDen: Int,
    val patDotted: Boolean,
    val notes: String?,
    val createdAt: Long,
)

@Serializable
data class BackupSetlist(val id: Long, val name: String, val lastPlayedAt: Long?)

/** Rhythm override columns are all null or all set; [notesOverridden] gates [ovNotes]. */
@Serializable
data class BackupEntry(
    val id: Long,
    val setlistId: Long,
    val songId: Long,
    val position: Int,
    val ovTempo: Int?,
    val ovTsNum: Int?,
    val ovTsDen: Int?,
    val ovPatDen: Int?,
    val ovPatDotted: Boolean?,
    val notesOverridden: Boolean,
    val ovNotes: String?,
)

@Serializable
data class BackupSnapshot(
    val songs: List<BackupSong>,
    val setlists: List<BackupSetlist>,
    val entries: List<BackupEntry>,
)

private val VALID_DENOMINATORS = setOf(2, 4, 8, 16)
private val FALLBACK_SIGNATURE = TimeSignature(4, 4)
private val FALLBACK_PATTERN = ClickPattern(4)

/**
 * Stored values are never trusted: a bad time-signature denominator would make `patternsFor`
 * throw in the UI, so it falls back to 4/4 with a quarter pattern; tempo is clamped.
 */
internal fun storedRhythm(tempo: Int, tsNum: Int, tsDen: Int, patDen: Int, patDotted: Boolean): Rhythm {
    val clamped = tempo.coerceIn(TempoLimits.MIN, TempoLimits.MAX)
    return if (tsDen in VALID_DENOMINATORS && tsNum > 0 && patDen in VALID_DENOMINATORS) {
        Rhythm(clamped, TimeSignature(tsNum, tsDen), ClickPattern(patDen, patDotted))
    } else {
        Rhythm(clamped, FALLBACK_SIGNATURE, FALLBACK_PATTERN)
    }
}

private fun SongEntity.rhythm() = storedRhythm(tempo, tsNum, tsDen, patDen, patDotted)

private fun SongEntity.toSong() = Song(id, title, artist, rhythm(), notes, createdAt)

private fun EntryRow.toEffective(): EffectiveSong {
    val library = storedRhythm(tempo, tsNum, tsDen, patDen, patDotted)
    val hasRhythmOverride = ovTempo != null && ovTsNum != null && ovTsDen != null &&
        ovPatDen != null && ovPatDotted != null
    val effective = if (hasRhythmOverride) {
        storedRhythm(ovTempo!!, ovTsNum!!, ovTsDen!!, ovPatDen!!, ovPatDotted!!)
    } else {
        library
    }
    return EffectiveSong(
        entryId = entryId,
        songId = songId,
        title = title,
        artist = artist,
        rhythm = effective,
        notes = if (notesOverridden) ovNotes else libNotes,
        rhythmOverridden = hasRhythmOverride,
        notesOverridden = notesOverridden,
        libraryRhythm = library,
        libraryNotes = libNotes,
    )
}

private fun String?.normalized() = this?.takeIf { it.isNotEmpty() }

class DuplicateSongException : Exception("A song with this title and artist already exists")

class Repository(
    private val db: JtmpoDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val songDao = db.songDao()
    private val setlistDao = db.setlistDao()
    private val entryDao = db.entryDao()

    /** Runs [block] atomically; an exception rolls back every write made inside it. */
    suspend fun <T> runInTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    /**
     * The one key computation for every writer, so the duplicate guard and the unique index can
     * never disagree about what counts as the same song.
     */
    private fun keyFor(title: String, artist: String?): String = songKey(title, artist)

    // Songs

    fun songs(): Flow<List<Song>> = songDao.observeAll().map { rows -> rows.map { it.toSong() } }

    suspend fun song(id: Long): Song? = songDao.get(id)?.toSong()

    suspend fun createSong(title: String, artist: String?, rhythm: Rhythm, notes: String?): Long =
        db.withTransaction {
            val key = keyFor(title, artist)
            if (songDao.idForKey(key) != null) throw DuplicateSongException()
            songDao.insert(
                SongEntity(
                    title = title,
                    artist = artist,
                    tempo = rhythm.tempo,
                    tsNum = rhythm.timeSignature.numerator,
                    tsDen = rhythm.timeSignature.denominator,
                    patDen = rhythm.pattern.noteDenominator,
                    patDotted = rhythm.pattern.dotted,
                    notes = notes,
                    createdAt = clock(),
                    songKey = key,
                ),
            )
        }

    suspend fun updateSong(song: Song) = db.withTransaction {
        val key = keyFor(song.title, song.artist)
        val colliding = songDao.idForKey(key)
        if (colliding != null && colliding != song.id) throw DuplicateSongException()
        songDao.update(
            SongEntity(
                id = song.id,
                title = song.title,
                artist = song.artist,
                tempo = song.rhythm.tempo,
                tsNum = song.rhythm.timeSignature.numerator,
                tsDen = song.rhythm.timeSignature.denominator,
                patDen = song.rhythm.pattern.noteDenominator,
                patDotted = song.rhythm.pattern.dotted,
                notes = song.notes,
                createdAt = song.createdAt,
                songKey = key,
            ),
        )
    }

    /** Call before [deleteSong] to warn the user which setlists lose an entry. */
    suspend fun setlistNamesContaining(songId: Long): List<String> = entryDao.setlistNamesContaining(songId)

    /** Cascades to entries, then closes the position gaps in every affected setlist. */
    suspend fun deleteSong(songId: Long) = db.withTransaction {
        val affected = entryDao.setlistIdsContaining(songId)
        songDao.delete(songId)
        affected.forEach { reindex(it) }
    }

    /** Like [deleteSong], but returns everything needed to undo it with [restoreSong]. */
    suspend fun removeSong(songId: Long): RemovedSong = db.withTransaction {
        val song = checkNotNull(songDao.get(songId)) { "No song $songId" }
        val entries = entryDao.forSong(songId)
        deleteSong(songId)
        RemovedSong(song, entries)
    }

    /**
     * Re-inserts the song under its old id, then its entries (ascending position per setlist, so
     * each lands at its old index). Entries whose setlist is gone are dropped, and so is the whole
     * undo if the same title and artist has been created again since the delete. Idempotent.
     */
    suspend fun restoreSong(removed: RemovedSong) = db.withTransaction {
        if (songDao.get(removed.song.id) != null) return@withTransaction
        // Its key taken means the song was re-created after the delete; restoring would only hit
        // the unique index, so the undo quietly does nothing.
        if (songDao.idForKey(removed.song.songKey) != null) return@withTransaction
        songDao.insertAll(listOf(removed.song))
        removed.entries.sortedWith(compareBy({ it.setlistId }, { it.position })).forEach { insertEntryAt(it) }
    }

    // Setlists

    fun setlists(): Flow<List<SetlistSummary>> = setlistDao.observeSummaries().map { rows ->
        rows.map { SetlistSummary(it.id, it.name, it.songCount, it.lastPlayedAt) }
    }

    suspend fun createSetlist(name: String): Long = setlistDao.insert(SetlistEntity(name = name, lastPlayedAt = null))

    suspend fun setlistName(id: Long): String? = setlistDao.get(id)?.name

    suspend fun renameSetlist(id: Long, name: String) = setlistDao.rename(id, name)

    suspend fun deleteSetlist(id: Long) = setlistDao.delete(id)

    /** Like [deleteSetlist], but returns everything needed to undo it with [restoreSetlist]. */
    suspend fun removeSetlist(id: Long): RemovedSetlist = db.withTransaction {
        val setlist = checkNotNull(setlistDao.get(id)) { "No setlist $id" }
        val entries = entryDao.forSetlist(id)
        setlistDao.delete(id)
        RemovedSetlist(setlist, entries)
    }

    /**
     * Re-inserts the setlist under its old id with its name and last-played time, then its entries in
     * order. Entries whose song has been deleted since are dropped. Idempotent (a double Undo tap).
     */
    suspend fun restoreSetlist(removed: RemovedSetlist) = db.withTransaction {
        if (setlistDao.exists(removed.setlist.id) != 0) return@withTransaction
        setlistDao.insertAll(listOf(removed.setlist))
        val kept = removed.entries.sortedBy { it.position }.filter { songDao.get(it.songId) != null }
        entryDao.insertAll(kept.mapIndexed { index, entry -> entry.copy(position = index) })
    }

    suspend fun markPlayed(setlistId: Long) = setlistDao.markPlayed(setlistId, clock())

    // Entries

    fun entries(setlistId: Long): Flow<List<EffectiveSong>> =
        entryDao.observeRows(setlistId).map { rows -> rows.map { it.toEffective() } }

    suspend fun entry(entryId: Long): EffectiveSong? = entryDao.row(entryId)?.toEffective()

    suspend fun addSongs(setlistId: Long, songIds: List<Long>) = db.withTransaction {
        var position = entryDao.nextPosition(setlistId)
        entryDao.insertAll(songIds.map { EntryEntity(setlistId = setlistId, songId = it, position = position++) })
    }

    /** Appends entries in order; a non-null rhythm becomes that entry's rhythm override. */
    suspend fun addEntries(setlistId: Long, items: List<Pair<Long, Rhythm?>>) = db.withTransaction {
        var position = entryDao.nextPosition(setlistId)
        entryDao.insertAll(
            items.map { (songId, rhythm) ->
                EntryEntity(
                    setlistId = setlistId,
                    songId = songId,
                    position = position++,
                    ovTempo = rhythm?.tempo,
                    ovTsNum = rhythm?.timeSignature?.numerator,
                    ovTsDen = rhythm?.timeSignature?.denominator,
                    ovPatDen = rhythm?.pattern?.noteDenominator,
                    ovPatDotted = rhythm?.pattern?.dotted,
                )
            },
        )
    }

    suspend fun moveEntry(setlistId: Long, from: Int, to: Int) = db.withTransaction {
        val ordered = entryDao.forSetlist(setlistId).toMutableList()
        if (from !in ordered.indices || to !in ordered.indices || from == to) return@withTransaction
        ordered.add(to, ordered.removeAt(from))
        ordered.forEachIndexed { index, entry ->
            if (entry.position != index) entryDao.setPosition(entry.id, index)
        }
    }

    suspend fun removeEntry(entryId: Long): RemovedEntry = db.withTransaction {
        val row = checkNotNull(entryDao.get(entryId)) { "No entry $entryId" }
        entryDao.delete(entryId)
        reindex(row.setlistId)
        RemovedEntry(row)
    }

    /** Re-inserts the removed row (same id and overrides) at its old position, shifting later entries. */
    suspend fun restoreEntry(removed: RemovedEntry) = db.withTransaction {
        insertEntryAt(removed.row)
    }

    private suspend fun insertEntryAt(row: EntryEntity) {
        // A double-tapped Undo must not insert twice.
        if (entryDao.get(row.id) != null) return
        // The setlist or song may have been deleted since the undo snackbar appeared.
        if (setlistDao.exists(row.setlistId) == 0 || songDao.get(row.songId) == null) return
        val ordered = entryDao.forSetlist(row.setlistId).toMutableList()
        val at = row.position.coerceIn(0, ordered.size)
        ordered.forEachIndexed { index, entry ->
            val target = if (index >= at) index + 1 else index
            if (entry.position != target) entryDao.setPosition(entry.id, target)
        }
        entryDao.insert(row.copy(position = at))
    }

    suspend fun saveToSetlist(entryId: Long, rhythm: Rhythm, notes: String?) = db.withTransaction {
        val entry = entryDao.get(entryId) ?: return@withTransaction
        val song = songDao.get(entry.songId) ?: return@withTransaction
        val rhythmDiffers = rhythm != song.rhythm()
        val notesDiffer = notes.normalized() != song.notes.normalized()
        entryDao.update(
            entry.copy(
                ovTempo = rhythm.tempo.takeIf { rhythmDiffers },
                ovTsNum = rhythm.timeSignature.numerator.takeIf { rhythmDiffers },
                ovTsDen = rhythm.timeSignature.denominator.takeIf { rhythmDiffers },
                ovPatDen = rhythm.pattern.noteDenominator.takeIf { rhythmDiffers },
                ovPatDotted = rhythm.pattern.dotted.takeIf { rhythmDiffers },
                notesOverridden = notesDiffer,
                ovNotes = notes.takeIf { notesDiffer },
            ),
        )
    }

    /** Updates the library song only; existing overrides in any setlist stay as they are. */
    suspend fun saveToLibrary(entryId: Long, rhythm: Rhythm, notes: String?) = db.withTransaction {
        val entry = entryDao.get(entryId) ?: return@withTransaction
        val song = songDao.get(entry.songId) ?: return@withTransaction
        songDao.update(
            song.copy(
                tempo = rhythm.tempo,
                tsNum = rhythm.timeSignature.numerator,
                tsDen = rhythm.timeSignature.denominator,
                patDen = rhythm.pattern.noteDenominator,
                patDotted = rhythm.pattern.dotted,
                notes = notes,
            ),
        )
    }

    suspend fun clearRhythmOverride(entryId: Long) {
        val entry = entryDao.get(entryId) ?: return
        entryDao.update(
            entry.copy(ovTempo = null, ovTsNum = null, ovTsDen = null, ovPatDen = null, ovPatDotted = null),
        )
    }

    suspend fun clearNotesOverride(entryId: Long) {
        val entry = entryDao.get(entryId) ?: return
        entryDao.update(entry.copy(notesOverridden = false, ovNotes = null))
    }

    // Backup

    suspend fun snapshot(): BackupSnapshot = db.withTransaction {
        BackupSnapshot(
            songs = songDao.all().map {
                BackupSong(
                    it.id, it.title, it.artist, it.tempo, it.tsNum, it.tsDen,
                    it.patDen, it.patDotted, it.notes, it.createdAt,
                )
            },
            setlists = setlistDao.all().map { BackupSetlist(it.id, it.name, it.lastPlayedAt) },
            entries = entryDao.all().map {
                BackupEntry(
                    it.id, it.setlistId, it.songId, it.position, it.ovTempo, it.ovTsNum, it.ovTsDen,
                    it.ovPatDen, it.ovPatDotted, it.notesOverridden, it.ovNotes,
                )
            },
        )
    }

    /** Replaces everything in one transaction; a malformed snapshot rolls back and leaves data intact. */
    suspend fun replaceAll(snapshot: BackupSnapshot) = db.withTransaction {
        entryDao.deleteAll()
        setlistDao.deleteAll()
        songDao.deleteAll()
        songDao.insertAll(
            snapshot.songs.map {
                SongEntity(
                    it.id, it.title, it.artist, it.tempo, it.tsNum, it.tsDen,
                    it.patDen, it.patDotted, it.notes, it.createdAt, keyFor(it.title, it.artist),
                )
            },
        )
        setlistDao.insertAll(snapshot.setlists.map { SetlistEntity(it.id, it.name, it.lastPlayedAt) })
        entryDao.insertAll(
            snapshot.entries.map {
                EntryEntity(
                    it.id, it.setlistId, it.songId, it.position, it.ovTempo, it.ovTsNum, it.ovTsDen,
                    it.ovPatDen, it.ovPatDotted, it.notesOverridden, it.ovNotes,
                )
            },
        )
    }

    private suspend fun reindex(setlistId: Long) {
        entryDao.forSetlist(setlistId).forEachIndexed { index, entry ->
            if (entry.position != index) entryDao.setPosition(entry.id, index)
        }
    }
}
