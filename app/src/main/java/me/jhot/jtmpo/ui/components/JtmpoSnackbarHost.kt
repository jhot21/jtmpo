package me.jhot.jtmpo.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.SurfaceRaised

/** Snackbar styled for the dark theme: the default inverse colours would be a light slab on near-black. */
@Composable
fun JtmpoSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = SurfaceRaised,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = Accent,
        )
    }
}
