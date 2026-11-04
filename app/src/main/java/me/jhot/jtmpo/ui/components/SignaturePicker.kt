package me.jhot.jtmpo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.domain.COMMON_SIGNATURES
import me.jhot.jtmpo.domain.SIGNATURE_DENOMINATORS
import me.jhot.jtmpo.domain.SIGNATURE_NUMERATORS
import me.jhot.jtmpo.domain.TimeSignature

/**
 * The common signatures as chips, plus an "Other…" row for anything within
 * [SIGNATURE_NUMERATORS] and [SIGNATURE_DENOMINATORS]. Shared by the song editor and Practice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignaturePicker(current: TimeSignature, onPick: (TimeSignature) -> Unit) {
    var otherOpen by remember { mutableStateOf(current !in COMMON_SIGNATURES) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COMMON_SIGNATURES.forEach { ts ->
            FilterChip(current == ts && !otherOpen, { otherOpen = false; onPick(ts) }, label = { Text(ts.toString()) })
        }
        FilterChip(otherOpen, { otherOpen = true }, label = { Text("Other\u2026") })
    }
    if (otherOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton({
                onPick(current.copy(numerator = (current.numerator - 1).coerceAtLeast(SIGNATURE_NUMERATORS.first)))
            }) { Text("\u2212") }
            Text("${current.numerator}", style = MaterialTheme.typography.titleLarge)
            FilledTonalIconButton({
                onPick(current.copy(numerator = (current.numerator + 1).coerceAtMost(SIGNATURE_NUMERATORS.last)))
            }) { Text("+") }
            Text("/", style = MaterialTheme.typography.titleLarge)
            SIGNATURE_DENOMINATORS.forEach { d ->
                FilterChip(current.denominator == d, { onPick(current.copy(denominator = d)) }, label = { Text("$d") })
            }
        }
    }
}
