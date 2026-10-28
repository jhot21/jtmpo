package me.jhot.jtmpo.ui.setlists

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
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.PlaybackController
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class RecordingOutput : ClickOutput {
    val calls = mutableListOf<String>()
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetlistDetailViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var playback: PlaybackController
    private lateinit var output: RecordingOutput
    private var now = 1_000L

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db) { now }
        output = RecordingOutput()
        playback = PlaybackController(output)
    }

    @After
    fun tearDown() {
        vms.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun await(vm: SetlistDetailViewModel, n: Int) {
        withTimeout(5_000) { vm.entries.first { it.size == n } }
    }

    private suspend fun setlistWith(vararg songIds: Long): Long {
        val id = repo.createSetlist("Gig")
        repo.addSongs(id, songIds.toList())
        return id
    }

    @Test
    fun emptySetlistCannotPlay() = runBlocking {
        val song = repo.createSong("A", null, r(100), null)
        val id = setlistWith(song)
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 1)
        assertTrue(vm.canPlay)
        vm.removeEntry(vm.entries.value[0].entryId)
        await(vm, 0)
        assertFalse(vm.canPlay)
        vm.play(0)
        vm.playFromStart()
        assertNull(playback.state.value.setlistId)
        assertNull(vm.navigateToPlayer.value)
        assertTrue(output.calls.isEmpty())
    }

    private suspend fun threeSongs(): Long {
        val ids = listOf(100, 120, 140).map { repo.createSong("S$it", null, r(it), null) }
        return setlistWith(*ids.toLongArray())
    }

    @Test
    fun tappingRowOfLoadedSetlistWhilePlayingSwitchesWithoutStopping() = runBlocking {
        val id = threeSongs()
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 3)
        vm.play(0)
        playback.play()
        output.calls.clear()
        vm.play(2)
        assertEquals(2, playback.state.value.currentIndex)
        assertTrue(playback.state.value.isPlaying)
        assertEquals(listOf("cpm:140"), output.calls)
    }

    @Test
    fun tappingRowOfLoadedSetlistWhilePausedOnlyChangesActiveEntry() = runBlocking {
        val id = threeSongs()
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 3)
        vm.play(0)
        output.calls.clear()
        vm.play(1)
        assertEquals(1, playback.state.value.currentIndex)
        assertFalse(playback.state.value.isPlaying)
        assertFalse("start" in output.calls || "stop" in output.calls)
    }

    @Test
    fun playButtonOnLoadedSetlistStartsCurrentEntryWithoutReloading() = runBlocking {
        val id = threeSongs()
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 3)
        vm.play(1)
        vm.playFromStart()
        assertEquals(1, playback.state.value.currentIndex)
        assertTrue(playback.state.value.isPlaying)
    }

    @Test
    fun tappingRowInADifferentSetlistWhilePlayingSwitchesAndKeepsPlaying() = runBlocking {
        val first = threeSongs()
        val second = setlistWith(repo.createSong("X", null, r(90), null), repo.createSong("Y", null, r(150), null))
        val vmA = vms.track(SetlistDetailViewModel(first, repo, playback))
        val vmB = vms.track(SetlistDetailViewModel(second, repo, playback))
        await(vmA, 3)
        await(vmB, 2)
        vmA.play(0)
        playback.play()
        output.calls.clear()
        vmB.play(1)
        assertEquals(second, playback.state.value.setlistId)
        assertEquals(1, playback.state.value.currentIndex)
        assertTrue(playback.state.value.isPlaying)
        assertEquals(listOf("cpm:150"), output.calls)
    }

    @Test
    fun playButtonOnADifferentSetlistWhilePlayingSwitchesAndKeepsPlaying() = runBlocking {
        val first = threeSongs()
        val second = setlistWith(repo.createSong("X", null, r(90), null))
        val vmA = vms.track(SetlistDetailViewModel(first, repo, playback))
        val vmB = vms.track(SetlistDetailViewModel(second, repo, playback))
        await(vmA, 3)
        await(vmB, 1)
        vmA.play(1)
        playback.play()
        output.calls.clear()
        vmB.playFromStart()
        assertEquals(second, playback.state.value.setlistId)
        assertEquals(0, playback.state.value.currentIndex)
        assertTrue(playback.state.value.isPlaying)
        assertEquals(listOf("cpm:90"), output.calls)
    }

    @Test
    fun tappingRowInADifferentSetlistWhilePausedLoadsItPaused() = runBlocking {
        val first = threeSongs()
        val second = threeSongs()
        val vmA = vms.track(SetlistDetailViewModel(first, repo, playback))
        val vmB = vms.track(SetlistDetailViewModel(second, repo, playback))
        await(vmA, 3)
        await(vmB, 3)
        vmA.play(0)
        vmB.play(1)
        assertEquals(second, playback.state.value.setlistId)
        assertEquals(1, playback.state.value.currentIndex)
        assertFalse(playback.state.value.isPlaying)
        assertFalse("start" in output.calls)
    }

    @Test
    fun removingTwoSameTitledEntriesGivesDistinctOffersAndEachUndoRestoresItsOwn() = runBlocking {
        val a = repo.createSong("Same", null, r(100), null)
        val id = setlistWith(a, a)
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 2)
        val (e1, e2) = vm.entries.value.map { it.entryId }
        vm.removeEntry(e1)
        withTimeout(5_000) { vm.undo.first { it?.entryId == e1 } }
        val first = vm.undo.value
        vm.removeEntry(e2)
        withTimeout(5_000) { vm.undo.first { it?.entryId == e2 } }
        assertNotEquals(first, vm.undo.value)
        vm.undoRemove()
        await(vm, 1)
        assertEquals(e2, vm.entries.value.single().entryId)
    }

    @Test
    fun tappingRowStartsFromThatIndexAndSelectsEntryNotSong() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val b = repo.createSong("B", null, r(120), null)
        val id = setlistWith(a, b, a)
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 3)
        assertTrue(vm.canPlay)
        val entries = vm.entries.value
        vm.play(2)
        val state = playback.state.value
        assertEquals(id, state.setlistId)
        assertEquals(2, state.currentIndex)
        assertEquals(entries[2].entryId, state.current?.entryId)
        assertTrue(entries[0].entryId != entries[2].entryId && entries[0].songId == entries[2].songId)
        assertFalse(state.isPlaying)
        assertNotNull(vm.navigateToPlayer.value)
        assertNotNull(withTimeout(5_000) { repo.setlists().first { l -> l.first().lastPlayedAt != null } })
    }

    @Test
    fun removeShowsUndoAndUndoRestoresPosition() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val b = repo.createSong("B", null, r(120), null)
        val c = repo.createSong("C", null, r(140), null)
        val id = setlistWith(a, b, c)
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 3)
        val middle = vm.entries.value[1]
        vm.removeEntry(middle.entryId)
        await(vm, 2)
        // The entries flow can emit before the removal call returns and the offer is published.
        withTimeout(5_000) { vm.undo.first { it != null } }
        assertEquals("B", vm.undo.value?.title)
        vm.undoRemove()
        await(vm, 3)
        assertEquals(listOf("A", "B", "C"), vm.entries.value.map { it.title })
        assertEquals(middle.entryId, vm.entries.value[1].entryId)
        assertNull(vm.undo.value)
    }

    @Test
    fun reorderCallsRepositoryMove() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val b = repo.createSong("B", null, r(120), null)
        val c = repo.createSong("C", null, r(140), null)
        val id = setlistWith(a, b, c)
        val vm = vms.track(SetlistDetailViewModel(id, repo, playback))
        await(vm, 3)
        vm.moveEntry(0, 2)
        withTimeout(5_000) { vm.entries.first { l -> l.map { it.title } == listOf("B", "C", "A") } }
        Unit
    }
}
