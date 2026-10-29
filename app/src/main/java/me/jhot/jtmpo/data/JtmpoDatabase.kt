package me.jhot.jtmpo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, SetlistEntity::class, EntryEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class JtmpoDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun setlistDao(): SetlistDao
    abstract fun entryDao(): EntryDao

    companion object {
        fun create(context: Context): JtmpoDatabase =
            Room.databaseBuilder(context.applicationContext, JtmpoDatabase::class.java, "jtmpo.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/** A `songs` row as [MIGRATION_1_2] reads it, before keys existed. */
private data class V1SongRow(
    val id: Long,
    val title: String,
    val artist: String?,
    val tempo: Int,
    val tsNum: Int,
    val tsDen: Int,
    val patDen: Int,
    val patDotted: Boolean,
    val notes: String?,
)

/** A v1 song plus the trimmed title/artist and the key computed from them. */
private data class KeyedSong(
    val row: V1SongRow,
    val title: String,
    val artist: String?,
    val key: String,
)

internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The default must match the entity's @ColumnInfo, or Room's first-open validation rejects it.
        db.execSQL("ALTER TABLE songs ADD COLUMN songKey TEXT NOT NULL DEFAULT ''")
        val rows = mutableListOf<V1SongRow>()
        db.query(
            "SELECT id, title, artist, tempo, tsNum, tsDen, patDen, patDotted, notes FROM songs",
        ).use { c ->
            while (c.moveToNext()) {
                rows += V1SongRow(
                    c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
                    c.getInt(5), c.getInt(6), c.getInt(7) != 0, c.getString(8),
                )
            }
        }
        val keyed = rows.map { row ->
            val title = row.title.trim()
            // A blank artist and a missing one are the same song; blank becomes NULL so rows and keys agree.
            val artist = row.artist?.trim()?.ifEmpty { null }
            KeyedSong(row, title, artist, songKey(title, artist))
        }
        keyed.forEach { song ->
            db.execSQL(
                "UPDATE songs SET title = ?, artist = ?, songKey = ? WHERE id = ?",
                arrayOf<Any?>(song.title, song.artist, song.key, song.row.id),
            )
        }
        keyed.groupBy { it.key }.forEach { group ->
            val survivor = group.value.minBy { it.row.id }
            group.value.forEach { duplicate ->
                if (duplicate.row.id != survivor.row.id) {
                    // Repoint before deleting, or the cascade delete eats the entries.
                    repointEntries(db, duplicate.row, survivor.row)
                    db.execSQL("DELETE FROM songs WHERE id = ?", arrayOf<Any?>(duplicate.row.id))
                }
            }
        }
        // Only now are the surviving keys unique, so the unique index can be created.
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_songs_songKey ON songs (songKey)")
    }

    /**
     * Moves every entry of a merged-away song onto the survivor. An entry that already carried an
     * override keeps it; a plain entry keeps what it used to be effective with, so the duplicate's
     * rhythm and notes become that entry's override group when they differed from the survivor's.
     */
    private fun repointEntries(db: SupportSQLiteDatabase, duplicate: V1SongRow, survivor: V1SongRow) {
        val rhythmDiffers = duplicate.tempo != survivor.tempo || duplicate.tsNum != survivor.tsNum ||
            duplicate.tsDen != survivor.tsDen || duplicate.patDen != survivor.patDen ||
            duplicate.patDotted != survivor.patDotted
        val notesDiffers =
            duplicate.notes?.takeIf { it.isNotEmpty() } != survivor.notes?.takeIf { it.isNotEmpty() }
        db.query(
            "SELECT id, ovTempo, notesOverridden FROM entries WHERE songId = ?",
            arrayOf<Any?>(duplicate.id),
        ).use { c ->
            while (c.moveToNext()) {
                val sets = mutableListOf("songId = ?")
                val args = mutableListOf<Any?>(survivor.id)
                if (c.isNull(1) && rhythmDiffers) {
                    sets += "ovTempo = ?, ovTsNum = ?, ovTsDen = ?, ovPatDen = ?, ovPatDotted = ?"
                    args.addAll(
                        listOf(duplicate.tempo, duplicate.tsNum, duplicate.tsDen, duplicate.patDen, duplicate.patDotted),
                    )
                }
                if (c.getInt(2) == 0 && notesDiffers) {
                    sets += "notesOverridden = 1, ovNotes = ?"
                    args += duplicate.notes?.takeIf { it.isNotEmpty() }
                }
                args += c.getLong(0)
                db.execSQL("UPDATE entries SET ${sets.joinToString()} WHERE id = ?", args.toTypedArray())
            }
        }
    }
}
