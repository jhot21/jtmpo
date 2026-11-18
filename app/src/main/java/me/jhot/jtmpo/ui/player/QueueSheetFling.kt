package me.jhot.jtmpo.ui.player

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.animateTo
import kotlin.math.abs

/**
 * What a slow release should do. Returns 0 (settle to the nearest stop, which for less than half the gap is the one the
 * drag started from) unless the sheet was dragged more than [fraction] of the way from [currentPosition] to
 * [neighborPosition], in which case it returns a flick of size [force] towards that neighbour (positive is down).
 * The library's own release only ever uses halfway, which made a small drag close the sheet and a 300 px one not open it.
 */
fun slowReleaseVelocity(offset: Float, currentPosition: Float, neighborPosition: Float?, fraction: Float, force: Float): Float {
    if (neighborPosition == null || offset.isNaN() || currentPosition.isNaN() || offset == currentPosition) return 0f
    val moved = abs(offset - currentPosition)
    val gap = abs(neighborPosition - currentPosition)
    if (gap <= 0f || moved <= gap * fraction) return 0f
    return if (offset > currentPosition) force else -force
}

/**
 * Release behaviour for the queue sheet. A real flick (faster than [minFlingVelocity], in px/s) goes on speed alone;
 * anything slower is decided by distance with [slowReleaseVelocity], so a small accidental movement always settles back.
 */
class DistanceFirstFling<T>(
    private val state: AnchoredDraggableState<T>,
    private val delegate: TargetedFlingBehavior,
    private val minFlingVelocity: Float,
    private val fraction: Float = 1f / 3f,
) : TargetedFlingBehavior {
    /** The velocity to release with. */
    fun adjust(velocity: Float): Float {
        if (abs(velocity) >= minFlingVelocity) return velocity
        val offset = state.offset
        val current = state.anchors.positionOf(state.settledValue)
        val neighbor = if (offset.isNaN() || current.isNaN() || offset == current) null
        else state.anchors.closestAnchor(offset, offset > current)?.let { state.anchors.positionOf(it) }
        return slowReleaseVelocity(offset, current, neighbor, fraction, force = minFlingVelocity * 4f)
    }

    /**
     * Settles the sheet after the song list was pulled down at full height and let go: towards the neighbouring stop in
     * the direction of [velocity] (see [adjust]), or back to the current one.
     */
    suspend fun settleAfterPull(velocity: Float) {
        val v = adjust(velocity)
        val offset = state.offset
        val target = when {
            offset.isNaN() || v == 0f -> state.settledValue
            else -> state.anchors.closestAnchor(offset, v > 0f) ?: state.settledValue
        }
        state.animateTo(target)
    }

    override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float =
        with(delegate) { performFling(adjust(initialVelocity), onRemainingDistanceUpdated) }
}
