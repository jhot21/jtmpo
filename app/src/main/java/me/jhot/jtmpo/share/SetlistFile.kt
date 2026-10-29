package me.jhot.jtmpo.share

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import me.jhot.jtmpo.data.songKey
import me.jhot.jtmpo.data.storedRhythm
import me.jhot.jtmpo.domain.Rhythm

data class SharedSong(val localId: Long, val title: String, val artist: String?, val rhythm: Rhythm, val notes: String?)

data class SharedEntry(val songId: Long, val rhythm: Rhythm, val notes: String?)

data class SharedSetlist(val name: String, val songs: List<SharedSong>, val entries: List<SharedEntry>)

sealed class SetlistFileException(message: String) : Exception(message) {
    class NewerVersion(val version: Int) : SetlistFileException("Setlist format $version is newer than ${SetlistFileCodec.VERSION}")

    /** A whole-app backup, which belongs under Restore rather than Open. */
    object LooksLikeBackup : SetlistFileException("This is a backup file, not a setlist")

    object NotASetlist : SetlistFileException("Not a jtmpo setlist")

    class Invalid(message: String) : SetlistFileException(message)
}

@Serializable
private data class FileSong(
    val id: Long,
    val title: String,
    val artist: String? = null,
    val tempo: Int,
    val tsNum: Int,
    val tsDen: Int,
    val patDen: Int,
    val patDotted: Boolean,
    val notes: String? = null,
)

@Serializable
private data class FileEntry(
    val songId: Long,
    val tempo: Int,
    val tsNum: Int,
    val tsDen: Int,
    val patDen: Int,
    val patDotted: Boolean,
    val notes: String? = null,
)

@Serializable
private data class SetlistFile(
    val type: String,
    val version: Int,
    val name: String = "",
    val songs: List<FileSong>,
    val entries: List<FileEntry>,
)

/** One setlist as a self-contained JSON document (`.jtmpo`): the songs it uses plus its ordered entries. */
object SetlistFileCodec {
    const val TYPE = "jtmpo-setlist"
    const val VERSION = 1
    private const val DEFAULT_NAME = "Shared setlist"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(setlist: SharedSetlist): String = json.encodeToString(
        SetlistFile(
            type = TYPE,
            version = VERSION,
            name = setlist.name,
            songs = setlist.songs.map {
                FileSong(
                    it.localId, it.title, it.artist, it.rhythm.tempo,
                    it.rhythm.timeSignature.numerator, it.rhythm.timeSignature.denominator,
                    it.rhythm.pattern.noteDenominator, it.rhythm.pattern.dotted, it.notes,
                )
            },
            entries = setlist.entries.map {
                FileEntry(
                    it.songId, it.rhythm.tempo,
                    it.rhythm.timeSignature.numerator, it.rhythm.timeSignature.denominator,
                    it.rhythm.pattern.noteDenominator, it.rhythm.pattern.dotted, it.notes,
                )
            },
        ),
    )

    /** Parses and validates; rhythm values are sanitised with the repository's own rules. */
    fun decode(text: String): Result<SharedSetlist> = try {
        Result.success(parse(text))
    } catch (e: SetlistFileException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(SetlistFileException.Invalid(e.message ?: "Unreadable setlist file"))
    }

    private fun parse(text: String): SharedSetlist {
        val root: JsonObject = json.parseToJsonElement(text).jsonObject
        val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != TYPE) {
            if (type == null && root["setlists"] is JsonArray) throw SetlistFileException.LooksLikeBackup
            throw SetlistFileException.NotASetlist
        }
        val versionElement = root["version"] as? JsonPrimitive
        if (versionElement == null || versionElement.isString) throw SetlistFileException.Invalid("Missing version")
        val version = versionElement.int
        if (version > VERSION) throw SetlistFileException.NewerVersion(version)
        if (version < 1) throw SetlistFileException.Invalid("Unknown version $version")

        val file = json.decodeFromJsonElement(SetlistFile.serializer(), root)
        fun fail(message: String): Nothing = throw SetlistFileException.Invalid(message)

        if (file.songs.any { it.title.isBlank() }) fail("Song without a title")
        if (file.songs.map { it.id }.toSet().size != file.songs.size) fail("Duplicate song id")
        if (file.songs.groupBy { songKey(it.title, it.artist) }.values.any { it.size > 1 }) fail("Duplicate song")
        if (file.entries.isEmpty()) fail("Setlist has no songs")
        val ids = file.songs.map { it.id }.toSet()
        if (file.entries.any { it.songId !in ids }) fail("Entry references a missing song")

        val used = file.entries.map { it.songId }.toSet()
        val songs = file.songs.filter { it.id in used }.map {
            SharedSong(it.id, it.title.trim(), it.artist?.trim()?.takeIf(String::isNotEmpty), rhythm(it.tempo, it.tsNum, it.tsDen, it.patDen, it.patDotted), it.notes)
        }
        val entries = file.entries.map {
            SharedEntry(it.songId, rhythm(it.tempo, it.tsNum, it.tsDen, it.patDen, it.patDotted), it.notes)
        }
        return SharedSetlist(file.name.trim().ifEmpty { DEFAULT_NAME }, songs, entries)
    }

    private fun rhythm(tempo: Int, tsNum: Int, tsDen: Int, patDen: Int, patDotted: Boolean): Rhythm =
        storedRhythm(tempo, tsNum, tsDen, patDen, patDotted)
}
