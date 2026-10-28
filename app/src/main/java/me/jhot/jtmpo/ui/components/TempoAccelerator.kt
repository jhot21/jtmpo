package me.jhot.jtmpo.ui.components

import kotlin.math.abs
import kotlin.math.truncate

/** All feel-tuning for the tempo drag lives here; curve is tuned on a real device. */
object TempoDragTuning {
    /** Slow drags move 1 BPM per this many pixels. */
    const val SLOW_PX_PER_BPM = 12f

    /** Below this speed (px/ms) there is no acceleration. */
    const val SLOW_SPEED_PX_PER_MS = 0.3f

    /** Extra step multiplier gained per px/ms above the slow speed. */
    const val GAIN_PER_PX_PER_MS = 2f

    /** Upper bound on the speed factor so a flick can't jump the whole range. */
    const val MAX_FACTOR = 8f
}

/** Turns horizontal drag into signed BPM steps; right is positive. Clamping is the caller's job. */
class TempoAccelerator {
    private var remainder = 0f

    fun onDrag(dxPx: Float, dtMs: Long): Int {
        val speed = abs(dxPx) / maxOf(dtMs, 1L)
        val factor = (1f + (speed - TempoDragTuning.SLOW_SPEED_PX_PER_MS).coerceAtLeast(0f) *
            TempoDragTuning.GAIN_PER_PX_PER_MS).coerceAtMost(TempoDragTuning.MAX_FACTOR)
        val exact = dxPx / TempoDragTuning.SLOW_PX_PER_BPM * factor + remainder
        val whole = truncate(exact)
        remainder = exact - whole
        return whole.toInt()
    }

    fun reset() {
        remainder = 0f
    }
}
