package me.jhot.jtmpo.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavVisibilityTest {
    @Test
    fun hiddenOnPlayer() {
        assertFalse(showsBottomNav("player"))
    }

    @Test
    fun shownOnTopLevelAndDetailRoutes() {
        for (route in listOf("setlists", "library", "settings", "setlist/{id}")) {
            assertTrue(route, showsBottomNav(route))
        }
    }

    @Test
    fun shownWhenRouteIsUnknown() {
        assertTrue(showsBottomNav(null))
    }
}
