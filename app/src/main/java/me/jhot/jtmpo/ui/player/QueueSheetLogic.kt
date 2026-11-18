package me.jhot.jtmpo.ui.player

import androidx.compose.ui.unit.Dp

/** How many setlist rows the queue sheet shows at its half stop. */
const val QUEUE_VISIBLE_ROWS = 5

/** The top row that makes [currentIndex] the last visible row, or the top of the list while it is already in view. */
fun firstVisibleIndexFor(currentIndex: Int, visibleRows: Int = QUEUE_VISIBLE_ROWS): Int =
    (currentIndex - (visibleRows - 1)).coerceAtLeast(0)

/** Half-stop height: handle, header, up to [visibleRows] rows (fewer when the setlist is shorter) and the nav-bar inset. */
fun queuePeekHeight(
    handle: Dp,
    header: Dp,
    row: Dp,
    songCount: Int,
    bottomInset: Dp,
    visibleRows: Int = QUEUE_VISIBLE_ROWS,
): Dp = handle + header + row * minOf(songCount, visibleRows) + bottomInset
