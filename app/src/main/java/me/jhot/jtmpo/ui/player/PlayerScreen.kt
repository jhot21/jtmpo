package me.jhot.jtmpo.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.components.TempoCard
import me.jhot.jtmpo.ui.edit.EditSongSheet
import me.jhot.jtmpo.ui.edit.EditSongViewModel
import me.jhot.jtmpo.ui.setlists.AddSongsSheet
import me.jhot.jtmpo.ui.theme.SurfaceCard
import me.jhot.jtmpo.ui.theme.TextSecondary

@Composable
fun PlayerScreen(graph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(graph.playback, graph.repository, graph.settings))
    PlayerContent(vm, graph, graph.audioOutput.pulses, onBack, modifier)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    vm: PlayerViewModel,
    graph: AppGraph,
    pulses: SharedFlow<Unit>,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    // The queue is a non-modal sheet with three stops: collapsed (the "Up next" bar), half (five rows) and full. It
    // follows the finger, and the player stays usable at half.
    val sheet = remember { AnchoredDraggableState(QueueStop.Collapsed) }
    val listState = rememberLazyListState()
    var headerPx by remember { mutableIntStateOf(0) }
    var rowPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerDp = if (headerPx > 0) with(density) { headerPx.toDp() } else 72.dp
    val rowDp = if (rowPx > 0) with(density) { rowPx.toDp() } else 56.dp
    val peek = queuePeekHeight(QueueHandleHeight, headerDp, rowDp, entries.size)
    // True from the moment a drag or tap is heading for half or full: the player compacts while the sheet opens.
    val queueShown = sheet.targetValue != QueueStop.Collapsed
    BackHandler(enabled = sheet.currentValue != QueueStop.Collapsed) { scope.launch { sheet.animateTo(QueueStop.Collapsed) } }
    // At the half stop the playing song must be on screen. Keyed on the settled stop and the current entry only, so
    // reordering or editing the list does not undo the user's own scrolling, and only scrolls when it is out of view.
    val currentIndex = entries.indexOfFirst { it.entryId == ui.entryId }
    LaunchedEffect(sheet.settledValue == QueueStop.Half, ui.entryId) {
        if (sheet.settledValue != QueueStop.Half) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == currentIndex }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        queueScrollTarget(currentIndex, listState.firstVisibleItemIndex, fullyVisible)?.let { listState.animateScrollToItem(it) }
    }
    var adding by remember { mutableStateOf(false) }
    var editingOther by remember { mutableStateOf<EffectiveSong?>(null) }
    // Bumped on every open so each sheet gets a fresh view model (no stale draft, no leftover "finished").
    var otherOpens by remember { mutableIntStateOf(0) }
    var chipEditing by remember { mutableStateOf<EditSongViewModel?>(null) }

    // One edit view model for the current entry drives the tempo control, the unsaved-change bar and
    // the chips' sheet, so every change goes through the same live preview and save path. It is rebuilt
    // when the entry changes, when its saved values change elsewhere, and after a revert or sheet close.
    var generation by remember { mutableIntStateOf(0) }
    val current = entries.firstOrNull { it.entryId == ui.entryId }
    val editVm: EditSongViewModel? = current?.let { c ->
        viewModel(
            key = "player-edit-${c.entryId}-${listOf(c.rhythm, c.notes, c.rhythmOverridden, c.notesOverridden).hashCode()}-$generation",
            factory = EditSongViewModel.Factory(c.entryId, c.songId, graph.repository, graph.playback),
        )
    }
    val editUi = editVm?.ui?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(editUi?.activeOverrideText) {
        editUi?.activeOverrideText?.let { snackbar.showSnackbar(it) }
    }

    DisposableEffect(graph) {
        graph.playerOpen.value = true
        onDispose { graph.playerOpen.value = false }
    }

    // The app's Scaffold already pads this screen by the status and navigation bars, so the sheet needs no insets of
    // its own; clipping keeps the collapsed sheet from drawing into the navigation bar.
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val containerPx = constraints.maxHeight.toFloat()
        // Fixed height: the full size, or less when the setlist is short.
        val sheetHeight = minOf(maxHeight, QueueHandleHeight + headerDp + rowDp * entries.size)
        val collapsedPx = with(density) { (QueueHandleHeight + QueueCollapsedLabelHeight).toPx() }
        val halfPx = with(density) { minOf(peek, sheetHeight).toPx() }
        val fullPx = with(density) { sheetHeight.toPx() }
        val gapPx = with(density) { 20.dp.toPx() }
        val minFlingVelocity = with(density) { 500.dp.toPx() }
        val baseFling = AnchoredDraggableDefaults.flingBehavior(sheet)
        val fling = remember(sheet, baseFling, minFlingVelocity) { DistanceFirstFling(sheet, baseFling, minFlingVelocity) }
        val anchors = remember(containerPx, collapsedPx, halfPx, fullPx) {
            DraggableAnchors {
                QueueStop.Collapsed at containerPx - collapsedPx
                if (halfPx > collapsedPx + 1f) QueueStop.Half at containerPx - halfPx
                if (fullPx > halfPx + 1f) QueueStop.Full at containerPx - fullPx
            }
        }
        // Applied before layout; until then sheetOffset() falls back to the collapsed position.
        SideEffect { sheet.updateAnchors(anchors) }
        val collapsedOffset = containerPx - collapsedPx
        val halfOffset = containerPx - halfPx
        // Read in layout and draw lambdas only, so a drag does not recompose the player.
        fun sheetOffset() = sheet.offset.takeUnless { it.isNaN() } ?: collapsedOffset
        fun openProgress() = if (collapsedOffset - halfOffset < 1f) 1f
        else ((collapsedOffset - sheetOffset()) / (collapsedOffset - halfOffset)).coerceIn(0f, 1f)

        // Pulling the list down from the top at full height drags the sheet down with it. At half the list just scrolls,
        // so a stray drag on the songs cannot close the sheet.
        val pullDown = remember(sheet, fling) {
            object : NestedScrollConnection {
                // currentValue flips to the nearest stop while dragging, so "pulling" is tracked here instead.
                private var pulling = false

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                    if (pulling && source == NestedScrollSource.UserInput && available.y < 0f) {
                        Offset(0f, sheet.dispatchRawDelta(available.y))
                    } else Offset.Zero

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    if (!pulling && sheet.settledValue != QueueStop.Full) return Offset.Zero
                    pulling = true
                    return Offset(0f, sheet.dispatchRawDelta(available.y))
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (!pulling) return Velocity.Zero
                    pulling = false
                    fling.settleAfterPull(available.y)
                    return available
                }
            }
        }

        // The player, with room kept below it for however much of the sheet is showing.
        Box(
            Modifier.fillMaxSize().layout { measurable, c ->
                val visible = containerPx - sheetOffset()
                val pad = (minOf(visible, halfPx) + gapPx).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(c.maxWidth, (c.maxHeight - pad).coerceAtLeast(0)))
                layout(c.maxWidth, c.maxHeight) { placeable.place(0, 0) }
            },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewportHeight = maxHeight
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Header(ui, onBack, compact = queueShown)
                        if (!ui.hasSong) {
                            Text(
                                "Nothing loaded. Open a setlist and tap a song.",
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 48.dp),
                            )
                        } else {
                            SongInfo(ui, compact = queueShown)
                            // With the queue open the card takes whatever height the other pieces leave, so there is no
                            // empty gap above the transport row.
                            TempoCard(
                                modifier = if (queueShown) Modifier.weight(1f) else Modifier,
                                compact = queueShown,
                                tempo = ui.tempo,
                                unitLabel = ui.tempoUnit,
                                pulses = pulses,
                                onTempo = { tempo -> if (editUi?.loaded == true) editVm.setTempo(tempo) },
                            )
                            Chips(
                                ui,
                                compact = queueShown,
                                onEdit = { if (editUi?.loaded == true) chipEditing = editVm },
                            )
                            if (ui.unsaved && editVm != null) {
                                UnsavedBar(
                                    enabled = editUi?.loaded == true,
                                    // A save finishes the view model; a fresh one takes over once it is done.
                                    onSaveSetlist = { scope.launch { editVm.saveToSetlist()?.join(); generation++ } },
                                    onSaveLibrary = { scope.launch { editVm.saveToLibrary()?.join(); generation++ } },
                                    onRevert = {
                                        editVm.revert()
                                        generation++
                                    },
                                )
                            }
                        }
                    }
                }
                // Outside the scroll area so it sits right above the sheet on tall screens.
                if (ui.hasSong) Transport(ui, vm, compact = queueShown)
            }
        }

        // The sheet: fixed height, positioned by its drag state. A drag on the handle, header or collapsed bar moves
        // it; the song list scrolls on its own.
        val next = entries.getOrNull(currentIndex + 1)
        val collapsedLabel = buildString {
            append(stringResource(R.string.up_next).uppercase())
            if (next != null) append(" \u2014 ${next.title} \u00b7 ${next.rhythm.tempo}")
        }
        Box(
            Modifier.fillMaxWidth().height(sheetHeight)
                .offset { IntOffset(0, sheetOffset().roundToInt()) }
                .anchoredDraggable(
                    sheet,
                    Orientation.Vertical,
                    flingBehavior = fling,
                )
                .nestedScroll(pullDown)
                .background(BottomSheetDefaults.ContainerColor, BottomSheetDefaults.ExpandedShape),
        ) {
            QueueSheetContent(
                setlistName = ui.setlistName,
                entries = entries,
                currentEntryId = ui.entryId,
                listState = listState,
                visibleHeight = { with(density) { (containerPx - sheetOffset()).toDp() } },
                collapsed = sheet.settledValue == QueueStop.Collapsed,
                openProgress = ::openProgress,
                collapsedLabel = collapsedLabel,
                onOpen = { scope.launch { sheet.animateTo(QueueStop.Half) } },
                onMeasured = { h, r -> if (h > 0) headerPx = h; if (r > 0) rowPx = r },
                onSelect = vm::select,
                onMove = vm::moveEntry,
                onEdit = { entry ->
                    // The current song is edited through the player's own view model, so the tempo drag,
                    // the chips and the pencil all share one draft.
                    if (entry.entryId == ui.entryId && editUi?.loaded == true) chipEditing = editVm
                    else { otherOpens++; editingOther = entry }
                },
                onAdd = { adding = true },
            )
        }
        // One host, drawn above the sheet, so "songs added" and override notices are visible at half.
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    chipEditing?.let { sheetVm ->
        EditSongSheet(sheetVm, onClose = { chipEditing = null; generation++ })
    }

    editingOther?.let { entry ->
        val otherVm: EditSongViewModel = viewModel(
            key = "queue-edit-${entry.entryId}-$otherOpens",
            factory = EditSongViewModel.Factory(entry.entryId, entry.songId, graph.repository, graph.playback),
        )
        EditSongSheet(otherVm, onClose = { editingOther = null })
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
}

