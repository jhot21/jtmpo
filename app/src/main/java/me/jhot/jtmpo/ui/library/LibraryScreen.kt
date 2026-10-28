package me.jhot.jtmpo.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
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
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.edit.EditSongSheet
import me.jhot.jtmpo.ui.edit.EditSongViewModel
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.SurfaceRaised
import me.jhot.jtmpo.ui.theme.TextSecondary

private fun patternText(p: ClickPattern) = "click " + (if (p.dotted) "dotted " else "") + "1/${p.noteDenominator}"

private fun detailLine(song: Song): String = buildList {
    song.artist?.takeIf { it.isNotBlank() }?.let(::add)
    add(song.rhythm.tempo.toString())
    add("${song.rhythm.timeSignature.numerator}/${song.rhythm.timeSignature.denominator}")
    add(patternText(song.rhythm.pattern))
}.joinToString(" · ")

/** The song being edited: [songId] null means a new song. */
private data class EditTarget(val songId: Long?)

private val sortLabels = listOf(
    LibrarySort.AZ to "A–Z",
    LibrarySort.TEMPO to "Tempo",
    LibrarySort.RECENT to "Recently added",
)

@Composable
fun LibraryScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(graph.repository))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val setlists by vm.setlists.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var addingSongId by remember { mutableStateOf<Long?>(null) }
    // Bumped on every open so each sheet gets a fresh view model (no stale draft, no leftover "finished").
    var editOpens by remember { mutableIntStateOf(0) }

    LaunchedEffect(ui.undoTitle) {
        val title = ui.undoTitle ?: return@LaunchedEffect
        val result = snackbar.showSnackbar("Deleted “$title”", "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.dismissUndo()
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "  · " + pluralStringResource(R.plurals.song_count, ui.totalCount, ui.totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search songs or artists") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceRaised,
                    unfocusedContainerColor = SurfaceRaised,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sortLabels.forEach { (sort, label) ->
                    FilterChip(
                        selected = ui.sort == sort,
                        onClick = { vm.setSort(sort) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.White,
                            selectedLabelColor = Color.Black,
                        ),
                    )
                }
            }
            if (ui.songs.isEmpty()) {
                Text(
                    if (ui.totalCount == 0) "No songs yet. Tap New song to add one." else "No songs match your search.",
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(ui.songs, key = { it.id }) { song ->
                    SongRow(
                        song,
                        onEdit = { editOpens++; editing = EditTarget(song.id) },
                        onDelete = { vm.requestDelete(song.id) },
                        onAddToSetlist = { addingSongId = song.id },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editOpens++; editing = EditTarget(null) },
            containerColor = Color.White,
            contentColor = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("＋ New song") }
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    editing?.let { target ->
        val editVm: EditSongViewModel = viewModel(
            key = "library-edit-${target.songId}-$editOpens",
            factory = EditSongViewModel.Factory(null, target.songId, graph.repository, graph.playback),
        )
        EditSongSheet(editVm, onClose = { editing = null })
    }

    ui.pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("Delete “${pending.title}”?") },
            text = {
                Text(
                    if (pending.setlistNames.isEmpty()) {
                        "It is not in any setlist."
                    } else {
                        "It will also be removed from: ${pending.setlistNames.joinToString(", ")}."
                    },
                )
            },
            confirmButton = { TextButton({ vm.confirmDelete(pending.songId) }) { Text("Delete") } },
            dismissButton = { TextButton(vm::cancelDelete) { Text("Cancel") } },
        )
    }

    addingSongId?.let { songId ->
        AlertDialog(
            onDismissRequest = { addingSongId = null },
            title = { Text("Add to setlist") },
            text = {
                if (setlists.isEmpty()) {
                    Text("Create a setlist first.")
                } else {
                    LazyColumn {
                        items(setlists, key = { it.id }) { list ->
                            Text(
                                list.name,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        addingSongId = null
                                        scope.launch {
                                            vm.addToSetlist(list.id, songId).join()
                                            snackbar.showSnackbar("Added to “${list.name}”")
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ addingSongId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SongRow(song: Song, onEdit: () -> Unit, onDelete: () -> Unit, onAddToSetlist: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detailLine(song),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            val actions = stringResource(R.string.song_actions, song.title)
            IconButton({ menuOpen = true }, Modifier.semantics { contentDescription = actions }) {
                Text("⋮", color = Accent.takeIf { menuOpen } ?: TextSecondary)
            }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("Add to setlist") }, onClick = { menuOpen = false; onAddToSetlist() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}
