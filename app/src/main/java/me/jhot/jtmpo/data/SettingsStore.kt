package me.jhot.jtmpo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

/** App preferences, edited on the Settings screen. */
class SettingsStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    /** Keep the screen on while the click is playing; on unless the user turned it off. */
    val keepAwake: Flow<Boolean> = store.data.map { it[KEEP_AWAKE] ?: true }

    suspend fun setKeepAwake(value: Boolean) {
        store.edit { it[KEEP_AWAKE] = value }
    }

    /** How early the tempo flash fires before its click, to tune the visual sync per device. */
    val pulseLeadMs: Flow<Int> = store.data.map { it[PULSE_LEAD_MS] ?: DEFAULT_PULSE_LEAD_MS }

    suspend fun setPulseLeadMs(value: Int) {
        store.edit { it[PULSE_LEAD_MS] = value.coerceIn(0, MAX_PULSE_LEAD_MS) }
    }

    /** Show the app rotated 180° (USB-C port on top) for a phone on a music stand; off unless the user turned it on. */
    val flipScreen: Flow<Boolean> = store.data.map { it[FLIP_SCREEN] ?: false }

    suspend fun setFlipScreen(value: Boolean) {
        store.edit { it[FLIP_SCREEN] = value }
    }

    /** Limit the flip to the player screen, so the rest of the app stays upright; on unless the user turned it off. */
    val flipPlayerOnly: Flow<Boolean> = store.data.map { it[FLIP_PLAYER_ONLY] ?: true }

    suspend fun setFlipPlayerOnly(value: Boolean) {
        store.edit { it[FLIP_PLAYER_ONLY] = value }
    }

    companion object {
        const val DEFAULT_PULSE_LEAD_MS = 40
        const val MAX_PULSE_LEAD_MS = 150
        private val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        private val FLIP_SCREEN = booleanPreferencesKey("flip_screen")
        private val FLIP_PLAYER_ONLY = booleanPreferencesKey("flip_player_only")
        private val PULSE_LEAD_MS = intPreferencesKey("pulse_lead_ms")
    }
}
