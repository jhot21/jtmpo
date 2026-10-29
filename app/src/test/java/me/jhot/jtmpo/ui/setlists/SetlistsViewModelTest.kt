package me.jhot.jtmpo.ui.setlists

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import me.jhot.jtmpo.playback.PlaybackBinding
import me.jhot.jtmpo.playback.PlaybackController
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class CallLog : ClickOutput {
    val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetlistsViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var output: CallLog
    private lateinit var playback: PlaybackController
    private lateinit var scope: CoroutineScope

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        output = CallLog()
        playback = PlaybackController(output)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        PlaybackBinding(repo, playback, scope).start()
    }

    @After
    fun tearDown() {
        vms.shutdown()
        // Wait for the binding to stop so it cannot touch the database or Main after teardown.
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun deletingTheLoadedSetlistStopsTheClickAndUndoDoesNotRestartIt() = runBlocking {
        val songs = listOf(repo.createSong("Song One", null, r(100), null), repo.createSong("Song Two", null, r(120), null))
        val id = repo.createSetlist("Setlist A")
        repo.addSongs(id, songs)
        val vm = vms.track(SetlistsViewModel(repo))
        playback.load(id, repo.entries(id).first(), 0)
        playback.play()

        vm.delete(id)
        val offer = withTimeout(5_000) { vm.undo.first { it != null } }
        assertEquals("Setlist A", offer?.name)
        withTimeout(5_000) { playback.state.first { !it.isPlaying } }
        assertEquals("stop", output.calls.last())
        output.calls.clear()

        vm.undoDelete()
        vm.undoDelete() // a double tap is harmless
        assertNull(vm.undo.value)
        withTimeout(5_000) { vm.ui.first { it.setlists.singleOrNull()?.name == "Setlist A" } }
        withTimeout(5_000) { playback.state.first { it.entries.size == 2 } }
        assertFalse(playback.state.value.isPlaying)
        assertFalse("start" in output.calls)
        assertEquals(2, repo.entries(id).first().size)
    }

    @Test
    fun dismissedUndoLeavesTheSetlistDeleted() = runBlocking {
        val id = repo.createSetlist("Setlist A")
        val vm = vms.track(SetlistsViewModel(repo))
        vm.delete(id)
        withTimeout(5_000) { vm.undo.first { it != null } }
        vm.dismissUndo()
        vm.undoDelete()
        assertNull(vm.undo.value)
        assertEquals(emptyList<Any>(), repo.setlists().first())
    }

    @Test
    fun shareJsonIsNullForEmptySetlist() = runBlocking {
        val id = repo.createSetlist("Setlist A")
        val vm = vms.track(SetlistsViewModel(repo))
        assertNull(vm.shareJson(id))
        assertNull(vm.shareJson(id + 99))
    }

    @Test
    fun shareJsonDecodesBackToTheSetlist() = runBlocking {
        val songs = listOf(repo.createSong("Song One", null, r(100), null), repo.createSong("Song Two", null, r(120), null))
        val id = repo.createSetlist("Setlist A")
        repo.addSongs(id, songs)
        val vm = vms.track(SetlistsViewModel(repo))
        val (name, json) = vm.shareJson(id)!!
        assertEquals("Setlist A", name)
        val decoded = me.jhot.jtmpo.share.SetlistFileCodec.decode(json).getOrThrow()
        assertEquals("Setlist A", decoded.name)
        assertEquals(listOf("Song One", "Song Two"), decoded.songs.map { it.title })
        assertEquals(2, decoded.entries.size)
    }
}
