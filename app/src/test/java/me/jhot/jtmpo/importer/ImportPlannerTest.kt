package me.jhot.jtmpo.importer

import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlannerTest {
    private val sample by lazy { parseTempoExport(sampleBytes()).getOrThrow() }

    private fun fs(title: String, tempo: Int = 100, beats: Int = 4, sub: Int = 2) = FztSong(title, tempo, beats, sub, 1)
    private fun lists(vararg songs: FztSong) = listOf(FztSetlist("L", songs.toList()))
    private val r100 = Rhythm(100, TimeSignature(4, 4), ClickPattern(8))

    private fun existing(id: Long, title: String, artist: String?, rhythm: Rhythm = r100) =
        Song(id, title, artist, rhythm, null, 0L)

    @Test
    fun importingOneSetlistYieldsItsSongsAndNoConflicts() {
        val friday = sample.indexOfFirst { it.title == "Friday Night" }
        val plan = planImport(sample, listOf(friday), ImportMode.SONGS_ONLY, emptyList())
        assertEquals(8, plan.newSongs.size)
        assertTrue(plan.conflicts.isEmpty())
        assertTrue(plan.reused.isEmpty())
        assertNull(plan.setlist)
    }

    @Test
    fun importingAllSetlistsYieldsTwelveUniqueSongsAndTwoConflicts() {
        val plan = planImport(sample, sample.indices.toList(), ImportMode.SONGS_ONLY, emptyList())
        assertEquals(12, plan.newSongs.size)
        assertEquals(listOf("Born to Be Wild", "Hey Joe"), plan.conflicts.map { it.title }.sorted())
        plan.conflicts.forEach { assertTrue(it.kept != it.other) }
    }

    @Test
    fun setlistModeKeepsFileOrderAndDuplicatesWithOverridesOnlyWhereDifferent() {
        val plan = planImport(
            lists(fs("A"), fs("B"), fs("A", tempo = 120), fs("A")),
            listOf(0), ImportMode.SONGS_AND_SETLIST, emptyList(),
        )
        assertEquals(listOf("A", "B"), plan.newSongs.map { it.title })
        assertEquals(listOf("A", "B", "A", "A"), plan.setlist!!.map { it.title })
        assertEquals(listOf(null, null, r100.copy(tempo = 120), null), plan.setlist!!.map { it.rhythmOverride })
        assertEquals(listOf(ImportConflict("A", r100, r100.copy(tempo = 120))), plan.conflicts)
    }

    @Test
    fun firstCopyWinsForLibraryValues() {
        val plan = planImport(lists(fs("A", tempo = 90), fs("A", tempo = 120)), listOf(0), ImportMode.SONGS_ONLY, emptyList())
        assertEquals(90, plan.newSongs.single().rhythm.tempo)
    }

    @Test
    fun existingSongWithSameTitleIsReused() {
        val plan = planImport(
            lists(fs(" my song ")), listOf(0), ImportMode.SONGS_AND_SETLIST,
            listOf(existing(7, "My Song", null)),
        )
        assertTrue(plan.newSongs.isEmpty())
        assertEquals(7L, plan.reused.single().existingId)
        assertEquals("my song", plan.setlist!!.single().title.lowercase())
        assertNull(plan.setlist!!.single().rhythmOverride)
    }

    @Test
    fun reusedSongWithDifferentRhythmBecomesConflictAndOverride() {
        val plan = planImport(
            lists(fs("A", tempo = 130)), listOf(0), ImportMode.SONGS_AND_SETLIST,
            listOf(existing(7, "A", null)),
        )
        assertEquals(listOf(ImportConflict("A", r100, r100.copy(tempo = 130))), plan.conflicts)
        assertEquals(r100.copy(tempo = 130), plan.setlist!!.single().rhythmOverride)
    }

    @Test
    fun songWithDifferentArtistIsNotMatched() {
        val plan = planImport(lists(fs("A")), listOf(0), ImportMode.SONGS_ONLY, listOf(existing(7, "A", "Some Artist")))
        assertEquals(1, plan.newSongs.size)
        assertTrue(plan.reused.isEmpty())
    }

    @Test
    fun importingTwiceAddsNoDuplicates() {
        val first = planImport(sample, sample.indices.toList(), ImportMode.SONGS_ONLY, emptyList())
        val saved = first.newSongs.mapIndexed { i, s -> existing(i + 1L, s.title, null, s.rhythm) }
        val second = planImport(sample, sample.indices.toList(), ImportMode.SONGS_ONLY, saved)
        assertEquals(0, second.newSongs.size)
        assertEquals(12, second.reused.size)
        assertNull(second.setlist)
        val third = planImport(sample, listOf(0), ImportMode.SONGS_AND_SETLIST, saved)
        assertNotNull(third.setlist)
        assertEquals(sample[0].songs.size, third.setlist!!.size)
        assertEquals(0, third.newSongs.size)
    }
}
