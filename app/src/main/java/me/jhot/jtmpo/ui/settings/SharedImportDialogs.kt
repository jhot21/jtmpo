package me.jhot.jtmpo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.jhot.jtmpo.R
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.share.Choice
import me.jhot.jtmpo.share.Match
import me.jhot.jtmpo.share.SongPlan
import me.jhot.jtmpo.ui.theme.TextSecondary

/** The dialog for each step of the `.jtmpo` flow; dismissing any of them cancels the whole import. */
@Composable
fun SharedImportDialogs(
    state: SharedImportState,
    onConfirm: (addWithSuffix: Boolean) -> Unit,
    onAnswer: (Choice) -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        is SharedImportState.Confirm -> ConfirmDialog(state, onConfirm, onCancel)
        is SharedImportState.Conflict -> ConflictDialog(state, onAnswer, onCancel)
        is SharedImportState.Error -> ErrorDialog(state.kind, onCancel)
        else -> Unit
    }
}

@Composable
private fun ConfirmDialog(state: SharedImportState.Confirm, onConfirm: (Boolean) -> Unit, onCancel: () -> Unit) {
    val plan = state.plan
    val decide = plan.conflicts.size
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.shared_import_confirm_title, plan.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    pluralStringResource(R.plurals.shared_import_entries, plan.entryCount, plan.entryCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(pluralStringResource(R.plurals.shared_import_exact, plan.exactCount, plan.exactCount))
                Text(pluralStringResource(R.plurals.shared_import_new, plan.newCount, plan.newCount))
                Text(pluralStringResource(R.plurals.shared_import_decide, decide, decide))
                if (state.nameCollision) {
                    Text(
                        stringResource(R.string.shared_import_collision, plan.name),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(state.nameCollision) }) {
                Text(
                    if (state.nameCollision) stringResource(R.string.shared_import_add_as, state.suggestedName)
                    else stringResource(R.string.import_confirm),
                )
            }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.shared_import_cancel)) } },
    )
}

@Composable
private fun ConflictDialog(state: SharedImportState.Conflict, onAnswer: (Choice) -> Unit, onCancel: () -> Unit) {
    val plan = state.current
    val song = plan.song
    val mine = plan.candidate ?: return
    val isA = plan.match == Match.CONFLICT_A
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.shared_import_conflict_title, state.number, state.total)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (isA) stringResource(R.string.shared_import_conflict_a, mine.title, song.artist.orEmpty())
                    else stringResource(R.string.shared_import_conflict_b, song.title, mine.artist.orEmpty()),
                )
                differences(plan)?.let { Text(it, color = TextSecondary, modifier = Modifier.padding(top = 8.dp)) }
                if (isA) {
                    ChoiceButton(R.string.shared_import_update, stringResource(R.string.shared_import_update_hint)) {
                        onAnswer(Choice.UPDATE)
                    }
                }
                ChoiceButton(R.string.shared_import_keep, stringResource(R.string.shared_import_keep_hint)) {
                    onAnswer(Choice.KEEP_MINE)
                }
                ChoiceButton(
                    R.string.shared_import_new_song,
                    if (isA) stringResource(R.string.shared_import_new_hint_a, song.title, song.artist.orEmpty())
                    else stringResource(R.string.shared_import_new_hint_b, song.title),
                ) { onAnswer(Choice.NEW) }
            }
        },
        confirmButton = { TextButton(onCancel) { Text(stringResource(R.string.shared_import_cancel_import)) } },
    )
}

/** What else the file's song has that the library song does not, beyond the artist; null when nothing. */
@Composable
private fun differences(plan: SongPlan): String? {
    val theirs = plan.song
    val mine = plan.candidate ?: return null
    val parts = buildList {
        if (theirs.rhythm.tempo != mine.rhythm.tempo) {
            add(stringResource(R.string.shared_import_diff_tempo, theirs.rhythm.tempo, mine.rhythm.tempo))
        }
        if (theirs.rhythm.timeSignature != mine.rhythm.timeSignature) {
            add(
                stringResource(
                    R.string.shared_import_diff_time_signature,
                    theirs.rhythm.timeSignature.toString(),
                    mine.rhythm.timeSignature.toString(),
                ),
            )
        }
        if (theirs.rhythm.pattern != mine.rhythm.pattern) {
            add(stringResource(R.string.shared_import_diff_click, click(theirs.rhythm.pattern), click(mine.rhythm.pattern)))
        }
        // Blank file notes are no opinion, as in the planner.
        if (!theirs.notes.isNullOrBlank() && theirs.notes != mine.notes.orEmpty()) {
            add(stringResource(R.string.shared_import_diff_notes))
        }
    }
    if (parts.isEmpty()) return null
    return stringResource(R.string.shared_import_differs, parts.joinToString(stringResource(R.string.shared_import_diff_separator)))
}

private fun click(pattern: ClickPattern) = "1/${pattern.noteDenominator}" + if (pattern.dotted) "." else ""

@Composable
private fun ChoiceButton(label: Int, hint: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(label)) }
    Text(hint, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ErrorDialog(kind: SharedImportError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_error_title)) },
        text = {
            Text(
                stringResource(
                    when (kind) {
                        SharedImportError.BACKUP -> R.string.shared_import_error_backup
                        SharedImportError.NOT_A_SETLIST -> R.string.shared_import_error_not_setlist
                        SharedImportError.NEWER_VERSION -> R.string.shared_import_error_newer
                        SharedImportError.INVALID -> R.string.shared_import_error_invalid
                        SharedImportError.COMMIT_FAILED -> R.string.import_error_commit
                    },
                ),
            )
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.import_error_ok)) } },
    )
}
