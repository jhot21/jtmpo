package me.jhot.jtmpo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class SetlistSummaryRow(val id: Long, val name: String, val songCount: Int, val lastPlayedAt: Long?)

/** One joined row: the entry's overrides next to its library song. */
data class EntryRow(
    val entryId: Long,
    val songId: Long,
    val title: String,
    val artist: String?,
    val tempo: Int,
    val tsNum: Int,
    val tsDen: Int,
    val patDen: Int,
    val patDotted: Boolean,
    val libNotes: String?,
    val ovTempo: Int?,
    val ovTsNum: Int?,
    val ovTsDen: Int?,
    val ovPatDen: Int?,
    val ovPatDotted: Boolean?,
    val notesOverridden: Boolean,
    val ovNotes: String?,
)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY id")
    suspend fun all(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: Long): SongEntity?

    /** The row already holding [key], if any; used by the repository's duplicate guard. */
    @Query("SELECT id FROM songs WHERE songKey = :key LIMIT 1")
    suspend fun idForKey(key: String): Long?

    @Insert
    suspend fun insert(song: SongEntity): Long

    @Insert
    suspend fun insertAll(songs: List<SongEntity>)

    @Update
    suspend fun update(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}

@Dao
interface SetlistDao {
    @Query(
        "SELECT l.id AS id, l.name AS name, " +
            "(SELECT COUNT(*) FROM entries e WHERE e.setlistId = l.id) AS songCount, " +
            "l.lastPlayedAt AS lastPlayedAt " +
            "FROM setlists l ORDER BY l.name COLLATE NOCASE",
    )
    fun observeSummaries(): Flow<List<SetlistSummaryRow>>

    @Query("SELECT * FROM setlists ORDER BY id")
    suspend fun all(): List<SetlistEntity>

    @Query("SELECT COUNT(*) FROM setlists WHERE id = :id")
    suspend fun exists(id: Long): Int

    @Query("SELECT * FROM setlists WHERE id = :id")
    suspend fun get(id: Long): SetlistEntity?

    @Insert
    suspend fun insert(setlist: SetlistEntity): Long

    @Insert
    suspend fun insertAll(setlists: List<SetlistEntity>)

    @Query("UPDATE setlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE setlists SET lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("DELETE FROM setlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM setlists")
    suspend fun deleteAll()
}

@Dao
interface EntryDao {
    @Query(
        "SELECT e.id AS entryId, e.songId AS songId, s.title AS title, s.artist AS artist, " +
            "s.tempo AS tempo, s.tsNum AS tsNum, s.tsDen AS tsDen, s.patDen AS patDen, " +
            "s.patDotted AS patDotted, s.notes AS libNotes, " +
            "e.ovTempo AS ovTempo, e.ovTsNum AS ovTsNum, e.ovTsDen AS ovTsDen, " +
            "e.ovPatDen AS ovPatDen, e.ovPatDotted AS ovPatDotted, " +
            "e.notesOverridden AS notesOverridden, e.ovNotes AS ovNotes " +
            "FROM entries e JOIN songs s ON s.id = e.songId " +
            "WHERE e.setlistId = :setlistId ORDER BY e.position",
    )
    fun observeRows(setlistId: Long): Flow<List<EntryRow>>

    @Query(
        "SELECT e.id AS entryId, e.songId AS songId, s.title AS title, s.artist AS artist, " +
            "s.tempo AS tempo, s.tsNum AS tsNum, s.tsDen AS tsDen, s.patDen AS patDen, " +
            "s.patDotted AS patDotted, s.notes AS libNotes, " +
            "e.ovTempo AS ovTempo, e.ovTsNum AS ovTsNum, e.ovTsDen AS ovTsDen, " +
            "e.ovPatDen AS ovPatDen, e.ovPatDotted AS ovPatDotted, " +
            "e.notesOverridden AS notesOverridden, e.ovNotes AS ovNotes " +
            "FROM entries e JOIN songs s ON s.id = e.songId WHERE e.id = :entryId",
    )
    suspend fun row(entryId: Long): EntryRow?

    @Query("SELECT * FROM entries ORDER BY setlistId, position")
    suspend fun all(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: Long): EntryEntity?

    @Query("SELECT * FROM entries WHERE setlistId = :setlistId ORDER BY position")
    suspend fun forSetlist(setlistId: Long): List<EntryEntity>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM entries WHERE setlistId = :setlistId")
    suspend fun nextPosition(setlistId: Long): Int

    @Query("SELECT * FROM entries WHERE songId = :songId ORDER BY setlistId, position")
    suspend fun forSong(songId: Long): List<EntryEntity>

    @Query("SELECT DISTINCT setlistId FROM entries WHERE songId = :songId")
    suspend fun setlistIdsContaining(songId: Long): List<Long>

    @Query(
        "SELECT DISTINCT l.name FROM setlists l JOIN entries e ON e.setlistId = l.id " +
            "WHERE e.songId = :songId ORDER BY l.name COLLATE NOCASE",
    )
    suspend fun setlistNamesContaining(songId: Long): List<String>

    @Insert
    suspend fun insert(entry: EntryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<EntryEntity>)

    @Update
    suspend fun update(entry: EntryEntity)

    @Query("UPDATE entries SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM entries")
    suspend fun deleteAll()
}
