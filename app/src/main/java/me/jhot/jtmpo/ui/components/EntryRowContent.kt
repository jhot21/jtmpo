package me.jhot.jtmpo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.ui.theme.Accent
import me.jhot.jtmpo.ui.theme.Surface0
import me.jhot.jtmpo.ui.theme.SurfaceRaised
import me.jhot.jtmpo.ui.theme.TextSecondary

private fun patternText(p: ClickPattern) = "click " + (if (p.dotted) "dotted " else "") + "1/${p.noteDenominator}"

/** Library-style summary: artist, tempo, signature, click pattern. */
private fun detailLine(song: EffectiveSong): String = buildList {
    song.artist?.takeIf { it.isNotBlank() }?.let(::add)
    val r = song.rhythm
    add(r.tempo.toString())
    add("${r.timeSignature.numerator}/${r.timeSignature.denominator}")
    add(patternText(r.pattern))
}.joinToString(" · ")

/**
 * A reorderable setlist row: drag handle, title, summary with the override marker, pencil.
 * Shared by the setlist detail screen and the player's pull-up setlist; [highlighted] marks the
 * entry that is current in the player.
 */
@Composable
fun EntryRowContent(
    entry: EffectiveSong,
    handle: Modifier,
    highlighted: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth()
            .background(if (highlighted) SurfaceRaised else Surface0)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val reorderLabel = stringResource(R.string.reorder_song, entry.title)
        IconButton(onClick = {}, modifier = handle.semantics { contentDescription = reorderLabel }) {
            Text("☰", color = TextSecondary)
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (highlighted) Text("● ", color = Accent, style = MaterialTheme.typography.bodyLarge)
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.rhythmOverridden || entry.notesOverridden) {
                    Text("● set  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    detailLine(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val editLabel = stringResource(R.string.edit_song) + ": " + entry.title
        IconButton(onEdit, Modifier.semantics { contentDescription = editLabel }) { Text("✎") }
    }
}
