package me.jhot.jtmpo.ui.practice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R

/** Placeholder so the tab is reachable; the practice controls and view model land on top of it. */
@Composable
fun PracticeScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.practice_title), style = MaterialTheme.typography.headlineSmall)
    }
}
