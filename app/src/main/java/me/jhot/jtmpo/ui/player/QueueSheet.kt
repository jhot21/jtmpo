package me.jhot.jtmpo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.ui.components.EntryRowContent
import me.jhot.jtmpo.ui.theme.TextSecondary
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The setlist inside the player's non-modal sheet. Its top is a drag handle, then a header that is the setlist name
 * and song count (and "Add from library"), cross-faded with the "Up next" label while the sheet is collapsed. A tap on
 * a row selects the song (the controller switches at once while playing, or waits while paused) and the sheet stays
 * open so the highlight can be seen moving.
 *
 * The sheet has a fixed height and [visibleHeight] is how much of it is on screen: the list gets what is left below
 * the handle and header, so at the half stop it is exactly five rows tall and scrolls by itself, and at full height it
 * fills the sheet. Never use navigationBarsPadding() in here: the sheet's own movement would change it mid-drag (the
 * player's Scaffold already pads the screen by the system bars).
 */
@Composable
fun QueueSheetContent(
    setlistName: String,
    entries: List<EffectiveSong>,
    currentEntryId: Long?,
    listState: LazyListState,
    visibleHeight: () -> Dp,
    collapsed: Boolean,
    openProgress: () -> Float,
    collapsedLabel: String,
    onOpen: () -> Unit,
    onMeasured: (headerPx: Int, rowPx: Int) -> Unit,
    onSelect: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onEdit: (EffectiveSong) -> Unit,
    onAdd: () -> Unit,
) {
    // The list shows a local copy while dragging; the repository write happens once, on drop.
    var local by remember { mutableStateOf(entries) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var headerPx by remember { mutableIntStateOf(0) }
    var rowPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(entries) { if (!dragging) local = entries }
    LaunchedEffect(headerPx, rowPx) { onMeasured(headerPx, rowPx) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    // Read here, so only this function recomposes while the sheet is dragged.
    val headerDp = with(androidx.compose.ui.platform.LocalDensity.current) { headerPx.toDp() }
    val listHeight = (visibleHeight() - QueueHandleHeight - headerDp).coerceIn(0.dp, Dp.Infinity)

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(QueueHandleHeight), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(TextSecondary, CircleShape))
        }
        Box(Modifier.fillMaxWidth().onSizeChanged { headerPx = it.height }) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = openProgress() }.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(setlistName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        pluralStringResource(R.plurals.song_count, entries.size, entries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                FilledTonalButton(onAdd) { Text(stringResource(R.string.add_from_library)) }
            }
            // The collapsed "Up next" bar. Drawn over the header, so a tap on it never reaches "Add from library".
            val openLabel = stringResource(R.string.open_setlist)
            Box(
                Modifier.fillMaxWidth().height(QueueCollapsedLabelHeight)
                    .graphicsLayer { alpha = 1f - openProgress() }
                    .semantics { contentDescription = openLabel }
                    .clickable(enabled = collapsed, onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    collapsedLabel,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyColumn(Modifier.height(listHeight), state = listState) {
            itemsIndexed(local, key = { _, it -> it.entryId }) { index, entry ->
                ReorderableItem(reorderState, key = entry.entryId) {
                    EntryRowContent(
                        entry = entry,
                        highlighted = entry.entryId == currentEntryId,
                        modifier = if (index == 0) Modifier.onSizeChanged { rowPx = it.height } else Modifier,
                        handle = Modifier.draggableHandle(
                            onDragStarted = {
                                dragging = true
                                dragFrom = local.indexOfFirst { it.entryId == entry.entryId }
                            },
                            onDragStopped = {
                                dragging = false
                                val to = local.indexOfFirst { it.entryId == entry.entryId }
                                if (dragFrom >= 0 && to >= 0 && to != dragFrom) onMove(dragFrom, to) else local = entries
                            },
                        ),
                        onClick = { onSelect(entries.indexOfFirst { it.entryId == entry.entryId }) },
                        onEdit = { onEdit(entry) },
                    )
                }
            }
        }
    }
}
