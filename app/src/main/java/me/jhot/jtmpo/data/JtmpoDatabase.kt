package me.jhot.jtmpo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SongEntity::class, SetlistEntity::class, EntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class JtmpoDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun setlistDao(): SetlistDao
    abstract fun entryDao(): EntryDao

    companion object {
        fun create(context: Context): JtmpoDatabase =
            Room.databaseBuilder(context.applicationContext, JtmpoDatabase::class.java, "jtmpo.db").build()
    }
}
