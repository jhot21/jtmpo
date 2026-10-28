package me.jhot.jtmpo.ui.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.LastPositionStore
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.LastPositionTracker
import me.jhot.jtmpo.playback.PlaybackController
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

private class RecordingOutput : ClickOutput {
    val calls = mutableListOf<String>()
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var output: RecordingOutput
    private lateinit var playback: PlaybackController
    private lateinit var scope: CoroutineScope
    private lateinit var positionStore: LastPositionStore
    private lateinit var settings: SettingsStore
    private lateinit var dir: File
    private var n = 0

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        output = RecordingOutput()
        playback = PlaybackController(output)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dir = File(context.cacheDir, "ds-${System.nanoTime()}").also { it.mkdirs() }
        positionStore = LastPositionStore(dataStore("position"))
        settings = SettingsStore(dataStore("settings"))
    }

    @After
    fun tearDown() {
        vms.shutdown()
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        db.close()
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private fun vm() = vms.track(PlayerViewModel(playback, repo, settings))

    /** A setlist "Fall Fest" of three songs at 100/120/140, loaded paused at [start]. */
    private fun loadSetlist(start: Int = 0): Pair<Long, List<Long>> = runBlocking {
        val songs = listOf(100, 120, 140).map { repo.createSong("Song $it", null, r(it), null) }
        val id = repo.createSetlist("Fall Fest")
        repo.addSongs(id, songs)
        val entries = repo.entries(id).first()
        playback.load(id, entries, start)
        id to entries.map { it.entryId }
    }

    private suspend fun <T> eventually(block: suspend () -> T?): T = withTimeout(5_000) {
        while (true) block()?.let { return@withTimeout it } ?: delay(20)
        error("unreachable")
    }

    @Test
    fun togglePlayDelegatesToController() {
        loadSetlist()
        val vm = vm()
        vm.togglePlay()
        assertTrue(playback.state.value.isPlaying)
        assertEquals(listOf("cpm:100", "cpm:100", "start"), output.calls)
        vm.togglePlay()
        assertFalse(playback.state.value.isPlaying)
        assertEquals("stop", output.calls.last())
    }

    @Test
    fun uiShowsTheCurrentSongAndPosition() = runBlocking {
        val (_, entryIds) = loadSetlist(start = 1)
        val ui = withTimeout(5_000) { vm().ui.first { it.setlistName.isNotEmpty() } }
        assertEquals("Song 120", ui.title)
        assertEquals(120, ui.tempo)
        assertEquals("4/4", ui.timeSignature)
        assertEquals("Fall Fest · 2/3", ui.positionText)
        assertEquals(entryIds[1], ui.entryId)
        assertFalse(ui.overridden)
        assertFalse(ui.isPlaying)
    }

    @Test
    fun lastPositionSavedOnSelectAndNeverAutoPlaysOnRestore() = runBlocking {
        val (setlistId, entryIds) = loadSetlist()
        LastPositionTracker(playback, repo, positionStore, scope).start()
        val vm = vm()
        vm.select(2)
        assertEquals(setlistId to entryIds[2], eventually { positionStore.load()?.takeIf { it.second == entryIds[2] } })

        // A fresh process: nothing is loaded, the tracker restores the stored position, paused.
        val output2 = RecordingOutput()
        val playback2 = PlaybackController(output2)
        LastPositionTracker(playback2, repo, positionStore, scope).start()
        eventually { playback2.state.value.takeIf { it.setlistId != null } }
        val s = playback2.state.value
        assertEquals(setlistId, s.setlistId)
        assertEquals(2, s.currentIndex)
        assertFalse(s.isPlaying)
        delay(200)
        assertTrue("restore must be silent, was ${output2.calls}", output2.calls.none { it == "start" })
        assertFalse(playback2.state.value.isPlaying)
    }

    @Test
    fun unloadForgetsTheStoredPositionSoADismissedPlayerStaysGone() = runBlocking {
        val (setlistId, entryIds) = loadSetlist()
        LastPositionTracker(playback, repo, positionStore, scope).start()
        eventually { positionStore.load() }
        playback.unload()
        eventually { positionStore.load().let { if (it == null) Unit else null } }
        assertNull(positionStore.load())
    }

    @Test
    fun restoreDoesNotOverrideAnAlreadyLoadedSetlist() = runBlocking {
        val (setlistId, entryIds) = loadSetlist()
        positionStore.save(setlistId, entryIds[2])
        LastPositionTracker(playback, repo, positionStore, scope).start()
        delay(300)
        assertEquals(0, playback.state.value.currentIndex)
    }

    @Test
    fun restoreWithADeletedSetlistLoadsNothingAndDoesNotCrash() = runBlocking {
        val (setlistId, entryIds) = loadSetlist()
        positionStore.save(setlistId, entryIds[1])
        repo.deleteSetlist(setlistId)
        val fresh = PlaybackController(RecordingOutput())
        LastPositionTracker(fresh, repo, positionStore, scope).start()
        delay(300)
        assertNull(fresh.state.value.setlistId)
    }

    @Test
    fun restoreWithADeletedEntryFallsBackToTheFirstSong() = runBlocking {
        val (setlistId, entryIds) = loadSetlist()
        positionStore.save(setlistId, entryIds[2])
        repo.removeEntry(entryIds[2])
        val fresh = PlaybackController(RecordingOutput())
        LastPositionTracker(fresh, repo, positionStore, scope).start()
        eventually { fresh.state.value.takeIf { it.setlistId != null } }
        assertEquals(0, fresh.state.value.currentIndex)
        assertFalse(fresh.state.value.isPlaying)
    }

    @Test
    fun noStoredPositionMeansNothingLoaded() = runBlocking {
        assertNull(positionStore.load())
    }

    @Test
    fun keepAwakeDefaultsOnWhilePlaying() = runBlocking {
        loadSetlist()
        val vm = vm()
        assertTrue("paused but the player is open: stays awake", vm.ui.first { it.setlistName.isNotEmpty() }.keepScreenOn)
        assertTrue("default is on", vm.ui.first { it.keepAwake }.keepAwake)
        vm.togglePlay()
        assertTrue(withTimeout(5_000) { vm.ui.first { it.isPlaying } }.keepScreenOn)
        settings.setKeepAwake(false)
        assertFalse(withTimeout(5_000) { vm.ui.first { !it.keepAwake } }.keepScreenOn)
        vm.togglePlay()
        settings.setKeepAwake(true)
        assertTrue(withTimeout(5_000) { vm.ui.first { it.keepAwake && !it.isPlaying } }.keepScreenOn)
        assertFalse("off elsewhere when paused", keepScreenOn(isPlaying = false, playerOpen = false, keepAwake = true))
        assertTrue("click keeps it on elsewhere", keepScreenOn(isPlaying = true, playerOpen = false, keepAwake = true))
    }

    @Test
    fun selectWhilePlayingSwitchesRateWithoutStopping() {
        loadSetlist()
        val vm = vm()
        vm.togglePlay()
        vm.select(2)
        assertEquals(listOf("cpm:100", "cpm:100", "start", "cpm:140"), output.calls)
        assertTrue(playback.state.value.isPlaying)
    }

    @Test
    fun selectWhilePausedStaysSilent() {
        loadSetlist()
        val vm = vm()
        vm.select(1)
        assertEquals(1, playback.state.value.currentIndex)
        assertTrue(output.calls.none { it == "start" || it == "stop" })
        assertFalse(playback.state.value.isPlaying)
    }
}
