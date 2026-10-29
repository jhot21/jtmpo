package me.jhot.jtmpo.share

import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetlistFileCodecTest {
    private val waltz = Rhythm(90, TimeSignature(3, 4), ClickPattern(4))
    private val shuffle = Rhythm(120, TimeSignature(6, 8), ClickPattern(8, dotted = false))

    private fun song(
        id: Long,
        title: String,
        artist: String? = null,
        tempo: Int = 100,
        tsDen: Int = 4,
    ) = """{"id":$id,"title":"$title","artist":${artist?.let { "\"$it\"" }},"tempo":$tempo,"tsNum":4,"tsDen":$tsDen,"patDen":4,"patDotted":false,"notes":null}"""

    private fun entry(songId: Long) =
        """{"songId":$songId,"tempo":100,"tsNum":4,"tsDen":4,"patDen":4,"patDotted":false,"notes":null}"""

    private fun file(
        songs: List<String>,
        entries: List<String>,
        type: String? = "jtmpo-setlist",
        version: Int = 1,
        name: String = "Gig",
    ): String {
        val t = type?.let { "\"type\":\"$it\"," } ?: ""
        return """{$t"version":$version,"name":"$name","songs":[${songs.joinToString(",")}],"entries":[${entries.joinToString(",")}]}"""
    }

    private fun failure(text: String) = SetlistFileCodec.decode(text).exceptionOrNull()

    @Test
    fun roundTripKeepsEverything() {
        val original = SharedSetlist(
            name = "Friday Night",
            songs = listOf(
                SharedSong(1, "Moonlit Waltz", "The Test Band", waltz, "slow start"),
                SharedSong(2, "Paper Boats", null, shuffle, null),
            ),
            entries = listOf(
                SharedEntry(2, shuffle, "cut the intro"),
                SharedEntry(1, waltz, null),
                SharedEntry(2, waltz, null),
            ),
        )
        val decoded = SetlistFileCodec.decode(SetlistFileCodec.encode(original)).getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun rhythmIsSanitisedOnDecode() {
        val text = file(listOf(song(1, "A", tsDen = 7)), listOf(entry(1)))
        val s = SetlistFileCodec.decode(text).getOrThrow().songs.single()
        assertEquals(TimeSignature(4, 4), s.rhythm.timeSignature)
    }

    @Test
    fun rejectsNotJson() {
        assertTrue(failure("not json at all") is SetlistFileException.Invalid)
    }

    @Test
    fun rejectsWrongOrMissingType() {
        assertTrue(failure(file(listOf(song(1, "A")), listOf(entry(1)), type = "other")) is SetlistFileException.NotASetlist)
        assertTrue(failure(file(listOf(song(1, "A")), listOf(entry(1)), type = null)) is SetlistFileException.NotASetlist)
    }

    @Test
    fun backupShapedJsonIsLooksLikeBackup() {
        val text = """{"version":1,"songs":[],"setlists":[],"entries":[]}"""
        assertTrue(failure(text) is SetlistFileException.LooksLikeBackup)
    }

    @Test
    fun rejectsNewerVersion() {
        val e = failure(file(listOf(song(1, "A")), listOf(entry(1)), version = 2))
        assertTrue(e is SetlistFileException.NewerVersion)
        assertEquals(2, (e as SetlistFileException.NewerVersion).version)
    }

    @Test
    fun rejectsVersionZero() {
        assertTrue(failure(file(listOf(song(1, "A")), listOf(entry(1)), version = 0)) is SetlistFileException.Invalid)
    }

    @Test
    fun rejectsBlankTitle() {
        assertTrue(failure(file(listOf(song(1, "  ")), listOf(entry(1)))) is SetlistFileException.Invalid)
    }

    @Test
    fun rejectsDuplicateSongId() {
        assertTrue(failure(file(listOf(song(1, "A"), song(1, "B")), listOf(entry(1)))) is SetlistFileException.Invalid)
    }

    @Test
    fun rejectsEntryForUnknownSong() {
        assertTrue(failure(file(listOf(song(1, "A")), listOf(entry(1), entry(9)))) is SetlistFileException.Invalid)
    }

    @Test
    fun rejectsTwoSongsWithSameKey() {
        val text = file(listOf(song(1, "Same Song", "Ann"), song(2, " same song ", "ANN")), listOf(entry(1), entry(2)))
        assertTrue(failure(text) is SetlistFileException.Invalid)
    }

    @Test
    fun rejectsZeroEntries() {
        assertTrue(failure(file(listOf(song(1, "A")), emptyList())) is SetlistFileException.Invalid)
    }

    @Test
    fun blankNameBecomesSharedSetlist() {
        val decoded = SetlistFileCodec.decode(file(listOf(song(1, "A")), listOf(entry(1)), name = "   ")).getOrThrow()
        assertEquals("Shared setlist", decoded.name)
    }

    @Test
    fun whitespaceArtistIsNull() {
        val decoded = SetlistFileCodec.decode(file(listOf(song(1, "A", artist = "   ")), listOf(entry(1)))).getOrThrow()
        assertNull(decoded.songs.single().artist)
    }

    @Test
    fun unreferencedSongsAreDropped() {
        val decoded = SetlistFileCodec.decode(file(listOf(song(1, "A"), song(2, "B")), listOf(entry(2)))).getOrThrow()
        assertEquals(listOf(2L), decoded.songs.map { it.localId })
    }
}
