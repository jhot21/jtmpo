package me.jhot.jtmpo.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.practice.PracticeSettings
import me.jhot.jtmpo.practice.RampDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsStorePracticeTest {
    private fun newStore(): Pair<SettingsStore, File> {
        val file = File.createTempFile("practice-settings", ".preferences_pb").also { it.delete() }
        val store = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
        return store to file
    }

    @Test
    fun defaultsOnAFreshStore() = runBlocking {
        val (store, _) = newStore()
        assertEquals(PracticeSettings(), withTimeout(5_000) { store.practice.first() })
    }

    @Test
    fun settingsRoundTrip() = runBlocking {
        val (store, _) = newStore()
        val value = PracticeSettings(
            startTempo = 92,
            timeSignature = TimeSignature(6, 8),
            pattern = ClickPattern(4, dotted = true),
            direction = RampDirection.Up,
            stepBpm = 7,
            everyBars = 4,
            flipped = true,
        )
        store.setPractice(value)
        assertEquals(value, withTimeout(5_000) { store.practice.first() })
    }

    @Test
    fun corruptValuesAreSanitised() = runBlocking {
        // The stored key names are the store's own; declaring them here keeps the production keys private.
        val file = File.createTempFile("practice-settings", ".preferences_pb").also { it.delete() }
        val raw = PreferenceDataStoreFactory.create(produceFile = { file })
        raw.edit {
            it[intPreferencesKey("practice_start_tempo")] = -3
            it[intPreferencesKey("practice_ts_den")] = 3
            it[intPreferencesKey("practice_step")] = 0
        }
        val store = SettingsStore(raw)

        val value = withTimeout(5_000) { store.practice.first() }

        assertEquals(PracticeSettings(startTempo = TempoLimits.MIN, stepBpm = 1), value)
        assertEquals(TimeSignature(4, 4), value.timeSignature)
        assertEquals(ClickPattern(4), value.pattern)
    }
}
