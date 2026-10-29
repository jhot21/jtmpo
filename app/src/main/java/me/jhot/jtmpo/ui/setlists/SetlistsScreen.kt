package me.jhot.jtmpo.ui.setlists

import android.text.format.DateUtils
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.SetlistSummary
import me.jhot.jtmpo.share.shareSetlistFile
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.components.SetlistCover
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.TextSecondary
import androidx.compose.material3.FilterChipDefaults

private sealed interface Dialog {
    data object Create : Dialog
    data class Rename(val setlist: SetlistSummary) : Dialog
    data class Delete(val setlist: SetlistSummary) : Dialog
}

private val sortLabels = listOf(SetlistSort.RECENT to "Recent", SetlistSort.AZ to "A–Z")

@Composable
fun SetlistsScreen(graph: AppGraph, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm: SetlistsViewModel = viewModel(factory = SetlistsViewModel.Factory(graph.repository))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val playback by graph.playback.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.share_failed)
    val scope = rememberCoroutineScope()

    // Also shown after a delete from the setlist's own screen, which deletes through this view model.
    LaunchedEffect(undo) {
        val offer = undo ?: return@LaunchedEffect
        val result = snackbar.showSnackbar("Deleted “${offer.name}”", "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete() else vm.dismissUndo()
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Setlists",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
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
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            if (ui.setlists.isEmpty()) {
                Text(
                    "No setlists yet. Tap New setlist to make one.",
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(ui.setlists, key = { it.id }) { setlist ->
                    SetlistRow(
                        setlist,
                        showLastPlayed = ui.sort == SetlistSort.RECENT,
                        onOpen = { onOpen(setlist.id) },
                        onShare = {
                            scope.launch {
                                val (name, json) = vm.shareJson(setlist.id) ?: return@launch
                                if (!context.shareSetlistFile(name, json)) snackbar.showSnackbar(shareFailed)
                            }
                        },
                        onRename = { dialog = Dialog.Rename(setlist) },
                        onDelete = { dialog = Dialog.Delete(setlist) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { dialog = Dialog.Create },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("＋ New setlist") }
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (val d = dialog) {
        Dialog.Create -> NameDialog(
            title = "New setlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                dialog = null
                vm.create(name, onOpen)
            },
            onDismiss = { dialog = null },
        )
        is Dialog.Rename -> NameDialog(
            title = "Rename setlist",
            initial = d.setlist.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                dialog = null
                vm.rename(d.setlist.id, name)
            },
            onDismiss = { dialog = null },
        )
        is Dialog.Delete -> DeleteSetlistDialog(
            name = d.setlist.name,
            loaded = playback.setlistId == d.setlist.id,
            onConfirm = {
                dialog = null
                vm.delete(d.setlist.id)
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun SetlistRow(
    setlist: SetlistSummary,
    showLastPlayed: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = pluralStringResource(R.plurals.song_count, setlist.songCount, setlist.songCount)
    val lastPlayed = setlist.lastPlayedAt?.takeIf { showLastPlayed }?.let {
        "last played " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
            .toString().replaceFirstChar(Char::lowercaseChar)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SetlistCover(setlist.name)
        Column(Modifier.weight(1f)) {
            Text(setlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(count, lastPlayed).joinToString(" · "),
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
                    enabled = setlist.songCount > 0,
                    onClick = { menuOpen = false; onShare() },
                )
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = {
            TextButton({ onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun DeleteSetlistDialog(name: String, loaded: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “$name”?") },
        text = {
            val base = "The setlist is removed. Its songs stay in the library."
            Text(if (loaded) "$base It is loaded in the player, so playback will stop." else base)
        },
        confirmButton = { TextButton(onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
