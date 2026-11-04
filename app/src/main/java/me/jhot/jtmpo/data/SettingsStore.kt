package me.jhot.jtmpo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.practice.PracticeSettings
import me.jhot.jtmpo.practice.RampDirection

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

    /** The practice metronome's settings, sanitized so a corrupt stored value still reads as a usable one. */
    val practice: Flow<PracticeSettings> = store.data.map { prefs ->
        PracticeSettings(
            startTempo = prefs[PRACTICE_START_TEMPO] ?: PRACTICE_DEFAULTS.startTempo,
            timeSignature = TimeSignature(
                prefs[PRACTICE_TS_NUM] ?: PRACTICE_DEFAULTS.timeSignature.numerator,
                prefs[PRACTICE_TS_DEN] ?: PRACTICE_DEFAULTS.timeSignature.denominator,
            ),
            pattern = ClickPattern(
                prefs[PRACTICE_PATTERN_DEN] ?: PRACTICE_DEFAULTS.pattern.noteDenominator,
                prefs[PRACTICE_PATTERN_DOTTED] ?: PRACTICE_DEFAULTS.pattern.dotted,
            ),
            direction = prefs[PRACTICE_DIRECTION]
                ?.let { stored -> RampDirection.entries.firstOrNull { it.name == stored } }
                ?: PRACTICE_DEFAULTS.direction,
            stepBpm = prefs[PRACTICE_STEP] ?: PRACTICE_DEFAULTS.stepBpm,
            everyBars = prefs[PRACTICE_EVERY] ?: PRACTICE_DEFAULTS.everyBars,
            flipped = prefs[PRACTICE_FLIPPED] ?: PRACTICE_DEFAULTS.flipped,
        ).sanitized()
    }

    suspend fun setPractice(value: PracticeSettings) {
        val clean = value.sanitized()
        store.edit {
            it[PRACTICE_START_TEMPO] = clean.startTempo
            it[PRACTICE_TS_NUM] = clean.timeSignature.numerator
            it[PRACTICE_TS_DEN] = clean.timeSignature.denominator
            it[PRACTICE_PATTERN_DEN] = clean.pattern.noteDenominator
            it[PRACTICE_PATTERN_DOTTED] = clean.pattern.dotted
            it[PRACTICE_DIRECTION] = clean.direction.name
            it[PRACTICE_STEP] = clean.stepBpm
            it[PRACTICE_EVERY] = clean.everyBars
            it[PRACTICE_FLIPPED] = clean.flipped
        }
    }

    companion object {
        const val DEFAULT_PULSE_LEAD_MS = 40
        const val MAX_PULSE_LEAD_MS = 150
        private val PRACTICE_DEFAULTS = PracticeSettings()
        private val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        private val FLIP_SCREEN = booleanPreferencesKey("flip_screen")
        private val FLIP_PLAYER_ONLY = booleanPreferencesKey("flip_player_only")
        private val PULSE_LEAD_MS = intPreferencesKey("pulse_lead_ms")
        private val PRACTICE_START_TEMPO = intPreferencesKey("practice_start_tempo")
        private val PRACTICE_TS_NUM = intPreferencesKey("practice_ts_num")
        private val PRACTICE_TS_DEN = intPreferencesKey("practice_ts_den")
        private val PRACTICE_PATTERN_DEN = intPreferencesKey("practice_pattern_den")
        private val PRACTICE_PATTERN_DOTTED = booleanPreferencesKey("practice_pattern_dotted")
        private val PRACTICE_DIRECTION = stringPreferencesKey("practice_direction")
        private val PRACTICE_STEP = intPreferencesKey("practice_step")
        private val PRACTICE_EVERY = intPreferencesKey("practice_every")
        private val PRACTICE_FLIPPED = booleanPreferencesKey("practice_flipped")
    }
}
