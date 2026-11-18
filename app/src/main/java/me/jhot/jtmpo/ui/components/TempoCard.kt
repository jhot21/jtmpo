package me.jhot.jtmpo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import me.jhot.jtmpo.R
import me.jhot.jtmpo.ui.theme.SurfaceCard
import me.jhot.jtmpo.ui.theme.TextSecondary

/**
 * The big live tempo with the existing per-click flash, shared by the player and Practice.
 *
 * The flash is driven by the audio output's own timing, not a UI timer: one flash per audible click.
 * [extra] slots a screen's own line (Practice's bar counter) between the tempo and the control.
 */
@Composable
fun TempoCard(
    tempo: Int,
    unitLabel: String,
    pulses: SharedFlow<Unit>,
    onTempo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(pulses) {
        // collectLatest: a new click restarts the flash instead of queueing behind the previous one.
        pulses.collectLatest {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = 160))
        }
    }
    // The card is as tall as it naturally is, and only gets smaller when the height it is offered is less (the player
    // with its setlist open): the number, the padding and the space for the drag bubble all shrink together with the
    // offered height, and never grow past their natural size. Without a bounded height (Practice) it is natural.
    Surface(
        modifier.fillMaxWidth().widthIn(max = 480.dp),
        color = SurfaceCard,
        shape = MaterialTheme.shapes.large,
    ) {
        BoxWithConstraints(propagateMinConstraints = true) {
            val t = if (constraints.hasBoundedHeight) {
                ((maxHeight - TempoCardMinHeight) / (TempoCardNaturalHeight - TempoCardMinHeight)).coerceIn(0f, 1f)
            } else 1f
            val numberSize = 48f + 36f * t
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp + 10.dp * t),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "$tempo",
                    fontSize = numberSize.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (numberSize + 4f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.graphicsLayer {
                        val p = pulse.value
                        scaleX = 1f + 0.08f * p
                        scaleY = 1f + 0.08f * p
                        alpha = 0.72f + 0.28f * p
                    },
                )
                Text(
                    "${stringResource(R.string.bpm)} $unitLabel".trim(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    letterSpacing = 2.sp,
                )
                extra()
                TempoDragControl(tempo, onTempo, Modifier.padding(top = 4.dp), bubbleSpace = TempoBubbleSpace * t)
            }
        }
    }
}

/** The card's height at its smallest (smallest number, no space reserved for the bubble) and at its natural size. */
val TempoCardMinHeight = 145.dp
val TempoCardNaturalHeight = 274.dp
