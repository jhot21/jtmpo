package me.jhot.jtmpo.ui.practice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.patternsFor
import me.jhot.jtmpo.domain.remapPattern
import me.jhot.jtmpo.playback.PlaybackController
import me.jhot.jtmpo.practice.PracticeController
import me.jhot.jtmpo.practice.PracticePersistence
import me.jhot.jtmpo.practice.PracticeSettings
import me.jhot.jtmpo.practice.RampDirection
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Reports clicks the way the engine does, so a test can drive the ramp and the bar count. */
private class ClickingOutput : ClickOutput {
    var running = false
    override var onClickStart: (() -> Unit)? = null

    override fun start() { running = true }
    override fun stop() { running = false }
    override fun setClicksPerMinute(cpm: Double) {}

    fun clicks(n: Int) = repeat(n) { onClickStart?.invoke() }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PracticeViewModelTest {
    private lateinit var output: ClickingOutput
    private lateinit var practice: PracticeController
    private lateinit var settings: SettingsStore
    private lateinit var scope: CoroutineScope
    private lateinit var vm: PracticeViewModel

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        output = ClickingOutput()
        practice = PracticeController(output, PlaybackController(output))
        val file = File.createTempFile("practice-view-model", ".preferences_pb").also { it.delete() }
        settings = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
        // The real persistence path, as AppGraph wires it, so toggleFlip is checked against the store.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        PracticePersistence(settings, practice, scope).start()
        vm = vms.track(PracticeViewModel(practice))
    }

    @After
    fun tearDown() {
        vms.shutdown()
        scope.cancel()
        Dispatchers.resetMain()
    }

    /** 4/4 with 1/4 clicks: four clicks to a bar. */
    private fun rampUp(everyBars: Int = 2) {
        vm.setDirection(RampDirection.Up)
        vm.setStep(5)
        vm.setEveryBars(everyBars)
        vm.nudgeTempo(90)
    }

    @Test
    fun uiCarriesBarAndNext() {
        rampUp(everyBars = 2)
        vm.togglePlay()
        assertTrue(output.running)

        // The first click is bar 1's downbeat, so two bars are eight clicks.
        output.clicks(8)

        val ui = vm.ui.value
        assertEquals(2, ui.bar)
        assertEquals(2, ui.everyBars)
        assertEquals(95, ui.nextTempo)
        assertTrue(ui.rampOn)
        assertFalse(ui.atLimit)
    }

    @Test
    fun uiFlagsAtLimit() {
        vm.setDirection(RampDirection.Up)
        vm.nudgeTempo(300)

        val ui = vm.ui.value
        assertTrue(ui.atLimit)
        assertNull(ui.nextTempo)
        assertTrue(ui.rampOn)
    }

    @Test
    fun rampOffClearsRampOn() {
        vm.setDirection(RampDirection.Up)
        assertTrue(vm.ui.value.rampOn)

        vm.setDirection(RampDirection.Off)

        val ui = vm.ui.value
        assertFalse(ui.rampOn)
        assertNull(ui.nextTempo)
        assertFalse(ui.atLimit)
    }

    @Test
    fun settingPatternKeepsItValidForTheSignature() {
        val sixEight = TimeSignature(6, 8)
        vm.setPattern(ClickPattern(4))

        vm.setSignature(sixEight)

        val ui = vm.ui.value
        assertEquals(sixEight, ui.settings.timeSignature)
        assertEquals(patternsFor(sixEight), ui.patterns)
        assertTrue(ui.settings.pattern in patternsFor(sixEight))
        // Remapped from the old grid, not reset to the first offered pattern.
        assertEquals(remapPattern(ClickPattern(4), TimeSignature(4, 4), sixEight), ui.settings.pattern)
    }

    @Test
    fun toggleFlipPersistsToSettings() = runBlocking {
        assertFalse(vm.ui.value.settings.flipped)

        vm.toggleFlip()

        assertTrue(vm.ui.value.settings.flipped)
        assertTrue(withTimeout(5_000) { settings.practice.first { it.flipped } }.flipped)

        vm.toggleFlip()

        assertFalse(vm.ui.value.settings.flipped)
        assertEquals(PracticeSettings(), withTimeout(5_000) { settings.practice.first { !it.flipped } })
    }
}