@Composable
private fun Header(ui: PlayerUi, onBack: () -> Unit, compact: Boolean) {
    val minHeight by animateDpAsState(if (compact) 48.dp else 64.dp, label = "headerHeight")
    Row(Modifier.fillMaxWidth().heightIn(min = minHeight), verticalAlignment = Alignment.CenterVertically) {
        val closeLabel = stringResource(R.string.close_player)
        IconButton(onBack, Modifier.semantics { contentDescription = closeLabel }) { Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(32.dp)) }
        Text(
            ui.positionText,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Balances the close button so the position stays centred.
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun SongInfo(ui: PlayerUi, compact: Boolean) {
    val verticalPadding by animateDpAsState(if (compact) 4.dp else 16.dp, label = "songInfoPadding")
    Column(Modifier.padding(vertical = verticalPadding).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ui.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Artist and notes drop out while the setlist is open, to leave room for the controls.
        AnimatedVisibility(!compact) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ui.artist?.let { Text(it, color = TextSecondary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                ui.notes?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Chips(ui: PlayerUi, compact: Boolean, onEdit: () -> Unit) {
    val verticalPadding by animateDpAsState(if (compact) 2.dp else 14.dp, label = "chipsPadding")
    Row(Modifier.padding(vertical = verticalPadding), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val sigLabel = stringResource(R.string.edit_time_signature, ui.timeSignature)
        AssistChip(onClick = onEdit, label = { Text(ui.timeSignature) }, modifier = Modifier.semantics { contentDescription = sigLabel })
        val patLabel = stringResource(R.string.edit_click_pattern, ui.patternLabel)
        AssistChip(onClick = onEdit, label = { Text("Click ${ui.patternLabel}") }, modifier = Modifier.semantics { contentDescription = patLabel })
        if (ui.overridden) {
            val badge = stringResource(R.string.overridden_here)
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.semantics { contentDescription = badge },
            ) { Text("set", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun UnsavedBar(enabled: Boolean, onSaveSetlist: () -> Unit, onSaveLibrary: () -> Unit, onRevert: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Unsaved change", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        // Two rows: three buttons in one row clipped "Save to setlist" on a 411 dp phone at larger font
        // scales. Labels may wrap to two lines rather than being cut off; every target stays >= 48 dp tall.
        val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        Row(
            Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 4.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onSaveSetlist, Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), enabled = enabled, contentPadding = padding) {
                Text("Save to setlist", textAlign = TextAlign.Center, maxLines = 2)
            }
            OutlinedButton(onSaveLibrary, Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), enabled = enabled, contentPadding = padding) {
                Text("Save to library", textAlign = TextAlign.Center, maxLines = 2)
            }
        }
        TextButton(onRevert, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Revert") }
    }
}

@Composable
private fun Transport(ui: PlayerUi, vm: PlayerViewModel, compact: Boolean) {
    val verticalPadding by animateDpAsState(if (compact) 8.dp else 16.dp, label = "transportPadding")
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(vm::previous, Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_next), stringResource(R.string.previous), Modifier.size(36.dp).graphicsLayer { rotationZ = 180f })
        }
        FilledIconButton(
            vm::togglePlay,
            Modifier.size(84.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                painterResource(if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (ui.isPlaying) R.string.pause else R.string.play),
                Modifier.size(44.dp),
            )
        }
        IconButton(vm::next, Modifier.size(64.dp)) {
            Icon(painterResource(R.drawable.ic_next), stringResource(R.string.next), Modifier.size(36.dp))
        }
    }
}
