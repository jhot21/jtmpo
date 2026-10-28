package me.jhot.jtmpo.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.backup.BackupCodec
import me.jhot.jtmpo.data.BackupEntry
import me.jhot.jtmpo.data.BackupSetlist
import me.jhot.jtmpo.data.BackupSnapshot
import me.jhot.jtmpo.data.BackupSong
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.PlaybackBinding
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
    var running = false
    override fun start() { running = true }
    override fun stop() { running = false }
    override fun setClicksPerMinute(cpm: Double) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var output: RecordingOutput
    private lateinit var controller: PlaybackController
    private lateinit var vm: SettingsViewModel
    private lateinit var settings: SettingsStore

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        output = RecordingOutput()
        controller = PlaybackController(output)
        val file = File.createTempFile("settings", ".preferences_pb").also { it.delete() }
        settings = SettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }))
        vm = vms.track(SettingsViewModel(repo, settings, controller))
    }

    @After
    fun tearDown() {
        vms.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    private val backup = BackupSnapshot(
        songs = listOf(BackupSong(7, "Restored", null, 90, 4, 4, 4, false, null, 1L)),
        setlists = listOf(BackupSetlist(3, "From backup", null)),
        entries = listOf(BackupEntry(5, 3, 7, 0, null, null, null, null, null, false, null)),
    )

    private suspend fun seed(): Long {
        val setlist = repo.createSetlist("Existing")
        repo.addSongs(setlist, listOf(repo.createSong("Keep me", null, r(120), null)))
        return setlist
    }

    private suspend fun next() = withTimeout(5_000) { vm.messages.first() }

    @Test
    fun pulseLeadDefaultsTo40AndIsClampedToItsRange() = runBlocking {
        assertEquals(40, settings.pulseLeadMs.first())
        settings.setPulseLeadMs(75)
        assertEquals(75, settings.pulseLeadMs.first())
        assertEquals(75, withTimeout(5_000) { vm.ui.first { it.pulseLeadMs == 75 } }.pulseLeadMs)
        settings.setPulseLeadMs(-10)
        assertEquals(0, settings.pulseLeadMs.first())
        settings.setPulseLeadMs(999)
        assertEquals(SettingsStore.MAX_PULSE_LEAD_MS, settings.pulseLeadMs.first())
    }

    @Test
    fun flipScreenDefaultsToOffAndPlayerOnlyToOn() = runBlocking {
        assertFalse(settings.flipScreen.first())
        assertTrue(settings.flipPlayerOnly.first())
        settings.setFlipScreen(true)
        settings.setFlipPlayerOnly(false)
        val ui = withTimeout(5_000) { vm.ui.first { it.flipScreen && !it.flipPlayerOnly } }
        assertTrue(ui.flipScreen)
        assertFalse(ui.flipPlayerOnly)
    }

    @Test
    fun restoreReplacesAll() = runBlocking {
        seed()
        vm.loadBackup { BackupCodec.encode(backup) }
        assertEquals(SettingsViewModel.PendingRestore(songs = 1, setlists = 1), vm.ui.value.pendingRestore)
        vm.confirmRestore()
        assertEquals(SettingsMessage.RESTORED, next())
        assertEquals(listOf("Restored"), repo.songs().first().map { it.title })
        assertEquals(listOf("From backup"), repo.setlists().first().map { it.name })
        assertNull(vm.ui.value.pendingRestore)
    }

    @Test
    fun restoreFailureLeavesRepositoryUntouched() = runBlocking {
        seed()
        val before = repo.snapshot()

        // A corrupt file never reaches the confirmation dialog.
        vm.loadBackup { "{ definitely not a backup" }
        assertEquals(SettingsMessage.RESTORE_UNREADABLE, next())
        assertNull(vm.ui.value.pendingRestore)
        assertEquals(before, repo.snapshot())

        // A newer file is reported as such.
        vm.loadBackup { BackupCodec.encode(backup).replace("\"version\":1", "\"version\":2") }
        assertEquals(SettingsMessage.RESTORE_NEWER, next())
        assertEquals(before, repo.snapshot())

        // A read failure leaves data untouched too.
        vm.loadBackup { error("disk gone") }
        assertEquals(SettingsMessage.RESTORE_UNREADABLE, next())
        assertEquals(before, repo.snapshot())

        // A replaceAll that fails midway (entry pointing at a missing song) rolls back.
        val broken = backup.copy(entries = listOf(backup.entries[0].copy(songId = 404)))
        assertTrue(runCatching { repo.replaceAll(broken) }.isFailure)
        assertEquals(before, repo.snapshot())
    }

    @Test
    fun cancelRestoreChangesNothing() = runBlocking {
        seed()
        val before = repo.snapshot()
        vm.loadBackup { BackupCodec.encode(backup) }
        vm.cancelRestore()
        assertNull(vm.ui.value.pendingRestore)
        vm.confirmRestore()
        assertEquals(before, repo.snapshot())
    }

    @Test
    fun exportWritesTheEncodedSnapshot() = runBlocking {
        seed()
        var written = ""
        vm.export { written = it }
        assertEquals(SettingsMessage.EXPORTED, next())
        assertEquals(repo.snapshot(), BackupCodec.decode(written).getOrThrow())
        vm.export { error("no space") }
        assertEquals(SettingsMessage.EXPORT_FAILED, next())
    }

    @Test
    fun restoreWhilePlayingStopsTheClickCleanly() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            PlaybackBinding(repo, controller, scope).start()
            val setlist = seed()
            val entries = repo.entries(setlist).first { it.isNotEmpty() }
            controller.load(setlist, entries, 0)
            controller.play()
            assertTrue(output.running)

            vm.loadBackup { BackupCodec.encode(backup) }
            vm.confirmRestore()
            assertEquals(SettingsMessage.RESTORED, next())

            assertFalse(output.running)
            val state = controller.state.value
            assertFalse(state.isPlaying)
            assertNull(state.setlistId)
            assertTrue(state.entries.isEmpty())
            assertNull(state.current)
            // Binding must not resurrect anything for the old setlist id afterwards.
            kotlinx.coroutines.delay(200)
            assertNull(controller.state.value.setlistId)
            assertFalse(controller.state.value.isPlaying)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restoreReusingTheLoadedSetlistIdWhilePlayingStopsAndUnloads() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            PlaybackBinding(repo, controller, scope).start()
            val setlist = seed()
            controller.load(setlist, repo.entries(setlist).first { it.isNotEmpty() }, 0)
            controller.play()
            // The backup's setlist and entry ids collide with what is loaded and playing.
            val songId = repo.songs().first().single().id
            val entryId = repo.entries(setlist).first().single().entryId
            val colliding = BackupSnapshot(
                songs = listOf(BackupSong(songId, "Restored", null, 90, 4, 4, 4, false, null, 1L)),
                setlists = listOf(BackupSetlist(setlist, "From backup", null)),
                entries = listOf(BackupEntry(entryId, setlist, songId, 0, null, null, null, null, null, false, null)),
            )
            vm.loadBackup { BackupCodec.encode(colliding) }
            vm.confirmRestore()
            assertEquals(SettingsMessage.RESTORED, next())
            assertFalse(output.running)
            kotlinx.coroutines.delay(200)
            val state = controller.state.value
            assertNull(state.setlistId)
            assertFalse(state.isPlaying)
            assertTrue(state.entries.isEmpty())
            assertEquals(listOf("From backup"), repo.setlists().first().map { it.name })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restoreIsNotCancelledHalfWay() = runBlocking {
        val setlist = seed()
        controller.load(setlist, repo.entries(setlist).first(), 0)
        controller.play()
        vm.loadBackup { BackupCodec.encode(backup) }
        vm.confirmRestore()
        // The screen goes away while the restore runs.
        vm.viewModelScope.cancel()
        withTimeout(5_000) { repo.songs().first { songs -> songs.map { it.title } == listOf("Restored") } }
        withTimeout(5_000) { controller.state.first { it.setlistId == null } }
        assertFalse(output.running)
    }
}
