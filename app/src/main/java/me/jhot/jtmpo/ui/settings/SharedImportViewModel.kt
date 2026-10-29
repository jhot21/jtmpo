package me.jhot.jtmpo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.share.Choice
import me.jhot.jtmpo.share.ImportFormat
import me.jhot.jtmpo.share.Match
import me.jhot.jtmpo.share.SetlistFileCodec
import me.jhot.jtmpo.share.SetlistFileException
import me.jhot.jtmpo.share.SharedImportPlan
import me.jhot.jtmpo.share.SongPlan
import me.jhot.jtmpo.share.commitShared
import me.jhot.jtmpo.share.detectFormat
import me.jhot.jtmpo.share.planSharedImport
import me.jhot.jtmpo.share.resolveSharedImport
import me.jhot.jtmpo.share.unusedSetlistName

enum class SharedImportError { BACKUP, NOT_A_SETLIST, NEWER_VERSION, INVALID, COMMIT_FAILED }

sealed interface SharedImportState {
    data object Idle : SharedImportState

    /** Reading and matching the file; nothing to show yet. */
    data object Loading : SharedImportState

    /** [suggestedName] is the name the setlist will get: the file's, or the next "Name (N)" on a collision. */
    data class Confirm(val plan: SharedImportPlan, val nameCollision: Boolean, val suggestedName: String) : SharedImportState

    /** [index] is 0-based into [SharedImportPlan.conflicts]; [number] is the 1-based "Conflict N of M". */
    data class Conflict(val plan: SharedImportPlan, val index: Int, val total: Int) : SharedImportState {
        val number: Int get() = index + 1
        val current: SongPlan get() = plan.conflicts[index]
    }

    data object Committing : SharedImportState

    data class Error(val kind: SharedImportError) : SharedImportState

    data class Done(val name: String, val newSongs: Int, val entries: Int) : SharedImportState
}

/**
 * The `.jtmpo` import: parse and match, confirm, one decision per conflict, then one commit. Nothing is
 * written before the last answer, and [cancel] at any step drops the whole import.
 */
class SharedImportViewModel(private val repository: Repository) : ViewModel() {
    private val _state = MutableStateFlow<SharedImportState>(SharedImportState.Idle)
    val state: StateFlow<SharedImportState> = _state.asStateFlow()

    private val decisions = mutableMapOf<Long, Choice>()
    private var job: Job? = null

    fun load(bytes: ByteArray) {
        job?.cancel()
        decisions.clear()
        _state.value = SharedImportState.Loading
        job = viewModelScope.launch {
            _state.value = withContext(Dispatchers.Default) { prepare(bytes) }
        }
    }

    private suspend fun prepare(bytes: ByteArray): SharedImportState {
        when (detectFormat(bytes)) {
            ImportFormat.JTMPO -> Unit
            ImportFormat.BACKUP -> return SharedImportState.Error(SharedImportError.BACKUP)
            // A Tempo file never gets here from the screen; to this flow it is just not a setlist.
            ImportFormat.TEMPO, ImportFormat.UNKNOWN -> return SharedImportState.Error(SharedImportError.NOT_A_SETLIST)
        }
        val file = SetlistFileCodec.decode(bytes.toString(Charsets.UTF_8)).getOrElse { e ->
            return SharedImportState.Error(
                when (e) {
                    is SetlistFileException.NewerVersion -> SharedImportError.NEWER_VERSION
                    is SetlistFileException.LooksLikeBackup -> SharedImportError.BACKUP
                    is SetlistFileException.NotASetlist -> SharedImportError.NOT_A_SETLIST
                    else -> SharedImportError.INVALID
                },
            )
        }
        val plan = planSharedImport(file, repository.songs().first())
        val existing = repository.setlists().first().map { it.name }
        val name = unusedSetlistName(plan.name, existing)
        return SharedImportState.Confirm(plan, nameCollision = name != plan.name, suggestedName = name)
    }

    /** At a name collision only [addWithSuffix] goes on; anything else is a cancel. */
    fun confirm(addWithSuffix: Boolean) {
        val current = _state.value as? SharedImportState.Confirm ?: return
        if (current.nameCollision && !addWithSuffix) return cancel()
        val conflicts = current.plan.conflicts
        if (conflicts.isEmpty()) commit(current.plan)
        else _state.value = SharedImportState.Conflict(current.plan, 0, conflicts.size)
    }

    fun answer(choice: Choice) {
        val current = _state.value as? SharedImportState.Conflict ?: return
        val song = current.current
        // Dialog B never offers an update: the file has no artist to give the library song.
        if (song.match == Match.CONFLICT_B && choice == Choice.UPDATE) return
        decisions[song.song.localId] = choice
        if (current.index + 1 < current.total) _state.value = current.copy(index = current.index + 1)
        else commit(current.plan)
    }

    private fun commit(plan: SharedImportPlan) {
        val answers = decisions.toMap()
        _state.value = SharedImportState.Committing
        job = viewModelScope.launch {
            _state.value = runCatching {
                val resolved = resolveSharedImport(plan, answers)
                val id = repository.commitShared(resolved)
                SharedImportState.Done(
                    name = repository.setlistName(id) ?: resolved.name,
                    newSongs = resolved.newSongs.size,
                    entries = resolved.entries.size,
                )
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                SharedImportState.Error(SharedImportError.COMMIT_FAILED)
            }
        }
    }

    /** Back to [SharedImportState.Idle] with nothing written. A commit already under way is left to finish. */
    fun cancel() {
        if (_state.value == SharedImportState.Committing) return
        job?.cancel()
        job = null
        decisions.clear()
        _state.value = SharedImportState.Idle
    }

    class Factory(private val repository: Repository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SharedImportViewModel(repository) as T
    }
}
