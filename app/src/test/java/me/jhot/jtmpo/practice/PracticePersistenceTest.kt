package me.jhot.jtmpo.practice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private class SilentOutput : ClickOutput {
    override fun start() {}
    override fun stop() {}
    override fun setClicksPerMinute(cpm: Double) {}
}

@RunWith(RobolectricTestRunner::class)
class PracticePersistenceTest {
    @Test
    fun restoresThenSaves() = runBlocking {
        val file = File.createTempFile("practice-settings", ".preferences_pb").also { it.delete() }
        val settings = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
        val seeded = PracticeSettings(
            startTempo = 76,
            timeSignature = TimeSignature(3, 4),
            pattern = ClickPattern(8),
            direction = RampDirection.Up,
            stepBpm = 4,
            everyBars = 2,
            flipped = true,
        )
        settings.setPractice(seeded)

        val output = SilentOutput()
        val controller = PracticeController(output, PlaybackController(output))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            PracticePersistence(settings, controller, scope).start()

            assertEquals(seeded, withTimeout(5_000) { controller.state.first { it.settings == seeded } }.settings)

            controller.update { it.copy(startTempo = 88, everyBars = 16) }
            val saved = withTimeout(5_000) { settings.practice.first { it.startTempo == 88 } }
            assertEquals(88, saved.startTempo)
            assertEquals(16, saved.everyBars)
            assertEquals(seeded.copy(startTempo = 88, everyBars = 16), saved)
        } finally {
            scope.cancel()
        }
    }
}
