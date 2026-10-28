package me.jhot.jtmpo.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.components.TempoDragControl
import me.jhot.jtmpo.ui.edit.EditSongSheet
import me.jhot.jtmpo.ui.edit.EditSongViewModel
import me.jhot.jtmpo.ui.setlists.AddSongsSheet
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.SurfaceCard
import me.jhot.jtmpo.ui.theme.TextSecondary

@Composable
fun PlayerScreen(graph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(graph.playback, graph.repository, graph.settings))
    PlayerContent(vm, graph, graph.audioOutput.pulses, onBack, modifier)
}

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
    var queueOpen by remember { mutableStateOf(false) }
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

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(ui, onBack)
                if (!ui.hasSong) {
                    Text(
                        "Nothing loaded. Open a setlist and tap a song.",
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 48.dp),
                    )
                } else {
                    SongInfo(ui)
                    TempoCard(ui, pulses) { tempo -> if (editUi?.loaded == true) editVm.setTempo(tempo) }
                    Chips(
                        ui,
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
            // Outside the scroll area so it sits right above the sheet handle on tall screens.
            if (ui.hasSong) Transport(ui, vm)
            UpNext(entries, ui, onOpen = { queueOpen = true })
        }
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    chipEditing?.let { sheetVm ->
        EditSongSheet(sheetVm, onClose = { chipEditing = null; generation++ })
    }

    if (queueOpen) {
        QueueSheet(
            setlistName = ui.setlistName,
            entries = entries,
            currentEntryId = ui.entryId,
            snackbar = snackbar,
            onSelect = vm::select,
            onMove = vm::moveEntry,
            onEdit = { entry ->
                // The current song is edited through the player's own view model, so the tempo drag,
                // the chips and the pencil all share one draft.
                if (entry.entryId == ui.entryId && editUi?.loaded == true) chipEditing = editVm
                else { otherOpens++; editingOther = entry }
            },
            onAdd = { adding = true },
            onDismiss = { queueOpen = false },
        )
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
private fun Header(ui: PlayerUi, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun SongInfo(ui: PlayerUi) {
    Column(Modifier.padding(vertical = 16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            ui.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun TempoCard(ui: PlayerUi, pulses: SharedFlow<Unit>, onTempo: (Int) -> Unit) {
    // Driven by the audio output's own timing, not a UI timer: one flash per audible click.
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulses) {
        // collectLatest: a new click restarts the flash instead of queueing behind the previous one.
        pulses.collectLatest {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = 160))
        }
    }
    Surface(
        Modifier.fillMaxWidth().widthIn(max = 480.dp),
        color = SurfaceCard,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${ui.tempo}",
                fontSize = 84.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 88.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer {
                    val p = pulse.value
                    scaleX = 1f + 0.08f * p
                    scaleY = 1f + 0.08f * p
                    alpha = 0.72f + 0.28f * p
                },
            )
            Text(
                "${stringResource(R.string.bpm)} ${ui.tempoUnit}".trim(),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                letterSpacing = 2.sp,
            )
            TempoDragControl(ui.tempo, onTempo, Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Chips(ui: PlayerUi, onEdit: () -> Unit) {
    Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun Transport(ui: PlayerUi, vm: PlayerViewModel) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(vertical = 16.dp),
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

/** Handle at the bottom: a tap or an upward drag pulls up the full setlist. */
@Composable
private fun UpNext(entries: List<EffectiveSong>, ui: PlayerUi, onOpen: () -> Unit) {
    val index = entries.indexOfFirst { it.entryId == ui.entryId }
    val next = entries.getOrNull(index + 1)
    val label = stringResource(R.string.open_setlist)
    Surface(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onVerticalDrag = { _, dy ->
                        drag += dy
                        if (drag < -40f) { drag = 0f; onOpen() }
                    },
                )
            }
            .clickable(onClick = onOpen),
        color = SurfaceCard,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(TextSecondary, CircleShape))
            Text(
                buildString {
                    append(stringResource(R.string.up_next).uppercase())
                    if (next != null) append(" — ${next.title} · ${next.rhythm.tempo}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
