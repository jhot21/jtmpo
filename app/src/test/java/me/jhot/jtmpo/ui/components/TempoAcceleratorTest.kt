package me.jhot.jtmpo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoAcceleratorTest {
    @Test
    fun slowDragMovesOneBpmPerStep() {
        val a = TempoAccelerator()
        assertEquals(1, a.onDrag(TempoDragTuning.SLOW_PX_PER_BPM, 100))
        assertEquals(3, a.onDrag(TempoDragTuning.SLOW_PX_PER_BPM * 3, 300))
    }

    @Test
    fun fastDragStepsGrow() {
        val slow = TempoAccelerator().onDrag(120f, 400)
        val fast = TempoAccelerator().onDrag(120f, 40)
        assertTrue("fast $fast should exceed slow $slow", fast > slow)
    }

    @Test
    fun leftIsNegativeRightIsPositive() {
        assertTrue(TempoAccelerator().onDrag(60f, 200) > 0)
        assertTrue(TempoAccelerator().onDrag(-60f, 200) < 0)
        assertEquals(-1, TempoAccelerator().onDrag(-TempoDragTuning.SLOW_PX_PER_BPM, 100))
    }

    @Test
    fun remainderCarriesBetweenCalls() {
        val a = TempoAccelerator()
        val half = TempoDragTuning.SLOW_PX_PER_BPM / 2
        assertEquals(0, a.onDrag(half, 100))
        assertEquals(1, a.onDrag(half, 100))
        assertEquals(0, a.onDrag(half, 100))
    }
}
