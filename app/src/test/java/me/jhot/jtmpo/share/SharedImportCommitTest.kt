package me.jhot.jtmpo.share

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import me.jhot.jtmpo.data.DuplicateSongException
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedImportCommitTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository

    private fun r(tempo: Int, num: Int = 4, den: Int = 4) = Rhythm(tempo, TimeSignature(num, den), ClickPattern(4))
    private fun song(id: Long, title: String, artist: String? = null, tempo: Int = 100, notes: String? = null) =
        SharedSong(id, title, artist, r(tempo), notes)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db) { 1_000L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun commitsNewSongsReusedSongsAndEntriesInOrder() = runBlocking {
        val mine = repo.createSong("Blue Harbor", null, r(90), null)
        val resolved = ResolvedImport(
            updates = emptyList(),
            newSongs = listOf(song(2, "Paper Lanterns")),
            reused = mapOf(1L to mine),
            entries = listOf(
                ResolvedEntry(2, null, null),
                ResolvedEntry(1, r(120), null),
                ResolvedEntry(2, null, null),
            ),
            name = "Friday",
        )
        val id = repo.commitShared(resolved)
        val snap = repo.snapshot()
        assertEquals(2, snap.songs.size)
        val lanterns = snap.songs.single { it.title == "Paper Lanterns" }.id
        assertEquals("Friday", repo.setlistName(id))
        val entries = snap.entries.filter { it.setlistId == id }.sortedBy { it.position }
        assertEquals(listOf(lanterns, mine, lanterns), entries.map { it.songId })
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        assertEquals(listOf(null, 120, null), entries.map { it.ovTempo })
    }

    @Test
    fun sameTitleTwoArtistsEntriesPointAtTheirOwnSongs() = runBlocking {
        val resolved = ResolvedImport(
            emptyList(),
            listOf(song(1, "Echo", "Ann"), song(2, "Echo", "Ben")),
            emptyMap(),
            listOf(ResolvedEntry(2, null, null), ResolvedEntry(1, null, null)),
            "Twins",
        )
        val id = repo.commitShared(resolved)
        val snap = repo.snapshot()
        val byArtist = snap.songs.associate { it.artist to it.id }
        val entries = snap.entries.filter { it.setlistId == id }.sortedBy { it.position }
        assertEquals(listOf(byArtist["Ben"], byArtist["Ann"]), entries.map { it.songId })
    }

    @Test
    fun updateReplacesArtistTempoAndNotes() = runBlocking {
        val mine = repo.createSong("Slow River", null, r(70), "old")
        val incoming = SharedSong(5, "Slow River", "Cora", r(88, 3, 4), "new notes")
        val id = repo.commitShared(
            ResolvedImport(listOf(mine to incoming), emptyList(), mapOf(5L to mine), listOf(ResolvedEntry(5, null, null)), "S"),
        )
        val updated = repo.song(mine)!!
        assertEquals("Cora", updated.artist)
        assertEquals(r(88, 3, 4), updated.rhythm)
        assertEquals("new notes", updated.notes)
        assertEquals(1, repo.snapshot().entries.count { it.setlistId == id })
    }

    @Test
    fun nameCollisionGetsNextSuffixInsideTheTransaction() = runBlocking {
        repo.createSetlist("Set")
        repo.createSetlist("  set (2) ")
        val id = repo.commitShared(ResolvedImport(emptyList(), emptyList(), emptyMap(), emptyList(), "Set"))
        assertEquals("Set (3)", repo.setlistName(id))
    }

    @Test
    fun notesOverrideIsStored() = runBlocking {
        val id = repo.commitShared(
            ResolvedImport(
                emptyList(), listOf(song(1, "Glass Moon", notes = "lib")), emptyMap(),
                listOf(ResolvedEntry(1, null, "stage note"), ResolvedEntry(1, null, null)), "N",
            ),
        )
        val entries = repo.snapshot().entries.filter { it.setlistId == id }.sortedBy { it.position }
        assertTrue(entries[0].notesOverridden)
        assertEquals("stage note", entries[0].ovNotes)
        assertFalse(entries[1].notesOverridden)
        assertNull(entries[1].ovNotes)
    }

    @Test
    fun failureRollsBackEverything() = runBlocking {
        val mine = repo.createSong("Mine", null, r(90), "keep")
        repo.createSong("Taken", "Dee", r(90), null)
        val before = repo.snapshot()
        val resolved = ResolvedImport(
            updates = listOf(mine to SharedSong(9, "Mine", "Zed", r(150), "changed")),
            newSongs = listOf(song(1, "Fresh One"), song(2, "Taken", "Dee")),
            reused = mapOf(9L to mine),
            entries = listOf(ResolvedEntry(1, null, null)),
            name = "Doomed",
        )
        try {
            repo.commitShared(resolved)
            fail("expected DuplicateSongException")
        } catch (_: DuplicateSongException) {
        }
        assertEquals(before, repo.snapshot())
    }

    @Test
    fun importingTwiceReusesSongsAndAddsSecondSetlist() = runBlocking {
        val first = repo.commitShared(
            ResolvedImport(emptyList(), listOf(song(1, "Once")), emptyMap(), listOf(ResolvedEntry(1, null, null)), "Gig"),
        )
        val songId = repo.snapshot().songs.single().id
        val second = repo.commitShared(
            ResolvedImport(emptyList(), emptyList(), mapOf(1L to songId), listOf(ResolvedEntry(1, null, null)), "Gig"),
        )
        val snap = repo.snapshot()
        assertEquals(1, snap.songs.size)
        assertEquals("Gig", repo.setlistName(first))
        assertEquals("Gig (2)", repo.setlistName(second))
        assertEquals(2, snap.entries.size)
    }
}
