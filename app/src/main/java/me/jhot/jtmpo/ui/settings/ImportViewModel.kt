package me.jhot.jtmpo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.importer.FztSetlist
import me.jhot.jtmpo.importer.ImportMode
import me.jhot.jtmpo.importer.ImportPreview
import me.jhot.jtmpo.importer.commitImport
import me.jhot.jtmpo.importer.parseTempoExport
import me.jhot.jtmpo.importer.planImport

enum class ImportError { UNREADABLE, EMPTY, COMMIT_FAILED }

sealed interface ImportEvent {
    data class Done(val newSongs: Int, val setlistEntries: Int?) : ImportEvent
}

data class ImportUi(
    val setlists: List<FztSetlist> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val mode: ImportMode = ImportMode.SONGS_ONLY,
    val preview: ImportPreview? = null,
    val error: ImportError? = null,
    val busy: Boolean = false,
) {
    val hasFile get() = setlists.isNotEmpty()
    val canConfirm get() = !busy && preview != null && (preview.newSongs.isNotEmpty() || preview.setlist != null)
}

class ImportViewModel(private val repository: Repository) : ViewModel() {
    private val setlists = MutableStateFlow<List<FztSetlist>>(emptyList())
    private val selected = MutableStateFlow<Set<Int>>(emptySet())
    private val mode = MutableStateFlow(ImportMode.SONGS_ONLY)
    private val error = MutableStateFlow<ImportError?>(null)
    private val busy = MutableStateFlow(false)
    private val eventChannel = Channel<ImportEvent>(Channel.BUFFERED)
    val events: Flow<ImportEvent> = eventChannel.receiveAsFlow()

    private val selection = combine(setlists, selected, mode) { l, s, m -> Triple(l, s, m) }

    val ui: StateFlow<ImportUi> = combine(selection, repository.songs(), error, busy) { (l, s, m), songs, e, b ->
        val preview = if (l.isEmpty() || s.isEmpty()) null else planImport(l, s.toList(), m, songs)
        ImportUi(l, s, m, preview, e, b)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ImportUi())

    /** Parses the picked file. A bad file only sets [ImportUi.error]; nothing is saved either way. */
    fun load(read: suspend () -> ByteArray) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val parsed = runCatching { read() }.mapCatching { parseTempoExport(it).getOrThrow() }
            busy.value = false
            val lists = parsed.getOrNull()
            when {
                lists == null -> { clearFile(); error.value = ImportError.UNREADABLE }
                // A .slist with an empty songs array parses fine but has nothing to import.
                lists.all { it.songs.isEmpty() } -> { clearFile(); error.value = ImportError.EMPTY }
                else -> {
                    setlists.value = lists
                    mode.value = ImportMode.SONGS_ONLY
                    // The largest setlist is the one most likely wanted.
                    selected.value = setOf(lists.indices.maxByOrNull { lists[it].songs.size }!!)
                }
            }
        }
    }

    private fun clearFile() {
        setlists.value = emptyList()
        selected.value = emptySet()
    }

    fun toggle(index: Int) {
        // A setlist import builds one setlist, so there the checkboxes behave as a single choice.
        selected.value = if (mode.value == ImportMode.SONGS_AND_SETLIST) setOf(index)
        else selected.value.let { if (index in it) it - index else it + index }
    }

    fun setMode(value: ImportMode) {
        mode.value = value
        if (value == ImportMode.SONGS_AND_SETLIST && selected.value.size > 1) {
            selected.value = setOf(selected.value.minOf { it })
        }
    }

    fun dismissError() {
        error.value = null
    }

    fun confirm() {
        val state = ui.value
        val preview = state.preview ?: return
        if (!state.canConfirm) return
        val name = state.selected.singleOrNull()?.let { state.setlists[it].title }
        viewModelScope.launch {
            busy.value = true
            val ok = runCatching { repository.commitImport(preview, name) }.isSuccess
            busy.value = false
            if (ok) {
                clearFile()
                eventChannel.send(ImportEvent.Done(preview.newSongs.size, preview.setlist?.size))
            } else {
                error.value = ImportError.COMMIT_FAILED
            }
        }
    }

    class Factory(private val repository: Repository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(repository) as T
    }
}
