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
        assertEquals(376.dp, queuePeekHeight(24.dp, 72.dp, 56.dp, songCount = 30))
        assertEquals(264.dp, queuePeekHeight(24.dp, 72.dp, 56.dp, songCount = 3))
        assertEquals(96.dp, queuePeekHeight(24.dp, 72.dp, 56.dp, songCount = 0))
    }

    @Test
    fun noScrollWhenPlayingSongIsFullyVisible() {
        assertEquals(null, queueScrollTarget(currentIndex = 7, firstVisibleIndex = 3, fullyVisible = true))
    }

    @Test
    fun noScrollWhenCurrentIsMissing() {
        assertEquals(null, queueScrollTarget(currentIndex = -1, firstVisibleIndex = 0, fullyVisible = false))
    }

    @Test
    fun playingSongAboveTheViewBecomesTheFirstRow() {
        assertEquals(2, queueScrollTarget(currentIndex = 2, firstVisibleIndex = 6, fullyVisible = false))
    }

    @Test
    fun playingSongBelowTheViewBecomesTheLastRow() {
        assertEquals(5, queueScrollTarget(currentIndex = 9, firstVisibleIndex = 0, fullyVisible = false))
        assertEquals(0, queueScrollTarget(currentIndex = 3, firstVisibleIndex = 0, fullyVisible = false))
    }

    @Test
    fun slowReleaseBelowAThirdOfTheGapSettlesBack() {
        // current stop at 1000, next stop (up) at 200: a third of the 800 gap is about 267
        assertEquals(0f, slowReleaseVelocity(offset = 800f, currentPosition = 1000f, neighborPosition = 200f, fraction = 1f / 3f, force = 5000f), 0f)
        assertEquals(0f, slowReleaseVelocity(offset = 1000f, currentPosition = 1000f, neighborPosition = 200f, fraction = 1f / 3f, force = 5000f), 0f)
    }

    @Test
    fun slowReleasePastAThirdGoesToTheNextStop() {
        assertEquals(-5000f, slowReleaseVelocity(offset = 700f, currentPosition = 1000f, neighborPosition = 200f, fraction = 1f / 3f, force = 5000f), 0f)
        // dragging down towards a stop below
        assertEquals(5000f, slowReleaseVelocity(offset = 600f, currentPosition = 200f, neighborPosition = 1000f, fraction = 1f / 3f, force = 5000f), 0f)
    }

    @Test
    fun slowReleaseWithNoNeighbourOrNoOffsetDoesNothing() {
        assertEquals(0f, slowReleaseVelocity(offset = 700f, currentPosition = 1000f, neighborPosition = null, fraction = 1f / 3f, force = 5000f), 0f)
        assertEquals(0f, slowReleaseVelocity(offset = Float.NaN, currentPosition = 1000f, neighborPosition = 200f, fraction = 1f / 3f, force = 5000f), 0f)
    }
}
