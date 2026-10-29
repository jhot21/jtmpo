package me.jhot.jtmpo.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.importer.ImportMode
import me.jhot.jtmpo.importer.readAtMost
import me.jhot.jtmpo.share.ImportFormat
import me.jhot.jtmpo.share.detectFormat
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.TextSecondary

private suspend fun readBytes(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readAtMost() } ?: error("Cannot open $uri")
}

@Composable
fun ImportScreen(graph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: ImportViewModel = viewModel(factory = ImportViewModel.Factory(graph.repository))
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Configuration-aware, unlike context.resources (lint LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val shared: SharedImportViewModel = viewModel(factory = SharedImportViewModel.Factory(graph.repository))
    val sharedState by shared.state.collectAsStateWithLifecycle()
    val sharedBusy = sharedState == SharedImportState.Loading || sharedState == SharedImportState.Committing
    val scope = rememberCoroutineScope()

    // The file decides which import runs, wherever it came from: a Tempo plist takes the existing path.
    fun route(bytes: ByteArray) = scope.launch {
        if (withContext(Dispatchers.Default) { detectFormat(bytes) } == ImportFormat.TEMPO) {
            shared.cancel()
            vm.load { bytes }
        } else {
            shared.load(bytes)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // A read failure keeps the Tempo import's own message, as before.
                val bytes = runCatching { readBytes(context, uri) }.getOrElse { e -> vm.load { throw e }; return@launch }
                route(bytes)
            }
        }
    }

    // Takes a file opened from another app exactly once: clearing it stops AppNav from navigating here
    // again, and compareAndSet keeps a second collector from taking the same bytes.
    LaunchedEffect(graph) {
        graph.incoming.collect { bytes ->
            if (bytes != null && graph.incoming.compareAndSet(bytes, null)) route(bytes)
        }
    }

    LaunchedEffect(sharedState) {
        val done = sharedState as? SharedImportState.Done ?: return@LaunchedEffect
        val text = resources.getQuantityString(R.plurals.shared_import_done, done.entries, done.name, done.entries)
        // The snackbar outlives this effect, which restarts once the state goes back to Idle.
        scope.launch { snackbar.showSnackbar(text) }
        shared.cancel()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ImportEvent.Done -> {
                    val songs = resources.getQuantityString(R.plurals.song_count, event.newSongs, event.newSongs)
                    val text = if (event.setlistEntries == null) {
                        resources.getString(R.string.import_done_songs, songs)
                    } else {
                        resources.getString(R.string.import_done_setlist, songs, event.setlistEntries)
                    }
                    snackbar.showSnackbar(text)
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = 640.dp).padding(horizontal = 16.dp),
        ) {
            item {
                val backLabel = stringResource(R.string.back_to_settings)
                TextButton(onBack, Modifier.semantics { contentDescription = backLabel }) {
                    Text(stringResource(R.string.back_chevron_label, stringResource(R.string.nav_settings)), color = TextSecondary)
                }
                Text(
                    stringResource(R.string.import_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(stringResource(R.string.import_hint), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.import_hint_jtmpo),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = { pickLauncher.launch(arrayOf("*/*")) },
                    enabled = !ui.busy && !sharedBusy,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text(stringResource(R.string.import_choose_file)) }
            }
            if (ui.hasFile) {
                item { SectionLabel(stringResource(R.string.import_pick_setlists)) }
                itemsIndexed(ui.setlists) { index, setlist ->
                    val checked = index in ui.selected
                    val singleChoice = ui.mode == ImportMode.SONGS_AND_SETLIST
                    val label = stringResource(
                        R.string.import_setlist_option,
                        setlist.title,
                        pluralStringResource(R.plurals.song_count, setlist.songs.size, setlist.songs.size),
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = if (singleChoice) Role.RadioButton else Role.Checkbox,
                                onValueChange = { vm.toggle(index) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row owns the click and the semantics; the control only shows the state.
                        if (singleChoice) {
                            RadioButton(checked, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                        } else {
                            Checkbox(checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Accent))
                        }
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
                item {
                    SectionLabel(stringResource(R.string.import_mode))
                    ModeRow(R.string.import_mode_songs, ui.mode == ImportMode.SONGS_ONLY) { vm.setMode(ImportMode.SONGS_ONLY) }
                    ModeRow(R.string.import_mode_setlist, ui.mode == ImportMode.SONGS_AND_SETLIST) {
                        vm.setMode(ImportMode.SONGS_AND_SETLIST)
                    }
                }
                val preview = ui.preview
                if (preview != null) {
                    item {
                        SectionLabel(stringResource(R.string.import_preview))
                        Text(stringResource(R.string.import_preview_counts, preview.newSongs.size, preview.reused.size))
                        preview.setlist?.let { entries ->
                            val name = ui.selected.singleOrNull()?.let { ui.setlists[it].title }.orEmpty()
                            Text(
                                stringResource(R.string.import_setlist_entries, name, entries.size),
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (preview.conflicts.isNotEmpty()) {
                            Text(
                                stringResource(R.string.import_conflicts_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    itemsIndexed(preview.conflicts) { _, conflict ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(conflict.title)
                            Text(
                                stringResource(
                                    R.string.import_conflict_line,
                                    rhythmSummary(conflict.kept),
                                    rhythmSummary(conflict.other),
                                ),
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = vm::confirm,
                        enabled = ui.canConfirm,
                        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
                    ) { Text(stringResource(if (ui.busy) R.string.import_working else R.string.import_confirm)) }
                }
            }
        }
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    SharedImportDialogs(sharedState, onConfirm = shared::confirm, onAnswer = shared::answer, onCancel = shared::cancel)

    ui.error?.let { error ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.import_error_title)) },
            text = {
                Text(
                    stringResource(
                        when (error) {
                            ImportError.UNREADABLE -> R.string.import_error_unreadable
                            ImportError.EMPTY -> R.string.import_error_empty
                            ImportError.COMMIT_FAILED -> R.string.import_error_commit
                        },
                    ),
                )
            },
            confirmButton = { TextButton(vm::dismissError) { Text(stringResource(R.string.import_error_ok)) } },
        )
    }
}

@Composable
private fun rhythmSummary(rhythm: Rhythm): String {
    val click = "1/${rhythm.pattern.noteDenominator}" + if (rhythm.pattern.dotted) "." else ""
    return stringResource(R.string.import_rhythm_summary, rhythm.tempo, rhythm.timeSignature.toString(), click)
}

@Composable
private fun ModeRow(label: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent))
        Text(stringResource(label), Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
}
