package me.jhot.jtmpo.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSheetLogicTest {
    @Test
    fun currentWithinFirstFiveShowsTop() {
        for (i in 0..4) assertEquals(0, firstVisibleIndexFor(i))
    }

    @Test
    fun currentPastFifthIsLastVisible() {
        assertEquals(1, firstVisibleIndexFor(5))
        assertEquals(3, firstVisibleIndexFor(7))
        assertEquals(25, firstVisibleIndexFor(29))
    }

    @Test
    fun missingCurrentShowsTop() {
        assertEquals(0, firstVisibleIndexFor(-1))
    }

    @Test
    fun peekShowsAtMostFiveRows() {
        assertEquals(392.dp, queuePeekHeight(24.dp, 72.dp, 56.dp, songCount = 30, bottomInset = 16.dp))
        assertEquals(280.dp, queuePeekHeight(24.dp, 72.dp, 56.dp, songCount = 3, bottomInset = 16.dp))
        assertEquals(112.dp, queuePeekHeight(24.dp, 72.dp, 56.dp, songCount = 0, bottomInset = 16.dp))
    }
}
