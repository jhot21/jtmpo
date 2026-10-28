package me.jhot.jtmpo.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class NoopOutput : ClickOutput {
    override fun start() {}
    override fun stop() {}
    override fun setClicksPerMinute(cpm: Double) {}
}

@RunWith(RobolectricTestRunner::class)
class PlaybackBindingTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var controller: PlaybackController
    private lateinit var scope: CoroutineScope

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        controller = PlaybackController(NoopOutput())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        PlaybackBinding(repo, controller, scope).start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun await(condition: (PlaybackState) -> Boolean) {
        withTimeout(5_000) { controller.state.first(condition) }
    }

    private suspend fun loaded(vararg tempos: Int): Pair<Long, List<Long>> {
        val setlist = repo.createSetlist("L")
        val songs = tempos.map { repo.createSong("s$it", null, r(it), null) }
        repo.addSongs(setlist, songs)
        val entries = repo.entries(setlist).first { it.size == tempos.size }
        controller.load(setlist, entries, 0)
        return setlist to entries.map { it.entryId }
    }

    @Test
    fun editingTheCurrentEntryReachesTheController() = runBlocking {
        val (_, ids) = loaded(100, 120)
        repo.saveToSetlist(ids[0], r(150), null)
        await { it.current?.rhythm == r(150) }
        assertEquals(true, controller.state.value.current?.rhythmOverridden)
    }

    @Test
    fun removingThePlayingEntryMovesToTheNextPaused() = runBlocking {
        val (_, ids) = loaded(100, 120)
        controller.play()
        repo.removeEntry(ids[0])
        await { it.entries.size == 1 }
        assertEquals(ids[1], controller.state.value.current?.entryId)
        assertFalse(controller.state.value.isPlaying)
    }

    @Test
    fun reorderKeepsTheCurrentByEntryId() = runBlocking {
        val (setlist, ids) = loaded(100, 120, 140)
        controller.select(1)
        repo.moveEntry(setlist, 0, 2)
        await { it.entries.map { e -> e.entryId } == listOf(ids[1], ids[2], ids[0]) }
        assertEquals(ids[1], controller.state.value.current?.entryId)
        assertEquals(0, controller.state.value.currentIndex)
    }

    @Test
    fun switchingSetlistsNeverMixesInTheOldSetlistsEntries() = runBlocking {
        val (first, _) = loaded(100, 120)
        val second = repo.createSetlist("M")
        repo.addSongs(second, listOf(repo.createSong("x", null, r(90), null)))
        val secondEntries = repo.entries(second).first { it.size == 1 }
        repeat(20) {
            controller.load(first, repo.entries(first).first(), 0)
            controller.load(second, secondEntries, 0)
            repo.saveToSetlist(repo.entries(first).first()[0].entryId, r(100 + it), null)
        }
        await { it.setlistId == second && it.entries == secondEntries }
        assertEquals(secondEntries, controller.state.value.entries)
    }
}
