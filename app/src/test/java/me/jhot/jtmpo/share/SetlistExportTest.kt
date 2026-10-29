package me.jhot.jtmpo.share

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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetlistExportTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4, false))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db) { 1_000L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exportsLibraryValuesOnceAndEffectiveValuesPerEntry() = runBlocking {
        val a = repo.createSong("Alpha Tune", "Made Up Band", r(155), "lib notes")
        val b = repo.createSong("Beta Tune", null, r(90), null)
        val list = repo.createSetlist("Test Gig")
        repo.addEntries(list, listOf(a to r(150), b to null, a to null))

        val shared = repo.exportSetlist(list)!!

        assertEquals("Test Gig", shared.name)
        assertEquals(
            listOf(
                SharedSong(1, "Alpha Tune", "Made Up Band", r(155), "lib notes"),
                SharedSong(2, "Beta Tune", null, r(90), null),
            ),
            shared.songs,
        )
        assertEquals(
            listOf(
                SharedEntry(1, r(150), "lib notes"),
                SharedEntry(2, r(90), null),
                SharedEntry(1, r(155), "lib notes"),
            ),
            shared.entries,
        )
    }

    @Test
    fun exportsNotesOverride() = runBlocking {
        val a = repo.createSong("Alpha Tune", null, r(120), "lib notes")
        val list = repo.createSetlist("Test Gig")
        repo.addSongs(list, listOf(a))
        val entry = repo.entries(list).first().single()
        repo.saveToSetlist(entry.entryId, r(120), "set notes")

        val shared = repo.exportSetlist(list)!!

        assertEquals("lib notes", shared.songs.single().notes)
        assertEquals("set notes", shared.entries.single().notes)
    }

    @Test
    fun emptyOrMissingSetlistIsNull() = runBlocking {
        val list = repo.createSetlist("Empty")
        assertNull(repo.exportSetlist(list))
        assertNull(repo.exportSetlist(999))
    }

    @Test
    fun exportedFileDecodesWithCodec() = runBlocking {
        val a = repo.createSong("Alpha Tune", "Made Up Band", r(155), "n")
        val list = repo.createSetlist("Test Gig")
        repo.addEntries(list, listOf(a to r(150), a to null))

        val shared = repo.exportSetlist(list)!!

        assertEquals(shared, SetlistFileCodec.decode(SetlistFileCodec.encode(shared)).getOrThrow())
    }
}
