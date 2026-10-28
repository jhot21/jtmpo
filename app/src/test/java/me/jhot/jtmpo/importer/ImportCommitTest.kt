package me.jhot.jtmpo.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportCommitTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private val r100 = Rhythm(100, TimeSignature(4, 4), ClickPattern(8))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), JtmpoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = Repository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun fs(title: String, tempo: Int = 100) = FztSong(title, tempo, 4, 2, 1)

    @Test
    fun commitCreatesExactlyThePreviewedSongsSetlistAndOverrides() = runBlocking {
        val reusedId = repo.createSong("Old", null, r100, "keep me")
        val plan = planImport(
            listOf(FztSetlist("L", listOf(fs("A"), fs("Old", 120), fs("A"), fs("A", 90)))),
            listOf(0), ImportMode.SONGS_AND_SETLIST, repo.songs().first(),
        )
        repo.commitImport(plan, "Imported")

        val snap = repo.snapshot()
        assertEquals(listOf("Old", "A"), snap.songs.map { it.title })
        assertEquals(1, snap.setlists.size)
        assertEquals("Imported", snap.setlists.single().name)
        val entries = snap.entries.sortedBy { it.position }
        assertEquals(4, entries.size)
        assertEquals(listOf(snap.songs[1].id, reusedId, snap.songs[1].id, snap.songs[1].id), entries.map { it.songId })
        assertEquals(listOf(null, 120, null, 90), entries.map { it.ovTempo })
        assertTrue(entries.none { it.notesOverridden })
        assertEquals("keep me", repo.song(reusedId)!!.notes)
    }

    @Test
    fun songsOnlyCommitCreatesNoSetlist() = runBlocking {
        val plan = planImport(listOf(FztSetlist("L", listOf(fs("A")))), listOf(0), ImportMode.SONGS_ONLY, emptyList())
        repo.commitImport(plan, null)
        assertEquals(1, repo.snapshot().songs.size)
        assertTrue(repo.snapshot().setlists.isEmpty())
    }

    @Test
    fun failingCommitLeavesDatabaseUnchanged() = runBlocking {
        repo.createSong("Existing", null, r100, null)
        val before = repo.snapshot()
        val broken = ImportPreview(
            newSongs = listOf(ImportSong("A", r100)),
            reused = emptyList(),
            conflicts = emptyList(),
            setlist = listOf(ImportEntry("A", null), ImportEntry("Missing", null)),
        )
        try {
            repo.commitImport(broken, "X")
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals(before, repo.snapshot())
    }

    @Test
    fun importFromASingleSetlistFileCreatesExactlyThePreviewedSongs() = runBlocking {
        val song = { t: String, tempo: Int -> """<dict><key>title</key><string>$t</string><key>tempo</key><integer>$tempo</integer>
            <key>beatCode</key><integer>1</integer><key>numBeats</key><integer>4</integer><key>numSubBeats</key><integer>2</integer></dict>""" }
        val lists = parseTempoExport(slistXml("Gig", song("A", 100), song("B", 90), song("A", 100)).toByteArray()).getOrThrow()
        assertEquals(1, lists.size)
        val plan = planImport(lists, listOf(0), ImportMode.SONGS_ONLY, repo.songs().first())
        assertEquals(listOf("A", "B"), plan.newSongs.map { it.title })
        repo.commitImport(plan, null)
        assertEquals(plan.newSongs.map { it.title }, repo.snapshot().songs.map { it.title })
        assertTrue(repo.snapshot().setlists.isEmpty())
    }
}
