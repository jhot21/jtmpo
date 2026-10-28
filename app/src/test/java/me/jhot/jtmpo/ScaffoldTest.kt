package me.jhot.jtmpo

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaffoldTest {
    @Test
    fun packageIsStable() {
        assertEquals("me.jhot.jtmpo", BuildConfig.APPLICATION_ID)
    }
}
