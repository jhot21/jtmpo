package me.jhot.jtmpo.ui.setlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.RemovedSetlist
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.share.SetlistFileCodec
import me.jhot.jtmpo.share.exportSetlist
import me.jhot.jtmpo.data.SetlistSummary

enum class SetlistSort { RECENT, AZ }

/** A deleted setlist still on offer to undo; [id] keeps two same-named deletes distinct. */
data class SetlistUndoOffer(val id: Long, val name: String)

data class SetlistsUi(val setlists: List<SetlistSummary> = emptyList(), val sort: SetlistSort = SetlistSort.RECENT)

class SetlistsViewModel(private val repository: Repository) : ViewModel() {
    private val sort = MutableStateFlow(SetlistSort.RECENT)

    val ui: StateFlow<SetlistsUi> = combine(repository.setlists(), sort) { all, s ->
        SetlistsUi(arrange(all, s), s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SetlistsUi())

    fun setSort(value: SetlistSort) {
        sort.value = value
    }

    fun create(name: String, onCreated: (Long) -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { onCreated(repository.createSetlist(trimmed)) }
    }

    fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameSetlist(id, trimmed) }
    }

    /** The setlist's name and `.jtmpo` JSON, or null when it is missing or empty. */
    suspend fun shareJson(setlistId: Long): Pair<String, String>? {
        val shared = repository.exportSetlist(setlistId) ?: return null
        return shared.name to SetlistFileCodec.encode(shared)
    }

    private val _undo = MutableStateFlow<SetlistUndoOffer?>(null)
    val undo: StateFlow<SetlistUndoOffer?> = _undo

    private var removed: RemovedSetlist? = null

    /**
     * Deletes with an Undo offer. Deleting the loaded setlist stops the click (its entries vanish
     * from the controller); undoing brings the setlist back but never restarts the click.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val snapshot = runCatching { repository.removeSetlist(id) }.getOrNull() ?: return@launch
            removed = snapshot
            _undo.value = SetlistUndoOffer(snapshot.setlist.id, snapshot.setlist.name)
        }
    }

    fun undoDelete() {
        val snapshot = removed ?: return
        removed = null
        _undo.value = null
        viewModelScope.launch { repository.restoreSetlist(snapshot) }
    }

    /** The snackbar timed out; Undo is no longer offered. */
    fun dismissUndo() {
        removed = null
        _undo.value = null
    }

    class Factory(private val repository: Repository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SetlistsViewModel(repository) as T
    }
}

/** Recent: last played first, never-played ones after, newest created first. */
internal fun arrange(all: List<SetlistSummary>, sort: SetlistSort): List<SetlistSummary> = when (sort) {
    SetlistSort.AZ -> all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    SetlistSort.RECENT -> all.sortedWith(
        compareByDescending<SetlistSummary> { it.lastPlayedAt ?: Long.MIN_VALUE }.thenByDescending { it.id },
    )
}
