package me.jhot.jtmpo.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharedFlow
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.practice.RampDirection
import me.jhot.jtmpo.ui.components.SignaturePicker
import me.jhot.jtmpo.ui.components.TempoCard
import me.jhot.jtmpo.ui.player.patternLabel
import me.jhot.jtmpo.ui.theme.TextSecondary

@Composable
fun PracticeScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val vm: PracticeViewModel = viewModel(factory = PracticeViewModel.Factory(graph.practice))
    PracticeContent(vm, graph, graph.audioOutput.pulses, modifier)
}

@Composable
private fun PracticeContent(vm: PracticeViewModel, graph: AppGraph, pulses: SharedFlow<Unit>, modifier: Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    DisposableEffect(graph) {
        // The activity's keep-awake and orientation rules read this while Practice is on screen.
        graph.practiceOpen.value = true
        onDispose { graph.practiceOpen.value = false }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(onRotate = vm::toggleFlip)
            TempoCard(
                tempo = ui.tempo,
                unitLabel = ui.unitLabel,
                pulses = pulses,
                onTempo = vm::nudgeTempo,
            ) {
                BarLine(ui)
            }
            Transport(isPlaying = ui.isPlaying, canReset = ui.canReset, onReset = vm::reset, onToggle = vm::togglePlay)
            Chips(ui, onEdit = { picking = true })
            RampControls(
                ui = ui,
                onDirection = vm::setDirection,
                onStep = vm::setStep,
                onEveryBars = vm::setEveryBars,
            )
        }
    }

    if (picking) {
        PickerSheet(
            ui = ui,
            onSignature = vm::setSignature,
            onPattern = vm::setPattern,
            onClose = { picking = false },
        )
    }
}

@Composable
private fun Header(onRotate: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.practice_title),
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val rotateLabel = stringResource(R.string.practice_rotate)
        IconButton(onRotate, Modifier.size(48.dp).semantics { contentDescription = rotateLabel }) {
            Icon(painterResource(R.drawable.ic_rotate), null, Modifier.size(26.dp))
        }
    }
}

/** "Bar 5 of 8 · next 90" / "Bar 5 of 8 · at limit"; hidden with the ramp off. */
@Composable
private fun BarLine(ui: PracticeUi) {
    if (!ui.rampOn) return
    val next = when {
        ui.nextTempo != null -> stringResource(R.string.practice_next, ui.nextTempo)
        ui.atLimit -> stringResource(R.string.practice_at_limit)
        else -> null
    }
    Text(
        listOfNotNull(stringResource(R.string.practice_bar_of, ui.bar, ui.everyBars), next).joinToString(" \u00B7 "),
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun Transport(isPlaying: Boolean, canReset: Boolean, onReset: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only offered when it would change something; the slot stays so play does not shift.
        if (canReset) {
            IconButton(onReset, Modifier.size(52.dp)) {
                Icon(painterResource(R.drawable.ic_reset), stringResource(R.string.practice_reset), Modifier.size(28.dp))
            }
        } else {
            Spacer(Modifier.size(52.dp))
        }
        FilledIconButton(
            onToggle,
            Modifier.size(84.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (isPlaying) R.string.pause else R.string.play),
                Modifier.size(44.dp),
            )
        }
        // Balances the Reset button so play stays centred.
        Spacer(Modifier.size(52.dp))
    }
}

@Composable
private fun Chips(ui: PracticeUi, onEdit: () -> Unit) {
    Row(
        Modifier.padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val signature = ui.settings.timeSignature.toString()
        val sigLabel = stringResource(R.string.edit_time_signature, signature)
        AssistChip(onClick = onEdit, label = { Text(signature) }, modifier = Modifier.semantics { contentDescription = sigLabel })
        val click = patternLabel(ui.settings.pattern)
        val clickLabel = stringResource(R.string.edit_click_pattern, click)
        AssistChip(onClick = onEdit, label = { Text("Click $click") }, modifier = Modifier.semantics { contentDescription = clickLabel })
    }
}

@Composable
private fun RampControls(
    ui: PracticeUi,
    onDirection: (RampDirection) -> Unit,
    onStep: (Int) -> Unit,
    onEveryBars: (Int) -> Unit,
) {
    val settings = ui.settings
    Label(stringResource(R.string.practice_ramp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        RampDirection.entries.forEachIndexed { index, direction ->
            SegmentedButton(
                selected = settings.direction == direction,
                onClick = { onDirection(direction) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = RampDirection.entries.size),
            ) {
                Text(
                    stringResource(
                        when (direction) {
                            RampDirection.Down -> R.string.practice_down
                            RampDirection.Off -> R.string.practice_off
                            RampDirection.Up -> R.string.practice_up
                        },
                    ),
                )
            }
        }
    }
    StepperRow(
        label = stringResource(R.string.practice_step),
        value = "${settings.stepBpm}",
        enabled = ui.rampOn,
        onMinus = { onStep(settings.stepBpm - 1) },
        onPlus = { onStep(settings.stepBpm + 1) },
    )
    StepperRow(
        label = stringResource(R.string.practice_every_bars),
        value = "${settings.everyBars}",
        enabled = ui.rampOn,
        onMinus = { onEveryBars(settings.everyBars - 1) },
        onPlus = { onEveryBars(settings.everyBars + 1) },
    )
}

/** A label with -/+ around the value. The rows dim through their buttons' disabled colours while the ramp is off. */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val minusLabel = stringResource(R.string.practice_decrease, label)
    val plusLabel = stringResource(R.string.practice_increase, label)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
        FilledTonalIconButton(
            onMinus,
            Modifier.size(48.dp).semantics { contentDescription = minusLabel },
            enabled = enabled,
        ) {
            Text("\u2212", style = MaterialTheme.typography.titleMedium)
        }
        // The slot is always there, so -/+ line up whether or not the label carries the value.
        Text(
            value,
            Modifier.widthIn(min = 56.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else TextSecondary,
        )
        FilledTonalIconButton(
            onPlus,
            Modifier.size(48.dp).semantics { contentDescription = plusLabel },
            enabled = enabled,
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
    )
}

/** The song editor's pickers: the same signatures and subdivisions, applied as they are tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    ui: PracticeUi,
    onSignature: (TimeSignature) -> Unit,
    onPattern: (ClickPattern) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Read the insets once, outside the sheet: applying them inside changes the sheet's height mid-drag.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.padding(top = topInset),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = bottomInset, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("TIME SIGNATURE")
            SignaturePicker(ui.settings.timeSignature, onSignature)
            Label("CLICK EVERY")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.patterns.forEach { pattern ->
                    FilterChip(
                        ui.settings.pattern == pattern,
                        { onPattern(pattern) },
                        label = { Text(patternLabel(pattern)) },
                    )
                }
            }
        }
    }
}
