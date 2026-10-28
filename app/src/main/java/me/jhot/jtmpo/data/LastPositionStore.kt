package me.jhot.jtmpo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.positionDataStore by preferencesDataStore("last_position")

/** The setlist and entry the player was last on, so a restart lands there (paused). */
class LastPositionStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.positionDataStore)

    suspend fun save(setlistId: Long, entryId: Long) {
        store.edit {
            it[SETLIST] = setlistId
            it[ENTRY] = entryId
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    suspend fun load(): Pair<Long, Long>? {
        val prefs = store.data.first()
        val setlist = prefs[SETLIST] ?: return null
        val entry = prefs[ENTRY] ?: return null
        return setlist to entry
    }

    private companion object {
        val SETLIST = longPreferencesKey("setlist_id")
        val ENTRY = longPreferencesKey("entry_id")
    }
}
