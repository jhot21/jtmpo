package me.jhot.jtmpo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Surface0 = Color(0xFF0F0F0F)
val SurfaceBar = Color(0xFF1A1A1A)
val SurfaceCard = Color(0xFF1C1C1C)
val SurfaceRaised = Color(0xFF272727)
val Accent = Color(0xFFFF4FA3)
val TextSecondary = Color(0xFFAAAAAA)

private val JtmpoColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Accent,
    onSecondary = Color.Black,
    tertiary = Accent,
    background = Surface0,
    onBackground = Color.White,
    surface = Surface0,
    onSurface = Color.White,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceBar,
    surfaceContainerLow = SurfaceCard,
    surfaceContainerHigh = SurfaceRaised,
    surfaceVariant = SurfaceRaised,
    secondaryContainer = SurfaceRaised,
    onSecondaryContainer = Color.White,
    outlineVariant = SurfaceRaised,
)

private val JtmpoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Dark only in v1: near-black surfaces, white primary actions, pink accent. */
@Composable
fun JtmpoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JtmpoColors, shapes = JtmpoShapes, content = content)
}
