package me.jhot.jtmpo.ui.setlists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.share.shareSetlistFile
import me.jhot.jtmpo.ui.components.EntryRowContent
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.edit.EditSongSheet
import me.jhot.jtmpo.ui.edit.EditSongViewModel
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.Surface0
import me.jhot.jtmpo.ui.theme.TextSecondary
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetlistDetailScreen(
    graph: AppGraph,
    setlistId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SetlistDetailViewModel = viewModel(
        key = "setlist-$setlistId",
        factory = SetlistDetailViewModel.Factory(setlistId, graph.repository, graph.playback),
    )
    val entries by vm.entries.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val navTarget by vm.navigateToPlayer.collectAsStateWithLifecycle()
    val playback by graph.playback.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val shareFailed = stringResource(R.string.share_failed)
    val context = LocalContext.current
    var editingEntry by remember { mutableStateOf<EffectiveSong?>(null) }
    // Bumped on every open so each sheet gets a fresh view model, never a finished or stale one.
    var editOpens by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(navTarget) {
        if (navTarget != null) {
            vm.navigated()
            onOpenPlayer()
        }
    }
    LaunchedEffect(undo) {
        val offer = undo ?: return@LaunchedEffect
        val result = snackbar.showSnackbar("Removed “${offer.title}”", "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoRemove() else vm.dismissUndo()
    }

    // The list shows a local copy while dragging; the repository write happens once, on drop.
    var removeFailures by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm) { vm.removeFailed.collect { removeFailures++ } }
    var local by remember { mutableStateOf(entries) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    LaunchedEffect(entries) { if (!dragging) local = entries }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val backLabel = stringResource(R.string.back_to_setlists)
            TextButton(onBack, Modifier.padding(start = 4.dp).semantics { contentDescription = backLabel }) {
                Text("‹ Setlists", color = TextSecondary)
            }
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.song_count, entries.size, entries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Box {
                    val actions = stringResource(R.string.setlist_actions)
                    IconButton({ menuOpen = true }, Modifier.semantics { contentDescription = actions }) {
                        Text("⋮", color = if (menuOpen) Accent else TextSecondary)
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            enabled = entries.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val (n, json) = vm.shareJson(setlistId) ?: return@launch
                                    if (!context.shareSetlistFile(n, json)) snackbar.showSnackbar(shareFailed)
                                }
                            },
                        )
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; deleting = true })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = vm::playFromStart,
                    enabled = vm.canPlay,
                    modifier = Modifier.weight(1f),
                ) { Text("▶ Play") }
                FilledTonalButton(
                    onClick = { adding = true },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text("＋ Add songs") }
            }
            if (entries.isEmpty()) {
                Text("Nothing here yet. Tap Add songs to pick from the library.", color = TextSecondary, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.weight(1f), state = listState) {
                items(local, key = { it.entryId }) { entry ->
                    ReorderableItem(reorderState, key = entry.entryId) {
                        EntryRow(
                            entry = entry,
                            resetKey = removeFailures,
                            handle = Modifier.draggableHandle(
                                onDragStarted = {
                                    dragging = true
                                    dragFrom = local.indexOfFirst { it.entryId == entry.entryId }
                                },
                                onDragStopped = {
                                    dragging = false
                                    val to = local.indexOfFirst { it.entryId == entry.entryId }
                                    if (dragFrom >= 0 && to >= 0 && to != dragFrom) vm.moveEntry(dragFrom, to)
                                    // No move: resync from the repository's own order.
                                    else local = entries
                                },
                            ),
                            onPlay = { vm.play(entries.indexOfFirst { it.entryId == entry.entryId }.coerceAtLeast(0)) },
                            onEdit = { editOpens++; editingEntry = entry },
                            onRemove = { vm.removeEntry(entry.entryId) },
                        )
                    }
                }
            }
        }
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    editingEntry?.let { entry ->
        val editVm: EditSongViewModel = viewModel(
            key = "entry-edit-${entry.entryId}-$editOpens",
            factory = EditSongViewModel.Factory(entry.entryId, entry.songId, graph.repository, graph.playback),
        )
        EditSongSheet(editVm, onClose = { editingEntry = null })
    }

    if (adding) {
        AddSongsSheet(
            library = library,
            inSetlist = entries.mapTo(HashSet()) { it.songId },
            onAdd = { ids ->
                adding = false
                vm.addSongs(ids)
                scope.launch {
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.songs_added, ids.size, ids.size))
                }
            },
            onDismiss = { adding = false },
        )
    }

    if (renaming) {
        NameDialog(
            title = "Rename setlist",
            initial = name.orEmpty(),
            confirmLabel = "Rename",
            onConfirm = { vm.rename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        DeleteSetlistDialog(
            name = name.orEmpty(),
            loaded = playback.setlistId == setlistId,
            onConfirm = { deleting = false; onDelete() },
            onDismiss = { deleting = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryRow(
    entry: EffectiveSong,
    resetKey: Int,
    handle: Modifier,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.Settled) {
                false
            } else {
                onRemove()
                true
            }
        },
    )
    // A row restored by Undo comes back with its saved swipe state still "dismissed".
    LaunchedEffect(resetKey) { if (swipe.currentValue != SwipeToDismissBoxValue.Settled) swipe.snapTo(SwipeToDismissBoxValue.Settled) }
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp)) {
                val end = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
                Text(
                    "Remove",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart),
                )
            }
        },
    ) {
        EntryRowContent(entry = entry, handle = handle, highlighted = false, onClick = onPlay, onEdit = onEdit)
    }
}
