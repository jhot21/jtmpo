package me.jhot.jtmpo.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class TabBackTest {
    @Test
    fun libraryIsTopLevelAndLeavesTheApp() =
        assertEquals(TabBack.LeaveApp, tabBack(Routes.LIBRARY, Routes.SETLISTS))

    @Test
    fun practiceIsTopLevelAndLeavesTheApp() =
        assertEquals(TabBack.LeaveApp, tabBack(Routes.PRACTICE, Routes.SETLISTS))

    @Test
    fun settingsGoesBackToTheTabItWasOpenedFrom() {
        assertEquals(TabBack.SwitchTo(Routes.SETLISTS), tabBack(Routes.SETTINGS, Routes.SETLISTS))
        assertEquals(TabBack.SwitchTo(Routes.LIBRARY), tabBack(Routes.SETTINGS, Routes.LIBRARY))
    }

    @Test
    fun settingsOpenedFromPracticeGoesBackToPractice() =
        assertEquals(TabBack.SwitchTo(Routes.PRACTICE), tabBack(Routes.SETTINGS, Routes.PRACTICE))

    @Test
    fun otherScreensKeepTheDefaultBack() {
        for (route in listOf(Routes.SETLISTS, Routes.SETLIST_PATTERN, Routes.IMPORT, Routes.PLAYER, null)) {
            assertEquals(route, TabBack.Default, tabBack(route, Routes.LIBRARY))
        }
    }

    @Test
    fun tabOfPractice() = assertEquals(Routes.PRACTICE, tabOf(Routes.PRACTICE))

    @Test
    fun tabOfGroupsDetailScreensWithTheirTab() {
        assertEquals(Routes.SETLISTS, tabOf(Routes.SETLIST_PATTERN))
        assertEquals(Routes.SETTINGS, tabOf(Routes.IMPORT))
        assertEquals(Routes.LIBRARY, tabOf(Routes.LIBRARY))
        assertEquals(null, tabOf(Routes.PLAYER))
    }
}
