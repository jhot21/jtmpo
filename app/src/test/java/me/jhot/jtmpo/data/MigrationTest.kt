package me.jhot.jtmpo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private const val DB_NAME = "migration-test.db"
private val schemaFile = File("schemas/me.jhot.jtmpo.data.JtmpoDatabase/1.json")

/**
 * Builds a v1 database by running the exported v1 schema's own DDL (tables, indices and the
 * `room_master_table` row with the v1 identity hash from `setupQueries`), inserts rows with raw
 * SQL, then opens the file with [MIGRATION_1_2]. Room validates the migrated schema on open, so
 * a migration that leaves the schema wrong fails with Room's own error before any assertion.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    /** A `songs` row as v1 stored it, before `songKey` existed. */
    private data class V1Song(
        val id: Long,
        val title: String,
        val artist: String?,
        val tempo: Int = 120,
        val notes: String? = null,
    )

    /** An `entries` row as v1 stored it; a non-null [ovTempo] stands for a whole 4/4 override group. */
    private data class V1Entry(
        val id: Long,
        val setlistId: Long,
        val songId: Long,
        val position: Int,
        val ovTempo: Int? = null,
        val notesOverridden: Boolean = false,
        val ovNotes: String? = null,
    )

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var db: JtmpoDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        db?.close()
        dbFile.delete()
    }

    @Test
    fun migratesCleanLibraryAndFillsKeys() = runBlocking {
        createV1(
            songs = listOf(
                V1Song(1, "Tremor", "Aria Vane"),
                V1Song(2, "Drift", null, tempo = 90),
            ),
            setlists = listOf(10L to "Gig"),
            entries = listOf(V1Entry(100, 10, songId = 1, position = 0)),
        )
        val db = openMigrated()
        val songs = db.songDao().all()
        assertEquals(listOf(1L, 2L), songs.map { it.id })
        assertEquals(songKey("Tremor", "Aria Vane"), songs.single { it.id == 1L }.songKey)
        assertEquals(songKey("Drift", null), songs.single { it.id == 2L }.songKey)
        val entry = db.entryDao().get(100)!!
        assertEquals(1L, entry.songId)
        assertEquals(0, entry.position)
    }

    @Test
    fun trimsTitlesAndTurnsBlankArtistIntoNull() = runBlocking {
        createV1(songs = listOf(V1Song(1, "  Gratitude  ", "   ")))
        val song = openMigrated().songDao().all().single()
        assertEquals("Gratitude", song.title)
        assertNull(song.artist)
        assertEquals(songKey("Gratitude", null), song.songKey)
    }

    @Test
    fun mergesDuplicatesRepointingEntries() = runBlocking {
        createV1(
            songs = listOf(
                V1Song(1, "Tremor", null),
                V1Song(2, " tremor ", "    "), // trims to the same key as song 1
                V1Song(3, "Drift", null),
            ),
            setlists = listOf(10L to "Gig"),
            entries = listOf(
                V1Entry(100, 10, songId = 1, position = 0),
                V1Entry(101, 10, songId = 2, position = 1),
                V1Entry(102, 10, songId = 3, position = 2),
            ),
        )
        val db = openMigrated()
        // the lowest id survives the merge
        assertEquals(listOf(1L, 3L), db.songDao().all().map { it.id })
        val entries = db.entryDao().forSetlist(10)
        assertEquals(listOf(100L, 101L, 102L), entries.map { it.id })
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        assertEquals(listOf(1L, 1L, 3L), entries.map { it.songId })
        // identical duplicates repoint without inventing overrides
        assertNull(entries[1].ovTempo)
        assertFalse(entries[1].notesOverridden)
    }

    @Test
    fun mergedEntryKeepsItsOldRhythmAndNotes() = runBlocking {
        createV1(
            songs = listOf(
                V1Song(1, "Anchor", null, tempo = 120),
                V1Song(2, " anchor ", "", tempo = 90, notes = "x"),
            ),
            setlists = listOf(10L to "Gig"),
            entries = listOf(
                // a plain entry keeps what it was effective with: the duplicate's tempo 90 and notes "x"
                V1Entry(100, 10, songId = 2, position = 0),
                // an entry that already had its own override keeps it verbatim
                V1Entry(101, 10, songId = 2, position = 1, ovTempo = 140, notesOverridden = true, ovNotes = "own"),
            ),
        )
        val db = openMigrated()
        assertEquals(listOf(1L), db.songDao().all().map { it.id })
        val repointed = db.entryDao().get(100)!!
        assertEquals(1L, repointed.songId)
        assertEquals(90, repointed.ovTempo)
        assertEquals(4, repointed.ovTsNum)
        assertEquals(4, repointed.ovTsDen)
        assertEquals(4, repointed.ovPatDen)
        assertEquals(false, repointed.ovPatDotted)
        assertTrue(repointed.notesOverridden)
        assertEquals("x", repointed.ovNotes)
        val kept = db.entryDao().get(101)!!
        assertEquals(1L, kept.songId)
        assertEquals(140, kept.ovTempo)
        assertTrue(kept.notesOverridden)
        assertEquals("own", kept.ovNotes)
    }

    @Test
    fun entryForeignKeysSurvive() = runBlocking {
        createV1(
            songs = listOf(
                V1Song(1, "Tremor", null),
                V1Song(2, "TREMOR", null), // same key, case-only difference
                V1Song(3, "Drift", "Aria Vane"),
            ),
            setlists = listOf(10L to "Gig", 11L to "Night One"),
            entries = listOf(
                V1Entry(100, 10, songId = 1, position = 0),
                V1Entry(101, 11, songId = 2, position = 0),
            ),
        )
        val db = openMigrated()
        assertEquals(listOf(1L, 3L), db.songDao().all().map { it.id })
        // repointed onto the survivor, not eaten by the cascade delete
        assertEquals(listOf(1L, 1L), db.entryDao().all().map { it.songId })
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { check ->
            assertFalse(check.moveToFirst())
        }
    }

    /**
     * Runs the v1 schema's DDL against a fresh file (tables, indices, the `room_master_table` row
     * from `setupQueries`), then inserts the given rows the way v1 stored them. `PRAGMA
     * user_version = 1` makes Room treat the file as a real v1 install when [openMigrated] opens
     * it and bumps it to v2.
     */
    private fun createV1(
        songs: List<V1Song>,
        setlists: List<Pair<Long, String>> = emptyList(),
        entries: List<V1Entry> = emptyList(),
    ) {
        val database = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.beginTransaction()
        try {
            for (entity in database["entities"]!!.jsonArray) {
                val table = entity.jsonObject
                val name = table["tableName"]!!.jsonPrimitive.content
                val indexSql = table["indices"]?.jsonArray
                    ?.map { it.jsonObject["createSql"]!!.jsonPrimitive.content }
                    .orEmpty()
                (listOf(table["createSql"]!!.jsonPrimitive.content) + indexSql).forEach { sql ->
                    sqlite.execSQL(sql.replace("\${TABLE_NAME}", name))
                }
            }
            database["setupQueries"]!!.jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            sqlite.execSQL("PRAGMA user_version = 1")
            songs.forEach { sqlite.insertSong(it) }
            setlists.forEach { (id, name) ->
                sqlite.execSQL(
                    "INSERT INTO setlists (id, name, lastPlayedAt) VALUES (?, ?, NULL)",
                    arrayOf<Any?>(id, name),
                )
            }
            entries.forEach { sqlite.insertEntry(it) }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
            sqlite.close()
        }
    }

    private fun openMigrated(): JtmpoDatabase =
        Room.databaseBuilder(context, JtmpoDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun SQLiteDatabase.insertSong(song: V1Song) {
        // 4/4 with a quarter pattern for every song; only tempo and notes ever differ in fixtures.
        execSQL(
            "INSERT INTO songs (id, title, artist, tempo, tsNum, tsDen, patDen, patDotted, notes, createdAt) " +
                "VALUES (?, ?, ?, ?, 4, 4, 4, 0, ?, 1)",
            arrayOf<Any?>(song.id, song.title, song.artist, song.tempo, song.notes),
        )
    }

    private fun SQLiteDatabase.insertEntry(entry: V1Entry) {
        val tempo = entry.ovTempo
        execSQL(
            "INSERT INTO entries (id, setlistId, songId, position, ovTempo, ovTsNum, ovTsDen, ovPatDen, ovPatDotted, notesOverridden, ovNotes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                entry.id, entry.setlistId, entry.songId, entry.position,
                tempo, tempo?.let { 4 }, tempo?.let { 4 }, tempo?.let { 4 }, tempo?.let { 0 },
                if (entry.notesOverridden) 1 else 0, entry.ovNotes,
            ),
        )
    }
}
