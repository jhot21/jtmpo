package me.jhot.jtmpo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.SurfaceRaised

private val ThumbSize = 28.dp
private val BubbleHeight = 44.dp

/** The space the drag control normally reserves above its track for the value bubble. */
val TempoBubbleSpace = BubbleHeight

/**
 * - / + buttons around a drag track. The value bubble floats above the thumb so a finger on the
 * thumb never hides it. Out-of-range results are clamped here; [onTempo] only sees valid values.
 */
@Composable
fun TempoDragControl(tempo: Int, onTempo: (Int) -> Unit, modifier: Modifier = Modifier, bubbleSpace: Dp = TempoBubbleSpace) {
    val haptic = LocalHapticFeedback.current
    val currentTempo by rememberUpdatedState(tempo)
    val currentOnTempo by rememberUpdatedState(onTempo)
    var dragging by remember { mutableStateOf(false) }

    fun step(delta: Int) {
        val next = (currentTempo + delta).coerceIn(TempoLimits.MIN, TempoLimits.MAX)
        if (next != currentTempo) {
            currentOnTempo(next)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton("−", "Decrease tempo") { step(-1) }
        BoxWithConstraints(Modifier.weight(1f)) {
            val trackWidth = maxWidth
            val density = LocalDensity.current
            val trackWidthPx = with(density) { maxWidth.toPx() }
            val thumbPx = with(density) { ThumbSize.toPx() }
            val fraction = (tempo - TempoLimits.MIN).toFloat() / (TempoLimits.MAX - TempoLimits.MIN)
            val thumbX = with(density) { ((trackWidthPx - thumbPx) * fraction).toDp() }

            Column {
                // Space reserved above the track so the bubble never shifts the layout or clips. A caller short of room
                // passes less (down to none): the bubble then floats over whatever is above the track while dragged.
                Box(Modifier.fillMaxWidth().height(bubbleSpace)) {
                    if (dragging) {
                        val bubbleWidth = 64.dp
                        val bubbleX = (thumbX + ThumbSize / 2 - bubbleWidth / 2)
                            .coerceIn(0.dp, trackWidth - bubbleWidth)
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                // requiredHeight centres the bubble on the (possibly empty) box; sit it just above the track.
                                .offset(x = bubbleX, y = (bubbleSpace - (BubbleHeight - 6.dp)) / 2 - 3.dp * (bubbleSpace / BubbleHeight))
                                .width(bubbleWidth)
                                .requiredHeight(BubbleHeight - 6.dp)
                                .background(Accent, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$tempo", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondary)
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .semantics { contentDescription = "Tempo drag area, $tempo BPM" }
                        .pointerInput(Unit) {
                            val accelerator = TempoAccelerator()
                            var last = 0L
                            var pending = 0
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    accelerator.reset()
                                    pending = currentTempo
                                    last = 0L
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dx ->
                                val dt = if (last == 0L) 16L else change.uptimeMillis - last
                                last = change.uptimeMillis
                                val delta = accelerator.onDrag(dx, dt)
                                if (delta != 0) {
                                    val next = (pending + delta).coerceIn(TempoLimits.MIN, TempoLimits.MAX)
                                    if (next != pending) {
                                        pending = next
                                        currentOnTempo(next)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).padding(horizontal = 4.dp)
                            .background(SurfaceRaised, RoundedCornerShape(3.dp)),
                    )
                    Box(
                        Modifier
                            .offset(x = thumbX)
                            .size(ThumbSize)
                            .background(if (dragging) Accent else MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
        StepButton("+", "Increase tempo") { step(1) }
    }
}

@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).semantics { contentDescription = description },
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
