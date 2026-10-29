package me.jhot.jtmpo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class SongKeyTest {
    @Test
    fun blankAndNullArtistAreEqual() {
        assertEquals(songKey("A", null), songKey("A", "  "))
    }

    @Test
    fun caseAndWhitespaceInsensitive() {
        assertEquals(songKey(" École ", "x"), songKey("école", " X"))
    }

    @Test
    fun differentArtistsDiffer() {
        assertNotEquals(songKey("A", "x"), songKey("A", "y"))
    }

    @Test
    fun separatorPreventsCollision() {
        assertNotEquals(songKey("a b", null), songKey("a", "b"))
    }

    @Test
    fun usesRootLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr"))
            assertEquals("i", titleKey("I"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
