package me.jhot.jtmpo.ui.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.importer.ImportMode
import me.jhot.jtmpo.importer.parseTempoExport
import me.jhot.jtmpo.importer.readAtMost
import me.jhot.jtmpo.importer.sampleBytes
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var vm: ImportViewModel
    private val sample = sampleBytes()

    /** The preview needs the library's first emission from Room, which arrives on another thread. */
    private suspend fun loaded(): ImportUi = withTimeout(5_000) { vm.ui.first { it.preview != null } }

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), JtmpoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = Repository(db)
        vm = vms.track(ImportViewModel(repo))
    }

    @After
    fun tearDown() {
        vms.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun badFileShowsErrorAndChangesNothing() = runBlocking {
        vm.load { "nope".toByteArray() }
        assertEquals(ImportError.UNREADABLE, withTimeout(5_000) { vm.ui.first { it.error != null } }.error)
        assertTrue(vm.ui.value.setlists.isEmpty())
        vm.dismissError()
        withTimeout(5_000) { vm.ui.first { it.error == null } }
        vm.load { error("io") }
        assertEquals(ImportError.UNREADABLE, withTimeout(5_000) { vm.ui.first { it.error != null } }.error)
        assertTrue(repo.snapshot().songs.isEmpty())
    }

    @Test
    fun oversizedFileIsUnreadableAndChangesNothing() = runBlocking {
        vm.load { java.io.ByteArrayInputStream(ByteArray(me.jhot.jtmpo.importer.MAX_FZT_BYTES + 1)).readAtMost() }
        assertEquals(ImportError.UNREADABLE, withTimeout(5_000) { vm.ui.first { it.error != null } }.error)
        assertTrue(repo.snapshot().songs.isEmpty())
    }

    @Test
    fun defaultsToLargestSetlistAndConfirmImportsIt() = runBlocking {
        vm.load { sample }
        val ui = loaded()
        val largest = ui.setlists.indices.maxByOrNull { ui.setlists[it].songs.size }
        assertEquals(setOf(largest), ui.selected)
        assertNotNull(ui.preview)
        vm.confirm()
        withTimeout(5_000) { vm.ui.first { !it.hasFile } }
        assertEquals(ui.preview!!.newSongs.size, repo.snapshot().songs.size)
        assertTrue(repo.snapshot().setlists.isEmpty())
        assertTrue(vm.ui.value.setlists.isEmpty())
    }

    @Test
    fun setlistModeIsSingleChoiceAndCreatesTheSetlist() = runBlocking {
        vm.load { sample }
        loaded()
        vm.ui.value.setlists.indices.filter { it !in vm.ui.value.selected }.forEach(vm::toggle)
        assertEquals(3, vm.ui.value.selected.size)
        vm.setMode(ImportMode.SONGS_AND_SETLIST)
        assertEquals(1, vm.ui.value.selected.size)
        val other = vm.ui.value.setlists.indices.first { it !in vm.ui.value.selected }
        vm.toggle(other)
        assertEquals(setOf(other), vm.ui.value.selected)
        vm.confirm()
        withTimeout(5_000) { vm.ui.first { !it.hasFile } }
        val snap = repo.snapshot()
        assertEquals(1, snap.setlists.size)
        assertEquals(vm.ui.value.setlists.size, 0)
        assertNull(vm.ui.value.error)
        val chosen = parseTempoExport(sample).getOrThrow()[other]
        assertEquals(chosen.songs.size, snap.entries.size)
        assertEquals(chosen.title, snap.setlists.single().name)
    }

    @Test
    fun singleSetlistFileIsSelectedByDefaultAndImports() = runBlocking {
        val xml = me.jhot.jtmpo.importer.slistXml(
            "Gig",
            """<dict><key>title</key><string>A</string><key>tempo</key><integer>100</integer><key>beatCode</key><integer>1</integer>
            <key>numBeats</key><integer>4</integer><key>numSubBeats</key><integer>2</integer></dict>""",
        )
        vm.load { xml.toByteArray() }
        val ui = loaded()
        assertEquals(1, ui.setlists.size)
        assertEquals(setOf(0), ui.selected)
        assertEquals(listOf("A"), ui.preview!!.newSongs.map { it.title })
        vm.confirm()
        withTimeout(5_000) { vm.ui.first { !it.hasFile } }
        assertEquals(listOf("A"), repo.snapshot().songs.map { it.title })
    }

    @Test
    fun aFileWhoseSetlistsHaveNoSongsIsEmpty() = runBlocking {
        vm.load { me.jhot.jtmpo.importer.slistXml("Gig").toByteArray() }
        assertEquals(ImportError.EMPTY, withTimeout(5_000) { vm.ui.first { it.error != null } }.error)
        assertTrue(vm.ui.value.setlists.isEmpty())
        assertTrue(repo.snapshot().songs.isEmpty())
    }
}
