package me.jhot.jtmpo.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The three stops of the player's queue sheet. */
enum class QueueStop { Collapsed, Half, Full }

/** What the player's sheet is showing: the setlist, or the full notes of the current song. */
enum class SheetContentMode { Queue, Notes }

/** Height of the sheet's drag handle row. */
val QueueHandleHeight = 24.dp

/** Height of the collapsed "Up next" label below the handle: the collapsed sheet shows handle + label. */
val QueueCollapsedLabelHeight = 36.dp

/** How many setlist rows the queue sheet shows at its half stop. */
const val QUEUE_VISIBLE_ROWS = 5

/** The top row that makes [currentIndex] the last visible row, or the top of the list while it is already in view. */
fun firstVisibleIndexFor(currentIndex: Int, visibleRows: Int = QUEUE_VISIBLE_ROWS): Int =
    (currentIndex - (visibleRows - 1)).coerceAtLeast(0)

/** Half-stop height: handle, header and up to [visibleRows] rows (fewer when the setlist is shorter). */
fun queuePeekHeight(
    handle: Dp,
    header: Dp,
    row: Dp,
    songCount: Int,
    visibleRows: Int = QUEUE_VISIBLE_ROWS,
): Dp = handle + header + row * minOf(songCount, visibleRows)

/**
 * Where to scroll the queue so the playing song is on screen, or null when it already is (or there is none).
 * Scrolling only when needed keeps the list from jumping under the finger after a tap on a visible row.
 */
fun queueScrollTarget(currentIndex: Int, firstVisibleIndex: Int, fullyVisible: Boolean): Int? = when {
    currentIndex < 0 || fullyVisible -> null
    currentIndex < firstVisibleIndex -> currentIndex
    else -> firstVisibleIndexFor(currentIndex)
}
