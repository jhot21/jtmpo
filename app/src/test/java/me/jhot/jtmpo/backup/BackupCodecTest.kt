package me.jhot.jtmpo.backup

import me.jhot.jtmpo.data.BackupEntry
import me.jhot.jtmpo.data.BackupSetlist
import me.jhot.jtmpo.data.BackupSnapshot
import me.jhot.jtmpo.data.BackupSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupCodecTest {
    private fun song(id: Long, tempo: Int = 100, tsDen: Int = 4, patDen: Int = 4) =
        BackupSong(id, "Song $id", "Artist", tempo, 4, tsDen, patDen, false, "notes", 5_000L + id)

    private fun entry(
        id: Long,
        setlist: Long = 1,
        song: Long = 1,
        position: Int = 0,
        rhythm: Boolean = false,
        notes: String? = null,
        notesOverridden: Boolean = false,
    ) = BackupEntry(
        id, setlist, song, position,
        if (rhythm) 150 else null, if (rhythm) 6 else null, if (rhythm) 8 else null,
        if (rhythm) 8 else null, if (rhythm) false else null,
        notesOverridden, notes,
    )

    private fun snapshot(
        songs: List<BackupSong> = listOf(song(1), song(2)),
        setlists: List<BackupSetlist> = listOf(BackupSetlist(1, "Setlist A", 9_000L), BackupSetlist(2, "Empty", null)),
        entries: List<BackupEntry> = listOf(
            entry(1, position = 0, song = 1),
            entry(2, position = 1, song = 2, rhythm = true, notes = "", notesOverridden = true),
            entry(3, position = 2, song = 1),
        ),
    ) = BackupSnapshot(songs, setlists, entries)

    private fun withVersion(json: String, version: String) =
        json.replace("\"version\":${BackupCodec.VERSION}", "\"version\":$version")

    @Test
    fun roundTripPreservesSongsSetlistsEntriesAndOverrides() {
        val original = snapshot()
        val decoded = BackupCodec.decode(BackupCodec.encode(original)).getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun encodedFileCarriesAFormatVersion() {
        assertTrue(BackupCodec.encode(snapshot()).contains("\"version\":${BackupCodec.VERSION}"))
    }

    @Test
    fun decodeRejectsCorruptJson() {
        assertTrue(BackupCodec.decode("{ not json").isFailure)
        assertTrue(BackupCodec.decode("").isFailure)
        val truncated = BackupCodec.encode(snapshot()).dropLast(20)
        assertTrue(BackupCodec.decode(truncated).isFailure)
    }

    @Test
    fun decodeRejectsNewerVersion() {
        val json = withVersion(BackupCodec.encode(snapshot()), "${BackupCodec.VERSION + 1}")
        val failure = BackupCodec.decode(json).exceptionOrNull()
        assertTrue(failure is BackupException.NewerVersion)
    }

    @Test
    fun decodeRejectsMissingOrInvalidVersion() {
        val json = BackupCodec.encode(snapshot())
        assertTrue(BackupCodec.decode(json.replace("\"version\":${BackupCodec.VERSION},", "")).isFailure)
        assertTrue(BackupCodec.decode(withVersion(json, "0")).isFailure)
        assertTrue(BackupCodec.decode(withVersion(json, "\"1\"")).isFailure)
    }

    @Test
    fun decodeRejectsDanglingReferences() {
        val missingSong = snapshot(entries = listOf(entry(1, song = 99)))
        val missingSetlist = snapshot(entries = listOf(entry(1, setlist = 99)))
        assertTrue(BackupCodec.decode(BackupCodec.encode(missingSong)).isFailure)
        assertTrue(BackupCodec.decode(BackupCodec.encode(missingSetlist)).isFailure)
    }

    @Test
    fun decodeRejectsDuplicateIdsAndPositions() {
        assertTrue(BackupCodec.decode(BackupCodec.encode(snapshot(songs = listOf(song(1), song(1))))).isFailure)
        assertTrue(
            BackupCodec.decode(
                BackupCodec.encode(snapshot(setlists = listOf(BackupSetlist(1, "a", null), BackupSetlist(1, "b", null)))),
            ).isFailure,
        )
        assertTrue(
            BackupCodec.decode(
                BackupCodec.encode(snapshot(entries = listOf(entry(1, position = 0), entry(1, position = 1)))),
            ).isFailure,
        )
        assertTrue(
            BackupCodec.decode(
                BackupCodec.encode(snapshot(entries = listOf(entry(1, position = 0), entry(2, position = 0)))),
            ).isFailure,
        )
    }

    @Test
    fun decodeRejectsNonPositiveIdsAndNegativePositions() {
        assertTrue(BackupCodec.decode(BackupCodec.encode(snapshot(songs = listOf(song(0))))).isFailure)
        assertTrue(BackupCodec.decode(BackupCodec.encode(snapshot(entries = listOf(entry(1, position = -1))))).isFailure)
    }

    @Test
    fun decodeSanitisesLikeTheRepositoryMapper() {
        val messy = snapshot(
            songs = listOf(song(1, tempo = 9_999, tsDen = 5), song(2, tempo = 3)),
            entries = listOf(
                // Rhythm override group with one column missing is no override at all.
                entry(1, position = 0, rhythm = true).copy(ovPatDen = null),
                // Notes text without the override flag is dropped.
                entry(2, position = 1, notes = "stale", notesOverridden = false),
            ),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(messy)).getOrThrow()
        // Bad denominator falls back to 4/4 with a quarter pattern, tempo clamped to 20..300.
        assertEquals(listOf(300, 20), decoded.songs.map { it.tempo })
        assertEquals(4, decoded.songs[0].tsDen)
        assertEquals(4, decoded.songs[0].tsNum)
        assertNull(decoded.entries[0].ovTempo)
        assertNull(decoded.entries[0].ovTsDen)
        assertNull(decoded.entries[0].ovPatDotted)
        assertNull(decoded.entries[1].ovNotes)
    }

    @Test
    fun decodeRejectsDuplicateSongKeys() {
        val songs = listOf(
            BackupSong(1, "Gratitude", "Sample Artist", 100, 4, 4, 4, false, null, 1L),
            BackupSong(2, "Gratitude ", "SAMPLE ARTIST", 100, 4, 4, 4, false, null, 2L),
        )
        val json = BackupCodec.encode(snapshot(songs = songs, entries = emptyList()))
        val failure = BackupCodec.decode(json).exceptionOrNull()
        assertTrue(failure is BackupException.DuplicateSong)
        assertEquals("Gratitude", (failure as BackupException.DuplicateSong).title)
    }

    private fun dbSong(id: Long, title: String, artist: String?) =
        BackupSong(id, title, artist, 100, 4, 4, 4, false, null, id)

    private fun encoded(songs: List<BackupSong>) = BackupCodec.encode(snapshot(songs = songs, entries = emptyList()))

    @Test
    fun validatorRejectsKeysThatDifferOnlyByUnicodeCase() {
        val songs = listOf(dbSong(1, "\u00c9cole", "X"), dbSong(2, "\u00e9cole", "x"))
        assertTrue(BackupCodec.decode(encoded(songs)).exceptionOrNull() is BackupException.DuplicateSong)
    }

    @Test
    fun validatorTreatsNullAndEmptyArtistAsTheSame() {
        val songs = listOf(dbSong(1, "\u00c9cole", null), dbSong(2, "\u00c9cole", ""))
        assertTrue(BackupCodec.decode(encoded(songs)).exceptionOrNull() is BackupException.DuplicateSong)
    }

    @Test
    fun validatorAcceptImpliesReplaceAllAccepts() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, me.jhot.jtmpo.data.JtmpoDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = me.jhot.jtmpo.data.Repository(db) { 1L }
            val accepted = listOf(
                "same title, different artists" to listOf(dbSong(1, "Sample", "A"), dbSong(2, "Sample", "B")),
                "same artist, titles differ by non-case characters" to
                    listOf(dbSong(1, "Sample One", "A"), dbSong(2, "Sample-One", "A"), dbSong(3, "Sample Two", "A")),
                "accented vs unaccented" to listOf(dbSong(1, "Ecole", "X"), dbSong(2, "\u00c9cole", "X")),
                "separator: title with space vs title/artist" to listOf(dbSong(1, "a b", null), dbSong(2, "a", "b")),
                "boundary shift" to listOf(dbSong(1, "ab", "c"), dbSong(2, "a", "bc")),
            )
            for ((label, songs) in accepted) {
                val snap = BackupCodec.decode(encoded(songs)).getOrNull()
                assertTrue("validator should accept: $label", snap != null)
                repo.replaceAll(snap!!)
                assertEquals(label, songs.size, repo.snapshot().songs.size)
            }
        } finally {
            db.close()
        }
    }
}
