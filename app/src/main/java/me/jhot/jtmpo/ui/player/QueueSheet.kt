package me.jhot.jtmpo.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.ui.components.EntryRowContent
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.theme.TextSecondary
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The pull-up setlist. Header is the setlist name and song count only. A tap selects the song
 * (the controller switches at once while playing, or waits while paused) and the sheet stays open
 * so the highlight can be seen moving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    setlistName: String,
    entries: List<EffectiveSong>,
    currentEntryId: Long?,
    snackbar: SnackbarHostState,
    onSelect: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onEdit: (EffectiveSong) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The list shows a local copy while dragging; the repository write happens once, on drop.
    // Static, not navigationBarsPadding() inside the sheet: that follows the sheet's position, so it changes the
    // sheet's height mid-drag and the sheet snaps back.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Same for the top: the sheet reports its own offset as consumed insets, so a top inset applied through
    // contentWindowInsets shrinks as the sheet is dragged down and resizes it mid-drag.
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    var local by remember { mutableStateOf(entries) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    LaunchedEffect(entries) { if (!dragging) local = entries }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.padding(top = topInset),
        // The padding above keeps the sheet below the status bar and camera cutout; the insets are padded by hand.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(bottom = bottomInset)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
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
                LazyColumn(Modifier.weight(1f, fill = false), state = listState) {
                    items(local, key = { it.entryId }) { entry ->
                        ReorderableItem(reorderState, key = entry.entryId) {
                            EntryRowContent(
                                entry = entry,
                                highlighted = entry.entryId == currentEntryId,
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
            JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
