package me.jhot.jtmpo.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.ui.components.JtmpoSnackbarHost
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.TextSecondary

private fun backupFileName(today: LocalDate = LocalDate.now()) = "jtmpo-backup-$today.json"

private suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Cannot open $uri")
}

private suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    // "wt" truncates, so overwriting a longer existing file leaves no stale tail.
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: error("Cannot open $uri")
}

/**
 * [onImportFzt] opens the Tempo importer. While it is null the row shows but is disabled.
 */
@Composable
fun SettingsScreen(graph: AppGraph, modifier: Modifier = Modifier, onImportFzt: (() -> Unit)? = null) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(graph.repository, graph.settings, graph.playback),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val messages = SettingsMessage.entries.associateWith { m ->
        stringResource(
            when (m) {
                SettingsMessage.EXPORTED -> R.string.backup_exported
                SettingsMessage.EXPORT_FAILED -> R.string.backup_export_failed
                SettingsMessage.RESTORED -> R.string.backup_restored
                SettingsMessage.RESTORE_UNREADABLE -> R.string.backup_unreadable
                SettingsMessage.RESTORE_NEWER -> R.string.backup_newer
                SettingsMessage.RESTORE_DUPLICATES -> R.string.backup_duplicates
                SettingsMessage.RESTORE_FAILED -> R.string.backup_restore_failed
            },
        )
    }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(messages.getValue(it)) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export { writeText(context, uri, it) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadBackup { readText(context, uri) }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).widthIn(max = 640.dp)) {
            Text(
                stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 14.dp),
            )
            SwitchRow(stringResource(R.string.keep_awake), ui.keepAwake) { vm.setKeepAwake(!ui.keepAwake) }
            SwitchRow(stringResource(R.string.flip_screen), ui.flipScreen) { vm.setFlipScreen(!ui.flipScreen) }
            if (ui.flipScreen) {
                SwitchRow(stringResource(R.string.flip_player_only), ui.flipPlayerOnly) { vm.setFlipPlayerOnly(!ui.flipPlayerOnly) }
            }
            val pulseLeadLabel = stringResource(R.string.pulse_lead)
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pulseLeadLabel, Modifier.weight(1f))
                    Text(stringResource(R.string.pulse_lead_value, ui.pulseLeadMs), color = TextSecondary)
                }
                Slider(
                    value = ui.pulseLeadMs.toFloat(),
                    onValueChange = { vm.setPulseLeadMs(it.roundToInt()) },
                    valueRange = 0f..SettingsStore.MAX_PULSE_LEAD_MS.toFloat(),
                    steps = SettingsStore.MAX_PULSE_LEAD_MS / 5 - 1,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    modifier = Modifier.semantics { contentDescription = pulseLeadLabel },
                )
                Text(
                    stringResource(R.string.pulse_lead_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            SectionLabel(stringResource(R.string.settings_data))
            SettingsRow(
                stringResource(R.string.import_tempo),
                enabled = onImportFzt != null,
                onClick = { onImportFzt?.invoke() },
            )
            SettingsRow(stringResource(R.string.export_backup), onClick = { exportLauncher.launch(backupFileName()) })
            SettingsRow(
                stringResource(R.string.restore_backup),
                onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
            SectionLabel(stringResource(R.string.settings_about))
            Text(
                stringResource(R.string.click_sound, stringResource(R.string.click_sound_name)),
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        JtmpoSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    ui.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.restore_title)) },
            text = {
                Text(
                    stringResource(R.string.restore_message) + " " +
                        pluralStringResource(R.plurals.song_count, pending.songs, pending.songs) + ", " +
                        pluralStringResource(R.plurals.setlist_count, pending.setlists, pending.setlists) + ".",
                )
            },
            confirmButton = { TextButton(vm::confirmRestore) { Text(stringResource(R.string.restore_confirm)) } },
            dismissButton = { TextButton(vm::cancelRestore) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch, onClick = onToggle).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            // The row handles the click; the switch just reflects the state.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
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

@Composable
private fun SettingsRow(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else TextSecondary.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
