package me.jhot.jtmpo.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import me.jhot.jtmpo.data.BackupEntry
import me.jhot.jtmpo.data.BackupSetlist
import me.jhot.jtmpo.data.BackupSnapshot
import me.jhot.jtmpo.data.BackupSong
import me.jhot.jtmpo.data.storedRhythm

sealed class BackupException(message: String) : Exception(message) {
    /** Written by a newer app; restoring it could silently drop data this version doesn't know. */
    class NewerVersion(val version: Int) : BackupException("Backup format $version is newer than ${BackupCodec.VERSION}")

    class Invalid(message: String) : BackupException(message)
}

@Serializable
private data class BackupFile(
    val version: Int,
    val songs: List<BackupSong>,
    val setlists: List<BackupSetlist>,
    val entries: List<BackupEntry>,
)

/** Whole-app backup as one JSON document with a top-level `version`. */
object BackupCodec {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(snapshot: BackupSnapshot): String =
        json.encodeToString(BackupFile(VERSION, snapshot.songs, snapshot.setlists, snapshot.entries))

    /**
     * Parses and validates before returning success, so a caller can hand the result straight to
     * `Repository.replaceAll`. Rhythm values are sanitised with the repository's own rules.
     */
    fun decode(text: String): Result<BackupSnapshot> = try {
        Result.success(parse(text))
    } catch (e: BackupException) {
        Result.failure(e)
    } catch (e: Exception) {
        // Malformed JSON, wrong types and missing fields all surface as a serialization/illegal-arg error.
        Result.failure(BackupException.Invalid(e.message ?: "Unreadable backup"))
    }

    private fun parse(text: String): BackupSnapshot {
        val root: JsonObject = json.parseToJsonElement(text).jsonObject
        val versionElement = root["version"] as? JsonPrimitive
        if (versionElement == null || versionElement.isString) throw BackupException.Invalid("Missing version")
        val version = versionElement.int
        if (version > VERSION) throw BackupException.NewerVersion(version)
        if (version < 1) throw BackupException.Invalid("Unknown version $version")

        val file = json.decodeFromJsonElement(BackupFile.serializer(), root)
        val songs = file.songs.map(::sanitise)
        val entries = file.entries.map(::sanitise)
        validate(songs, file.setlists, entries)
        return BackupSnapshot(songs, file.setlists, entries)
    }

    private fun sanitise(song: BackupSong): BackupSong {
        val r = storedRhythm(song.tempo, song.tsNum, song.tsDen, song.patDen, song.patDotted)
        return song.copy(
            tempo = r.tempo,
            tsNum = r.timeSignature.numerator,
            tsDen = r.timeSignature.denominator,
            patDen = r.pattern.noteDenominator,
            patDotted = r.pattern.dotted,
        )
    }

    private fun sanitise(entry: BackupEntry): BackupEntry {
        val hasGroup = entry.ovTempo != null && entry.ovTsNum != null && entry.ovTsDen != null &&
            entry.ovPatDen != null && entry.ovPatDotted != null
        val withRhythm = if (hasGroup) {
            val r = storedRhythm(entry.ovTempo, entry.ovTsNum, entry.ovTsDen, entry.ovPatDen, entry.ovPatDotted)
            entry.copy(
                ovTempo = r.tempo,
                ovTsNum = r.timeSignature.numerator,
                ovTsDen = r.timeSignature.denominator,
                ovPatDen = r.pattern.noteDenominator,
                ovPatDotted = r.pattern.dotted,
            )
        } else {
            entry.copy(ovTempo = null, ovTsNum = null, ovTsDen = null, ovPatDen = null, ovPatDotted = null)
        }
        return if (withRhythm.notesOverridden) withRhythm else withRhythm.copy(ovNotes = null)
    }

    private fun validate(songs: List<BackupSong>, setlists: List<BackupSetlist>, entries: List<BackupEntry>) {
        fun fail(message: String): Nothing = throw BackupException.Invalid(message)
        if (songs.any { it.id <= 0 } || setlists.any { it.id <= 0 } || entries.any { it.id <= 0 }) fail("Non-positive id")
        if (songs.map { it.id }.toSet().size != songs.size) fail("Duplicate song id")
        if (setlists.map { it.id }.toSet().size != setlists.size) fail("Duplicate setlist id")
        if (entries.map { it.id }.toSet().size != entries.size) fail("Duplicate entry id")
        val songIds = songs.map { it.id }.toSet()
        val setlistIds = setlists.map { it.id }.toSet()
        if (entries.any { it.songId !in songIds }) fail("Entry references a missing song")
        if (entries.any { it.setlistId !in setlistIds }) fail("Entry references a missing setlist")
        if (entries.any { it.position < 0 }) fail("Negative entry position")
        if (entries.groupBy { it.setlistId }.values.any { l -> l.map { it.position }.toSet().size != l.size }) {
            fail("Duplicate entry position")
        }
    }
}
