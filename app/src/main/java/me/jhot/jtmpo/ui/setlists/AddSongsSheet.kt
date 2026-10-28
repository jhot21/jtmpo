package me.jhot.jtmpo.ui.setlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.R
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.ui.theme.TextSecondary

/**
 * Multi-select library picker. Songs already in the setlist stay selectable (the same song may
 * appear twice); they are just labelled. [onAdd] receives ids in the order they were picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsSheet(
    library: List<Song>,
    inSetlist: Set<Long>,
    onAdd: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Static, not navigationBarsPadding() inside the sheet: that follows the sheet's position, so it changes the
    // sheet's height mid-drag and the sheet snaps back.
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Same for the top: the sheet reports its own offset as consumed insets, so a top inset applied through
    // contentWindowInsets shrinks as the sheet is dragged down and resizes it mid-drag.
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val picked = remember { emptyList<Long>().toMutableStateList() }
    var query by remember { mutableStateOf("") }
    val shown = remember(library, query) {
        val q = query.trim()
        library.filter { q.isEmpty() || it.title.contains(q, true) || it.artist?.contains(q, true) == true }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.padding(top = topInset),
        // The padding above keeps the sheet below the status bar and camera cutout; the insets are padded by hand.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = bottomInset)) {
            Text("Add songs", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search songs or artists") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Text(
                    if (library.isEmpty()) "The library is empty. Add songs there first." else "No songs match your search.",
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { song ->
                    val selected = song.id in picked
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (selected) picked.remove(song.id) else picked.add(song.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                song.artist?.takeIf { it.isNotBlank() },
                                "already in this setlist".takeIf { song.id in inSetlist },
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) {
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { onAdd(picked.toList()) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Text(
                    if (picked.isEmpty()) "Select songs" else pluralStringResource(R.plurals.add_songs_action, picked.size, picked.size),
                )
            }
        }
    }
}
