package me.jhot.jtmpo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Deterministic gradient endpoints for a setlist name: nothing is stored, renaming changes them. */
fun coverColors(name: String): Pair<Color, Color> {
    // FNV-1a over the UTF-16 units; String.hashCode clusters for similar short names.
    var h = -0x7ee3623b // 2166136261
    for (ch in name) {
        h = (h xor ch.code) * 16777619
    }
    val hue = ((h ushr 1) % 360).toFloat()
    val shift = 40f + ((h ushr 9) and 0x3F)
    return Color.hsv(hue, 0.6f, 0.95f) to Color.hsv((hue + shift) % 360f, 0.6f, 0.9f)
}

@Composable
fun SetlistCover(name: String, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val (from, to) = coverColors(name)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(from, to))),
    )
}
