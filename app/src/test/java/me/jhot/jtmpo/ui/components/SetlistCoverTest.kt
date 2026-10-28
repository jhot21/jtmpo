package me.jhot.jtmpo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SetlistCoverTest {
    @Test
    fun coverColorsAreStablePerNameAndDifferAcrossNames() {
        assertEquals(coverColors("Fall Fest"), coverColors("Fall Fest"))
        val names = listOf("Fall Fest", "Setlist A (all songs)", "Setlist 2", "Wedding", "Rehearsal")
        assertEquals(names.size, names.map { coverColors(it) }.toSet().size)
    }

    @Test
    fun renamingChangesCover() {
        assertNotEquals(coverColors("Fall Fest"), coverColors("Fall Fest 2"))
    }
}
