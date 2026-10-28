package me.jhot.jtmpo.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private var now = 1_000L

    private fun r(tempo: Int, num: Int = 4, den: Int = 4, pat: Int = 4, dotted: Boolean = false) =
        Rhythm(tempo, TimeSignature(num, den), ClickPattern(pat, dotted))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = Repository(db) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun positions(setlistId: Long): List<Int> = runBlocking {
        repo.snapshot().entries.filter { it.setlistId == setlistId }.sortedBy { it.position }.map { it.position }
    }

    @Test
    fun sameSongTwiceInSetlistKeepsSeparateEntriesAndOverrides() = runBlocking {
        val song = repo.createSong("Song", null, r(100), null)
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(song, song))
        val (a, b) = repo.entries(list).first()
        assertTrue(a.entryId != b.entryId)
        repo.saveToSetlist(a.entryId, r(72), null)
        val after = repo.entries(list).first()
        assertEquals(72, after[0].rhythm.tempo)
        assertTrue(after[0].rhythmOverridden)
        assertEquals(100, after[1].rhythm.tempo)
        assertFalse(after[1].rhythmOverridden)
    }

    @Test
    fun saveToSetlistStoresOnlyDifferingGroups() = runBlocking {
        val song = repo.createSong("Song", null, r(100), "lib notes")
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(song))
        val entry = repo.entries(list).first()[0]
        repo.saveToSetlist(entry.entryId, r(80), "lib notes")
        var e = repo.entries(list).first()[0]
        assertTrue(e.rhythmOverridden)
        assertFalse(e.notesOverridden)
        // saving values equal to the library clears the overrides
        repo.saveToSetlist(entry.entryId, r(100), "lib notes")
        e = repo.entries(list).first()[0]
        assertFalse(e.rhythmOverridden)
        assertFalse(e.notesOverridden)
        // override notes to empty is distinguishable from no override
        repo.saveToSetlist(entry.entryId, r(100), null)
        e = repo.entries(list).first()[0]
        assertTrue(e.notesOverridden)
        assertNull(e.notes)
        assertEquals("lib notes", e.libraryNotes)
    }

    @Test
    fun saveToLibraryLeavesExistingOverridesAlone() = runBlocking {
        val song = repo.createSong("Song", null, r(100), null)
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(song))
        val entry = repo.entries(list).first()[0]
        repo.saveToSetlist(entry.entryId, r(72), null)
        repo.saveToLibrary(entry.entryId, r(74), "new")
        val e = repo.entries(list).first()[0]
        assertEquals(72, e.rhythm.tempo)
        assertEquals(74, e.libraryRhythm.tempo)
        assertEquals("new", e.libraryNotes)
        assertEquals("new", e.notes)
    }

    @Test
    fun clearOverridesResetToLibrary() = runBlocking {
        val song = repo.createSong("Song", null, r(100), "n")
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(song))
        val id = repo.entries(list).first()[0].entryId
        repo.saveToSetlist(id, r(72), "other")
        repo.clearRhythmOverride(id)
        var e = repo.entries(list).first()[0]
        assertFalse(e.rhythmOverridden)
        assertTrue(e.notesOverridden)
        repo.clearNotesOverride(id)
        e = repo.entries(list).first()[0]
        assertFalse(e.notesOverridden)
        assertEquals("n", e.notes)
    }

    @Test
    fun moveEntryReindexesPositions() = runBlocking {
        val ids = listOf("A", "B", "C", "D").map { repo.createSong(it, null, r(100), null) }
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, ids)
        repo.moveEntry(list, 0, 2)
        assertEquals(listOf("B", "C", "A", "D"), repo.entries(list).first().map { it.title })
        repo.moveEntry(list, 3, 0)
        assertEquals(listOf("D", "B", "C", "A"), repo.entries(list).first().map { it.title })
        assertEquals(listOf(0, 1, 2, 3), positions(list))
    }

    @Test
    fun removeThenRestoreEntryReturnsSamePositionAndOverrides() = runBlocking {
        val ids = listOf("A", "B", "C").map { repo.createSong(it, null, r(100), null) }
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, ids)
        val b = repo.entries(list).first()[1]
        repo.saveToSetlist(b.entryId, r(60), "x")
        val removed = repo.removeEntry(b.entryId)
        assertEquals(listOf("A", "C"), repo.entries(list).first().map { it.title })
        assertEquals(listOf(0, 1), positions(list))
        repo.restoreEntry(removed)
        val after = repo.entries(list).first()
        assertEquals(listOf("A", "B", "C"), after.map { it.title })
        assertEquals(b.entryId, after[1].entryId)
        assertEquals(60, after[1].rhythm.tempo)
        assertEquals("x", after[1].notes)
        assertEquals(listOf(0, 1, 2), positions(list))
    }

    @Test
    fun restoreEntryTwiceIsIdempotent() = runBlocking {
        val ids = listOf("A", "B", "C").map { repo.createSong(it, null, r(100), null) }
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, ids)
        val b = repo.entries(list).first()[1]
        repo.saveToSetlist(b.entryId, r(60), "x")
        val removed = repo.removeEntry(b.entryId)
        repo.restoreEntry(removed)
        repo.restoreEntry(removed)
        val after = repo.entries(list).first()
        assertEquals(listOf("A", "B", "C"), after.map { it.title })
        assertEquals(60, after[1].rhythm.tempo)
        assertEquals("x", after[1].notes)
        assertEquals(listOf(0, 1, 2), positions(list))
    }

    @Test
    fun removingFirstAndLastEntryKeepsPositionsContiguous() = runBlocking {
        val ids = listOf("A", "B", "C").map { repo.createSong(it, null, r(100), null) }
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, ids)
        val es = repo.entries(list).first()
        repo.removeEntry(es[0].entryId)
        repo.removeEntry(es[2].entryId)
        assertEquals(listOf("B"), repo.entries(list).first().map { it.title })
        assertEquals(listOf(0), positions(list))
    }

    @Test
    fun deleteSongRemovesItsEntriesAndNamesAffectedSetlistsFirst() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val b = repo.createSong("B", null, r(100), null)
        val one = repo.createSetlist("One")
        val two = repo.createSetlist("Two")
        repo.createSetlist("Three")
        repo.addSongs(one, listOf(a, b, a))
        repo.addSongs(two, listOf(b, a))
        assertEquals(listOf("One", "Two"), repo.setlistNamesContaining(a).sorted())
        repo.deleteSong(a)
        assertEquals(listOf("B"), repo.entries(one).first().map { it.title })
        assertEquals(listOf("B"), repo.entries(two).first().map { it.title })
        assertEquals(listOf(0), positions(one))
        assertEquals(listOf(0), positions(two))
        val snap = repo.snapshot()
        assertEquals(2, snap.entries.size)
        assertTrue(snap.entries.all { it.songId == b })
        assertTrue(repo.setlistNamesContaining(a).isEmpty())
    }

    @Test
    fun deleteSetlistRemovesItsEntriesOnly() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val one = repo.createSetlist("One")
        val two = repo.createSetlist("Two")
        repo.addSongs(one, listOf(a))
        repo.addSongs(two, listOf(a))
        repo.deleteSetlist(one)
        val snap = repo.snapshot()
        assertEquals(listOf(two), snap.setlists.map { it.id })
        assertEquals(1, snap.entries.size)
        assertEquals(1, repo.songs().first().size)
    }

    @Test
    fun sameTitleDifferentArtistAreSeparateSongs() = runBlocking {
        repo.createSong("Yesterday", "Beatles", r(100), null)
        repo.createSong("Yesterday", "Other", r(90), null)
        val songs = repo.songs().first()
        assertEquals(2, songs.size)
        assertEquals(setOf("Beatles", "Other"), songs.map { it.artist }.toSet())
    }

    @Test
    fun updateSongChangesFieldsButNotCreatedAt() = runBlocking {
        now = 5
        val id = repo.createSong("T", null, r(100), null)
        now = 9
        val song = repo.songs().first().single()
        repo.updateSong(song.copy(title = "T2", artist = "Me", rhythm = r(90, 6, 8, 8), notes = "n"))
        val after = repo.songs().first().single()
        assertEquals(id, after.id)
        assertEquals("T2", after.title)
        assertEquals(r(90, 6, 8, 8), after.rhythm)
        assertEquals(5L, after.createdAt)
    }

    @Test
    fun markPlayedSetsLastPlayedAndRecentSortUsesIt() = runBlocking {
        val a = repo.createSetlist("A")
        val b = repo.createSetlist("B")
        val song = repo.createSong("S", null, r(100), null)
        repo.addSongs(a, listOf(song, song))
        assertTrue(repo.setlists().first().all { it.lastPlayedAt == null })
        now = 2_000
        repo.markPlayed(a)
        now = 3_000
        repo.markPlayed(b)
        val recent = repo.setlists().first()
            .sortedWith(compareByDescending<SetlistSummary> { it.lastPlayedAt ?: Long.MIN_VALUE })
        assertEquals(listOf("B", "A"), recent.map { it.name })
        assertEquals(3_000L, recent[0].lastPlayedAt)
        assertEquals(2, recent[1].songCount)
        repo.renameSetlist(a, "A2")
        assertTrue(repo.setlists().first().any { it.name == "A2" })
    }

    @Test
    fun snapshotRoundTripsThroughReplaceAll() = runBlocking {
        val a = repo.createSong("A", "x", r(100), "n")
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(a, a))
        val e = repo.entries(list).first()[1]
        repo.saveToSetlist(e.entryId, r(60, 3, 4, 8), null)
        val snap = repo.snapshot()
        repo.replaceAll(BackupSnapshot(emptyList(), emptyList(), emptyList()))
        assertTrue(repo.songs().first().isEmpty())
        repo.replaceAll(snap)
        assertEquals(snap, repo.snapshot())
        val after = repo.entries(list).first()
        assertEquals(r(60, 3, 4, 8), after[1].rhythm)
        assertTrue(after[1].notesOverridden)
    }

    @Test
    fun badStoredValuesFallBackInsteadOfCrashing() = runBlocking {
        val songId = db.songDao().insert(
            SongEntity(
                title = "Bad", artist = null, tempo = 999, tsNum = 4, tsDen = 5,
                patDen = 4, patDotted = false, notes = null, createdAt = 1,
            ),
        )
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(songId))
        val song = repo.songs().first().single()
        assertEquals(r(300), song.rhythm)
        val id = repo.entries(list).first()[0].entryId
        db.entryDao().update(
            db.entryDao().get(id)!!.copy(
                ovTempo = 5, ovTsNum = 3, ovTsDen = 7, ovPatDen = 8, ovPatDotted = false,
            ),
        )
        val e = repo.entries(list).first()[0]
        assertEquals(r(300), e.libraryRhythm)
        assertEquals(r(TempoLimits.MIN), e.rhythm)
    }

    @Test
    fun restoreSongBringsBackSongAndEntriesAtOriginalPositions() = runBlocking {
        val a = repo.createSong("A", "X", r(100), "n")
        val b = repo.createSong("B", null, r(100), null)
        val one = repo.createSetlist("One")
        val two = repo.createSetlist("Two")
        repo.addSongs(one, listOf(a, b, a))
        repo.addSongs(two, listOf(b, a))
        val target = repo.entries(one).first()[2]
        repo.saveToSetlist(target.entryId, r(70), "mine")
        val songBefore = repo.song(a)
        val oneBefore = repo.entries(one).first()
        val twoBefore = repo.entries(two).first()
        val removed = repo.removeSong(a)
        assertNull(repo.song(a))
        assertEquals(listOf("B"), repo.entries(one).first().map { it.title })
        repo.restoreSong(removed)
        repo.restoreSong(removed) // double tap is harmless
        assertEquals(songBefore, repo.song(a))
        assertEquals(oneBefore, repo.entries(one).first())
        assertEquals(twoBefore, repo.entries(two).first())
        assertEquals(listOf(0, 1, 2), positions(one))
        assertEquals(listOf(0, 1), positions(two))
    }

    @Test
    fun restoreSongSkipsEntriesOfDeletedSetlist() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val list = repo.createSetlist("Gone")
        repo.addSongs(list, listOf(a))
        val removed = repo.removeSong(a)
        repo.deleteSetlist(list)
        repo.restoreSong(removed)
        assertEquals("A", repo.song(a)?.title)
    }

    @Test
    fun removeThenRestoreSetlistBringsBackEverything() = runBlocking {
        val a = repo.createSong("A", null, r(100), "lib")
        val b = repo.createSong("B", null, r(120), null)
        val list = repo.createSetlist("Set A")
        repo.addSongs(list, listOf(a, b, a))
        now = 5_000L
        repo.markPlayed(list)
        val before = repo.entries(list).first()
        repo.saveToSetlist(before[2].entryId, r(72), "gig notes")
        val expected = repo.entries(list).first()

        val removed = repo.removeSetlist(list)
        assertTrue(repo.setlists().first().isEmpty())
        assertTrue(repo.entries(list).first().isEmpty())

        repo.restoreSetlist(removed)
        val summary = repo.setlists().first().single()
        assertEquals(SetlistSummary(list, "Set A", 3, 5_000L), summary)
        assertEquals(expected, repo.entries(list).first())
        assertEquals(listOf(0, 1, 2), positions(list))
    }

    @Test
    fun restoreSetlistTwiceIsIdempotent() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val list = repo.createSetlist("Set A")
        repo.addSongs(list, listOf(a))
        val removed = repo.removeSetlist(list)
        repo.restoreSetlist(removed)
        repo.restoreSetlist(removed)
        assertEquals(1, repo.setlists().first().size)
        assertEquals(1, repo.entries(list).first().size)
    }

    @Test
    fun restoreSetlistDropsEntriesWhoseSongIsGone() = runBlocking {
        val a = repo.createSong("A", null, r(100), null)
        val b = repo.createSong("B", null, r(120), null)
        val c = repo.createSong("C", null, r(140), null)
        val list = repo.createSetlist("Set A")
        repo.addSongs(list, listOf(a, b, c))
        val removed = repo.removeSetlist(list)
        repo.deleteSong(b)
        repo.restoreSetlist(removed)
        assertEquals(listOf("A", "C"), repo.entries(list).first().map { it.title })
        assertEquals(listOf(0, 1), positions(list))
    }
}
