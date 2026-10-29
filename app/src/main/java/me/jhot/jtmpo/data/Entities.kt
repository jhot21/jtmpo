package me.jhot.jtmpo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index(value = ["songKey"], unique = true)],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String?,
    val tempo: Int,
    val tsNum: Int,
    val tsDen: Int,
    val patDen: Int,
    val patDotted: Boolean,
    val notes: String?,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val songKey: String,
)

@Entity(tableName = "setlists")
data class SetlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lastPlayedAt: Long?,
)

/**
 * The ov* rhythm columns are set all-or-none (one rhythm override group). Notes use an explicit
 * flag so "overridden to empty" differs from "no override".
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(SongEntity::class, ["id"], ["songId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SetlistEntity::class, ["id"], ["setlistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("songId"), Index("setlistId", "position")],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val setlistId: Long,
    val songId: Long,
    val position: Int,
    val ovTempo: Int? = null,
    val ovTsNum: Int? = null,
    val ovTsDen: Int? = null,
    val ovPatDen: Int? = null,
    val ovPatDotted: Boolean? = null,
    val notesOverridden: Boolean = false,
    val ovNotes: String? = null,
)
