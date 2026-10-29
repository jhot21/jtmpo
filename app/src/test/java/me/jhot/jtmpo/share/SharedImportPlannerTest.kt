package me.jhot.jtmpo.share

import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImportPlannerTest {
    private fun r(tempo: Int, num: Int = 4) = Rhythm(tempo, TimeSignature(num, 4), ClickPattern(4, false))

    private fun lib(id: Long, title: String, artist: String?, rhythm: Rhythm = r(120), notes: String? = null) =
        Song(id, title, artist, rhythm, notes, createdAt = 0L)

    private fun fileSong(id: Long, title: String, artist: String?, rhythm: Rhythm = r(120), notes: String? = null) =
        SharedSong(id, title, artist, rhythm, notes)

    /** A file whose entries play each song once at its library values, unless [entries] says otherwise. */
    private fun file(songs: List<SharedSong>, entries: List<SharedEntry> = songs.map { SharedEntry(it.localId, it.rhythm, it.notes) }) =
        SharedSetlist("Made Up Set", songs, entries)

    @Test
    fun exactMatchIsReusedWithoutOverride() {
        val plan = planSharedImport(file(listOf(fileSong(1, "Paper Lanterns", "The Inventions"))), listOf(lib(7, "paper lanterns ", "the inventions")))

        assertEquals(Match.EXACT, plan.songs.single().match)
        assertEquals(7L, plan.songs.single().candidate?.id)
        val resolved = resolveSharedImport(plan, emptyMap())
        assertEquals(mapOf(1L to 7L), resolved.reused)
        assertEquals(emptyList<SharedSong>(), resolved.newSongs)
        assertEquals(emptyList<Pair<Long, SharedSong>>(), resolved.updates)
        assertEquals(listOf(ResolvedEntry(1, null, null)), resolved.entries)
        assertEquals("Made Up Set", resolved.name)
    }

    @Test
    fun differentTempoBecomesEntryOverride() {
        val song = fileSong(1, "Paper Lanterns", "The Inventions", r(120))
        val plan = planSharedImport(file(listOf(song), listOf(SharedEntry(1, r(132), null))), listOf(lib(7, "Paper Lanterns", "The Inventions", r(120))))

        assertEquals(listOf(ResolvedEntry(1, r(132), null)), resolveSharedImport(plan, emptyMap()).entries)
    }

    @Test
    fun anyRhythmDifferenceOverridesTheWholeRhythm() {
        val song = fileSong(1, "Paper Lanterns", null, r(120))
        val plan = planSharedImport(file(listOf(song), listOf(SharedEntry(1, r(120, num = 3), null))), listOf(lib(7, "Paper Lanterns", null, r(120))))

        assertEquals(listOf(ResolvedEntry(1, r(120, num = 3), null)), resolveSharedImport(plan, emptyMap()).entries)
    }

    @Test
    fun blankFileNotesNeverOverride() {
        val song = fileSong(1, "Paper Lanterns", null)
        val plan = planSharedImport(
            file(listOf(song), listOf(SharedEntry(1, r(120), null), SharedEntry(1, r(120), "  "))),
            listOf(lib(7, "Paper Lanterns", null, notes = "my own notes")),
        )

        assertEquals(listOf(ResolvedEntry(1, null, null), ResolvedEntry(1, null, null)), resolveSharedImport(plan, emptyMap()).entries)
    }

    @Test
    fun fileNotesOverTheirEmptyNotesOverride() {
        val song = fileSong(1, "Paper Lanterns", null)
        val plan = planSharedImport(
            file(listOf(song), listOf(SharedEntry(1, r(120), "Soft start"), SharedEntry(1, r(120), "Same"))),
            listOf(lib(7, "Paper Lanterns", null, notes = "Same")),
        )
        val emptyNotes = planSharedImport(file(listOf(song), listOf(SharedEntry(1, r(120), "Soft start"))), listOf(lib(7, "Paper Lanterns", null, notes = null)))

        assertEquals(listOf(ResolvedEntry(1, null, "Soft start"), ResolvedEntry(1, null, null)), resolveSharedImport(plan, emptyMap()).entries)
        assertEquals(listOf(ResolvedEntry(1, null, "Soft start")), resolveSharedImport(emptyNotes, emptyMap()).entries)
    }

    @Test
    fun sameTitleDifferentArtistsAreBothNewWhenRecipientHasNeither() {
        val a = fileSong(1, "Gratitude", "Band A")
        val b = fileSong(2, "Gratitude", "Band B")
        val plan = planSharedImport(file(listOf(a, b)), listOf(lib(7, "Other Tune", null)))

        assertEquals(listOf(Match.NEW, Match.NEW), plan.songs.map { it.match })
        val resolved = resolveSharedImport(plan, emptyMap())
        assertEquals(listOf(a, b), resolved.newSongs)
        assertEquals(listOf(ResolvedEntry(1, null, null), ResolvedEntry(2, null, null)), resolved.entries)
    }

    @Test
    fun bothArtistsDifferIsNew() {
        val plan = planSharedImport(file(listOf(fileSong(1, "Gratitude", "Band A"))), listOf(lib(7, "Gratitude", "Band C")))

        assertEquals(Match.NEW, plan.songs.single().match)
        assertNull(plan.songs.single().candidate)
        assertEquals(0, plan.conflicts.size)
    }

    @Test
    fun artistlessLibrarySongGivesConflictA() {
        val plan = planSharedImport(
            file(listOf(fileSong(1, "Gratitude", "Band A"))),
            listOf(lib(3, "Gratitude", "Band C"), lib(7, "gratitude", null)),
        )

        assertEquals(Match.CONFLICT_A, plan.songs.single().match)
        assertEquals(7L, plan.songs.single().candidate?.id)
        assertEquals(plan.songs, plan.conflicts)
    }

    @Test
    fun artistlessFileSongGivesConflictBWithLowestId() {
        val plan = planSharedImport(
            file(listOf(fileSong(1, "Gratitude", null))),
            listOf(lib(9, "Gratitude", "Band C"), lib(4, "Gratitude", "Band D"), lib(2, "Other Tune", "Band E")),
        )

        assertEquals(Match.CONFLICT_B, plan.songs.single().match)
        assertEquals(4L, plan.songs.single().candidate?.id)
    }

    @Test
    fun conflictBSkipsClaimedLibrarySongs() {
        // "Gratitude"/Band D is claimed by an exact match, so the artist-less file song falls to the next lowest id.
        val plan = planSharedImport(
            file(listOf(fileSong(1, "Gratitude", null), fileSong(2, "Gratitude", "Band D"))),
            listOf(lib(9, "Gratitude", "Band C"), lib(4, "Gratitude", "Band D")),
        )

        assertEquals(listOf(Match.CONFLICT_B, Match.EXACT), plan.songs.map { it.match })
        assertEquals(listOf(9L, 4L), plan.songs.map { it.candidate?.id })
    }

    @Test
    fun twoFileSongsCompeteForOneLibrarySongFirstWins() {
        val plan = planSharedImport(
            file(listOf(fileSong(1, "Gratitude", "A"), fileSong(2, "Gratitude", "B"))),
            listOf(lib(7, "Gratitude", null)),
        )

        assertEquals(listOf(Match.CONFLICT_A, Match.NEW), plan.songs.map { it.match })
        assertEquals(1, plan.conflicts.size)
        assertEquals(1L, plan.conflicts.single().song.localId)
        // The claim holds whatever the answer: choosing NEW for A does not hand the library song to B.
        val resolved = resolveSharedImport(plan, mapOf(1L to Choice.NEW))
        assertEquals(listOf(1L, 2L), resolved.newSongs.map { it.localId })
        assertEquals(emptyMap<Long, Long>(), resolved.reused)
    }

    @Test
    fun exactMatchesClaimBeforeConflicts() {
        // The artist-less file song comes first but its exact library twin is claimed up front,
        // and the later file song with an artist cannot take it as a dialog A candidate.
        val plan = planSharedImport(
            file(listOf(fileSong(1, "Gratitude", "A"), fileSong(2, "Gratitude", null))),
            listOf(lib(7, "Gratitude", null)),
        )

        assertEquals(listOf(Match.NEW, Match.EXACT), plan.songs.map { it.match })
        assertEquals(listOf(null, 7L), plan.songs.map { it.candidate?.id })
        assertEquals(0, plan.conflicts.size)
    }

    @Test
    fun updateChoiceComparesAgainstUpdatedValues() {
        val song = fileSong(1, "Gratitude", "A", r(100), "File notes")
        val plan = planSharedImport(
            file(listOf(song), listOf(SharedEntry(1, r(100), "File notes"), SharedEntry(1, r(110), "Encore"))),
            listOf(lib(7, "Gratitude", null, r(80), "Mine")),
        )

        val resolved = resolveSharedImport(plan, mapOf(1L to Choice.UPDATE))

        assertEquals(listOf(7L to song), resolved.updates)
        assertEquals(mapOf(1L to 7L), resolved.reused)
        assertEquals(emptyList<SharedSong>(), resolved.newSongs)
        assertEquals(listOf(ResolvedEntry(1, null, null), ResolvedEntry(1, r(110), "Encore")), resolved.entries)
    }

    @Test
    fun keepMineOverridesDifferences() {
        val song = fileSong(1, "Gratitude", "A", r(100), "File notes")
        val plan = planSharedImport(file(listOf(song)), listOf(lib(7, "Gratitude", null, r(80), "Mine")))

        val resolved = resolveSharedImport(plan, mapOf(1L to Choice.KEEP_MINE))

        assertEquals(emptyList<Pair<Long, SharedSong>>(), resolved.updates)
        assertEquals(mapOf(1L to 7L), resolved.reused)
        assertEquals(listOf(ResolvedEntry(1, r(100), "File notes")), resolved.entries)
    }

    @Test
    fun newChoiceCreatesSongWithLibraryValuesAndEntryOverride() {
        val song = fileSong(1, "Gratitude", null, r(100), "File notes")
        val plan = planSharedImport(
            file(listOf(song), listOf(SharedEntry(1, r(100), "File notes"), SharedEntry(1, r(140), null))),
            listOf(lib(7, "Gratitude", "Band C", r(80), "Mine")),
        )
        assertEquals(Match.CONFLICT_B, plan.songs.single().match)

        val resolved = resolveSharedImport(plan, mapOf(1L to Choice.NEW))

        assertEquals(listOf(song), resolved.newSongs)
        assertEquals(emptyMap<Long, Long>(), resolved.reused)
        assertEquals(listOf(ResolvedEntry(1, null, null), ResolvedEntry(1, r(140), null)), resolved.entries)
    }

    @Test
    fun dialogBRejectsUpdate() {
        val plan = planSharedImport(file(listOf(fileSong(1, "Gratitude", null))), listOf(lib(7, "Gratitude", "Band C")))

        assertThrows(IllegalArgumentException::class.java) { resolveSharedImport(plan, mapOf(1L to Choice.UPDATE)) }
    }

    @Test
    fun repeatedSongProducesTwoEntries() {
        val a = fileSong(1, "Paper Lanterns", null)
        val b = fileSong(2, "Night Ferry", null)
        val plan = planSharedImport(
            file(listOf(a, b), listOf(SharedEntry(1, r(120), null), SharedEntry(2, r(120), null), SharedEntry(1, r(126), null))),
            emptyList(),
        )

        assertEquals(2, plan.songs.size)
        assertEquals(3, plan.entryCount)
        val resolved = resolveSharedImport(plan, emptyMap())
        assertEquals(listOf(a, b), resolved.newSongs)
        assertEquals(
            listOf(ResolvedEntry(1, null, null), ResolvedEntry(2, null, null), ResolvedEntry(1, r(126), null)),
            resolved.entries,
        )
    }

    @Test
    fun resolveRequiresADecisionForEveryConflict() {
        val plan = planSharedImport(
            file(listOf(fileSong(1, "Gratitude", "A"), fileSong(2, "Night Ferry", null))),
            listOf(lib(7, "Gratitude", null), lib(8, "Night Ferry", "Band C")),
        )
        assertEquals(2, plan.conflicts.size)

        assertThrows(IllegalArgumentException::class.java) { resolveSharedImport(plan, mapOf(1L to Choice.KEEP_MINE)) }
        assertThrows(IllegalArgumentException::class.java) { resolveSharedImport(plan, emptyMap()) }
        val resolved = resolveSharedImport(plan, mapOf(1L to Choice.KEEP_MINE, 2L to Choice.KEEP_MINE))
        assertEquals(mapOf(1L to 7L, 2L to 8L), resolved.reused)
        assertTrue(resolved.newSongs.isEmpty())
    }

    @Test
    fun confirmationCountsPartitionDistinctSongs() {
        val plan = planSharedImport(
            file(
                listOf(
                    fileSong(1, "Paper Lanterns", null),
                    fileSong(2, "Gratitude", "A"),
                    fileSong(3, "Gratitude", "B"),
                    fileSong(4, "Night Ferry", null),
                    fileSong(5, "Brand New Tune", "Band Z"),
                ),
                listOf(
                    SharedEntry(1, r(120), null), SharedEntry(2, r(120), null), SharedEntry(3, r(120), null),
                    SharedEntry(4, r(120), null), SharedEntry(5, r(120), null), SharedEntry(1, r(120), null),
                ),
            ),
            listOf(lib(1, "Paper Lanterns", null), lib(2, "Gratitude", null), lib(3, "Night Ferry", "Band C")),
        )

        assertEquals(1, plan.exactCount)
        assertEquals(2, plan.newCount)
        assertEquals(2, plan.conflicts.size)
        assertEquals(6, plan.entryCount)
        assertEquals(plan.songs.size, plan.exactCount + plan.newCount + plan.conflicts.size)
        assertEquals("Made Up Set", plan.name)
    }
}
