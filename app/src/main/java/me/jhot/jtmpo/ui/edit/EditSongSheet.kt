package me.jhot.jtmpo.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.COMMON_SIGNATURES
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.patternsFor
import me.jhot.jtmpo.ui.components.TempoDragControl
import me.jhot.jtmpo.ui.theme.TextSecondary

private fun patternLabel(p: ClickPattern) = (if (p.dotted) "dotted " else "") + "1/${p.noteDenominator}"

/** Bottom sheet; call [EditSongViewModel.dismiss] semantics happen here when the sheet leaves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSongSheet(vm: EditSongViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Read here, not with navigationBarsPadding() inside the sheet: that padding follows the sheet's position,
    // so it changes the sheet's height mid-drag, which moves the drag anchors and snaps the sheet back.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Same reason for the top: the sheet reports its own offset as consumed insets, so a top inset applied through
    // contentWindowInsets shrinks as the sheet is dragged down and, once the content overflows, resizes the sheet mid-drag.
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    // dismiss() is a no-op after a save, so one path serves cancel, swipe-away and finish.
    fun close() {
        vm.dismiss()
        onClose()
    }
    LaunchedEffect(ui.finished) { if (ui.finished) close() }
    // Cancelling restores what was playing when the sheet opened, not what the song had saved.
    LaunchedEffect(vm) { vm.openSheet() }

    // Not before the song has loaded: a sheet that opens empty computes its anchors for the handle alone, and
    // the anchors it gets when the content arrives afterwards do not always take, leaving it resting mostly off-screen.
    if (!ui.loaded) return
    ModalBottomSheet(
        onDismissRequest = ::close,
        sheetState = sheetState,
        modifier = Modifier.padding(top = topInset),
        // The padding above keeps the sheet below the status bar and camera cutout; the insets are padded by hand.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        EditSongContent(ui, vm, bottomInset, onCancel = ::close)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditSongContent(ui: EditUi, vm: EditSongViewModel, bottomInset: Dp, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.isEntry) {
            Text(ui.setlistSongTitle, style = MaterialTheme.typography.titleLarge)
        } else {
            OutlinedTextField(
                ui.title, vm::setTitle, label = { Text("Title") }, singleLine = true,
                isError = ui.title.isBlank() || ui.duplicate,
                supportingText = if (ui.duplicate) {
                    { Text("A song with this title and artist already exists.") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(ui.artist, vm::setArtist, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Label("TEMPO")
        TempoDragControl(ui.rhythm.tempo, vm::setTempo)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TempoField(ui.rhythm.tempo, vm)
            PreviewButton(ui.previewing, vm::togglePreview)
        }
        Text("${ui.clicksPerMinute.toInt()} clicks per minute", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Label("TIME SIGNATURE")
        SignaturePicker(ui.rhythm.timeSignature, vm::setTimeSignature)

        Label("CLICK EVERY")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            patternsFor(ui.rhythm.timeSignature).forEach { p ->
                FilterChip(ui.rhythm.pattern == p, { vm.setPattern(p) }, label = { Text(patternLabel(p)) })
            }
        }

        OutlinedTextField(
            ui.notes, vm::setNotes, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )

        val overrideText = ui.activeOverrideText
        if (overrideText != null) {
            Text(overrideText, color = MaterialTheme.colorScheme.secondary)
        } else if (ui.isEntry && ui.entryOverridden) {
            val lib = ui.libraryRhythm
            Text("Library value: ${lib?.timeSignature} \u00B7 ${lib?.tempo}", color = TextSecondary)
        }
        if (ui.isEntry && (ui.entryOverridden || ui.notesOverridden)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.entryOverridden) TextButton({ vm.clearOverride() }) { Text(stringResource(R.string.clear_rhythm_override)) }
                if (ui.notesOverridden) TextButton({ vm.clearNotesOverride() }) { Text("Clear notes override") }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.isEntry) {
                Button({ vm.saveToSetlist() }, Modifier.weight(1f), enabled = ui.canSave) { Text("Save to this setlist") }
            }
            if (ui.isEntry) {
                OutlinedButton({ vm.saveToLibrary() }, Modifier.weight(1f), enabled = ui.canSave) { Text("Save to library") }
            } else {
                Button({ vm.saveToLibrary() }, Modifier.weight(1f), enabled = ui.canSave) { Text("Save to library") }
            }
        }
        TextButton(onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = TextSecondary)

/** Auditions the draft rhythm so the user can match it against a recording. */
@Composable
private fun PreviewButton(previewing: Boolean, onToggle: () -> Unit) {
    FilledTonalButton(onClick = onToggle) {
        Icon(
            painterResource(if (previewing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(if (previewing) R.string.stop_preview else R.string.preview_click),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Typed BPM: only digits are accepted; the draft changes only for a whole number (clamped by the VM). */
@Composable
private fun TempoField(tempo: Int, vm: EditSongViewModel) {
    var text by remember { mutableStateOf(tempo.toString()) }
    var focused by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Follow drags and +/- unless the user is typing.
    LaunchedEffect(tempo) { if (!focused) text = tempo.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            // Typing stays local; playback only hears the value on commit.
            if (input.length <= 3 && input.all(Char::isDigit)) text = input
        },
        keyboardActions = KeyboardActions(onDone = {
            vm.commitTempoInput(text)
            focusManager.clearFocus()
        }),
        label = { Text("BPM") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        isError = text.toIntOrNull() == null,
        modifier = Modifier.width(120.dp).onFocusChanged {
            // Compose reports an initial unfocused state; only a real focus-then-blur may commit.
            if (it.isFocused) hadFocus = true
            if (!it.isFocused && hadFocus) {
                hadFocus = false
                if (text != tempo.toString()) vm.commitTempoInput(text)
                text = tempo.toString()
            }
            focused = it.isFocused
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignaturePicker(current: TimeSignature, onPick: (TimeSignature) -> Unit) {
    var otherOpen by remember { mutableStateOf(current !in COMMON_SIGNATURES) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COMMON_SIGNATURES.forEach { ts ->
            FilterChip(current == ts && !otherOpen, { otherOpen = false; onPick(ts) }, label = { Text(ts.toString()) })
        }
        FilterChip(otherOpen, { otherOpen = true }, label = { Text("Other…") })
    }
    if (otherOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton({ onPick(current.copy(numerator = (current.numerator - 1).coerceAtLeast(1))) }) { Text("−") }
            Text("${current.numerator}", style = MaterialTheme.typography.titleLarge)
            FilledTonalIconButton({ onPick(current.copy(numerator = (current.numerator + 1).coerceAtMost(32))) }) { Text("+") }
            Text("/", style = MaterialTheme.typography.titleLarge)
            listOf(2, 4, 8, 16).forEach { d ->
                FilterChip(current.denominator == d, { onPick(current.copy(denominator = d)) }, label = { Text("$d") })
            }
        }
    }
}
