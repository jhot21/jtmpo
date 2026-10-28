package me.jhot.jtmpo.ui.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private var now = 1_000L

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db) { now }
    }

    @After
    fun tearDown() {
        vms.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun song(title: String, artist: String?, tempo: Int): Long {
        now += 10
        return repo.createSong(title, artist, r(tempo), null)
    }

    private fun titles(vm: LibraryViewModel) = vm.ui.value.songs.map { it.title }

    /** Room emits off the main thread, so wait for the state instead of reading it immediately. */
    private suspend fun await(vm: LibraryViewModel, condition: (LibraryUi) -> Boolean) {
        withTimeout(5_000) { vm.ui.first(condition) }
    }

    @Test
    fun searchMatchesTitleAndArtistCaseInsensitive() = runBlocking {
        song("Landslide", "Fleetwood Mac", 74)
        song("Valerie", "Amy Winehouse", 96)
        song("Song Three", null, 74)
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 3 }
        vm.setQuery("LAND")
        await(vm) { it.query == "LAND" }
        assertEquals(listOf("Landslide"), titles(vm))
        vm.setQuery("winehouse")
        await(vm) { it.query == "winehouse" }
        assertEquals(listOf("Valerie"), titles(vm))
        vm.setQuery("")
        await(vm) { it.query == "" }
        assertEquals(3, vm.ui.value.songs.size)
        assertEquals(3, vm.ui.value.totalCount)
    }

    @Test
    fun sortByTempoAscending() = runBlocking {
        song("B", null, 120)
        song("A", null, 60)
        song("C", null, 90)
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 3 }
        assertEquals(listOf("A", "B", "C"), titles(vm))
        vm.setSort(LibrarySort.TEMPO)
        await(vm) { it.sort == LibrarySort.TEMPO }
        assertEquals(listOf("A", "C", "B"), titles(vm))
    }

    @Test
    fun recentSortUsesCreatedAtDescending() = runBlocking {
        song("First", null, 100)
        song("Second", null, 100)
        song("Third", null, 100)
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 3 }
        vm.setSort(LibrarySort.RECENT)
        await(vm) { it.sort == LibrarySort.RECENT }
        assertEquals(listOf("Third", "Second", "First"), titles(vm))
    }

    @Test
    fun sameTitleDifferentArtistBothListed() = runBlocking {
        song("Landslide", "Smashing Pumpkins", 78)
        song("Landslide", "Fleetwood Mac", 74)
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 2 }
        assertEquals(listOf("Fleetwood Mac", "Smashing Pumpkins"), vm.ui.value.songs.map { it.artist })
    }

    @Test
    fun deleteWarnsWithAffectedSetlistNames() = runBlocking {
        val s = song("Valerie", null, 96)
        val one = repo.createSetlist("Setlist A")
        val two = repo.createSetlist("Fall Fest")
        repo.addSongs(one, listOf(s))
        repo.addSongs(two, listOf(s))
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 1 }
        vm.requestDelete(s)
        await(vm) { it.pendingDelete != null }
        val pending = vm.ui.value.pendingDelete!!
        assertEquals(s, pending.songId)
        assertEquals("Valerie", pending.title)
        assertEquals(listOf("Fall Fest", "Setlist A"), pending.setlistNames)
        // Warning alone deletes nothing.
        assertEquals(1, vm.ui.value.songs.size)
        vm.confirmDelete(s)
        await(vm) { it.undoTitle != null && it.totalCount == 0 }
        assertNull(vm.ui.value.pendingDelete)
        assertTrue(vm.ui.value.songs.isEmpty())
        assertEquals(0, repo.entries(one).first().size)
    }

    @Test
    fun undoDeleteRestoresSongAndItsEntriesWithOverrides() = runBlocking {
        val a = song("A", null, 100)
        val b = song("B", null, 100)
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(a, b, a))
        val before = repo.entries(list).first()
        repo.saveToSetlist(before[2].entryId, r(70), "mine")
        val expected = repo.entries(list).first()
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 2 }
        vm.requestDelete(a)
        await(vm) { it.pendingDelete != null }
        vm.confirmDelete(a)
        await(vm) { it.undoTitle != null && it.totalCount == 1 }
        assertEquals(listOf("B"), repo.entries(list).first().map { it.title })
        assertEquals("A", vm.ui.value.undoTitle)
        vm.undoDelete()
        await(vm) { it.undoTitle == null && it.totalCount == 2 }
        assertNull(vm.ui.value.undoTitle)
        assertEquals(expected, repo.entries(list).first())
        assertEquals(listOf("A", "B"), titles(vm))
    }

    @Test
    fun cancelDeleteClearsWarning() = runBlocking {
        val s = song("A", null, 100)
        val vm = vms.track(LibraryViewModel(repo))
        await(vm) { it.totalCount == 1 }
        vm.requestDelete(s)
        await(vm) { it.pendingDelete != null }
        vm.cancelDelete()
        await(vm) { it.pendingDelete == null }
        assertNull(vm.ui.value.pendingDelete)
        assertEquals(1, vm.ui.value.songs.size)
    }

    @Test
    fun addToSetlistCompletesOnceTheEntryIsStored() = runBlocking {
        val id = song("Song One", null, 100)
        val list = repo.createSetlist("Setlist A")
        val vm = vms.track(LibraryViewModel(repo))
        vm.addToSetlist(list, id).join()
        assertEquals(listOf(id), repo.entries(list).first().map { it.songId })
    }
}
