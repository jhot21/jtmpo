package me.jhot.jtmpo.share

import me.jhot.jtmpo.backup.BackupCodec
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.data.storedRhythm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Pins `demo/demo-setlist.jtmpo` to the outcomes `demo/README.md` promises against `demo/demo-library.json`. */
class DemoSetlistTest {
    // Gradle runs unit tests from the module directory, so the demo folder is one level up.
    private val demo = File("../demo")

    private fun plan(): SharedImportPlan {
        val file = SetlistFileCodec.decode(File(demo, "demo-setlist.jtmpo").readText()).getOrThrow()
        val backup = BackupCodec.decode(File(demo, "demo-library.json").readText()).getOrThrow()
        val library = backup.songs.map {
            Song(it.id, it.title, it.artist, storedRhythm(it.tempo, it.tsNum, it.tsDen, it.patDen, it.patDotted), it.notes, it.createdAt)
        }
        return planSharedImport(file, library)
    }

    private fun SharedImportPlan.song(title: String) = songs.single { it.song.title == title }

    @Test
    fun fixtureProducesTheFourDocumentedOutcomes() {
        val plan = plan()
        assertEquals(4, plan.songs.size)
        val resolved = resolveSharedImport(plan, plan.conflicts.associate { it.song.localId to Choice.KEEP_MINE })

        val exact = plan.song("Hotel California")
        assertEquals(Match.EXACT, exact.match)
        assertNull(resolved.entries.single { it.localSongId == exact.song.localId }.rhythmOverride)

        val tempo = plan.song("Sweet Home Alabama")
        assertEquals(Match.EXACT, tempo.match)
        assertNotNull(resolved.entries.single { it.localSongId == tempo.song.localId }.rhythmOverride)

        assertEquals(Match.NEW, plan.song("Paper Lanterns").match)

        val dialogB = plan.song("Layla")
        assertEquals(Match.CONFLICT_B, dialogB.match)
        assertEquals(9L, dialogB.candidate?.id)

        assertEquals(listOf(dialogB), plan.conflicts)
        assertEquals(1, plan.conflicts.size)
    }
}
