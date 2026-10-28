package me.jhot.jtmpo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.RemovedSong
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.SetlistSummary
import me.jhot.jtmpo.data.Song

enum class LibrarySort { AZ, TEMPO, RECENT }

/** A delete awaiting confirmation, with the setlists that would lose an entry. */
data class PendingDelete(val songId: Long, val title: String, val setlistNames: List<String>)

data class LibraryUi(
    val songs: List<Song> = emptyList(),
    /** Library size ignoring the search, for the header. */
    val totalCount: Int = 0,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.AZ,
    val pendingDelete: PendingDelete? = null,
    /** Title of the song just deleted; non-null while Undo is on offer. */
    val undoTitle: String? = null,
)

/** Takes only the repository so it can be built without Android context. */
class LibraryViewModel(private val repository: Repository) : ViewModel() {
    private data class Controls(
        val query: String = "",
        val sort: LibrarySort = LibrarySort.AZ,
        val pendingDelete: PendingDelete? = null,
        val undoTitle: String? = null,
    )

    private val controls = MutableStateFlow(Controls())
    private var removed: RemovedSong? = null

    val ui: StateFlow<LibraryUi> = combine(repository.songs(), controls) { all, c ->
        LibraryUi(
            songs = arrange(all, c.query, c.sort),
            totalCount = all.size,
            query = c.query,
            sort = c.sort,
            pendingDelete = c.pendingDelete,
            undoTitle = c.undoTitle,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUi())

    /** Picker data for "Add to setlist". */
    val setlists: StateFlow<List<SetlistSummary>> =
        repository.setlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setQuery(query: String) = controls.update { it.copy(query = query) }

    fun setSort(sort: LibrarySort) = controls.update { it.copy(sort = sort) }

    fun requestDelete(songId: Long) {
        viewModelScope.launch {
            val song = repository.song(songId) ?: return@launch
            val names = repository.setlistNamesContaining(songId)
            controls.update { it.copy(pendingDelete = PendingDelete(songId, song.title, names)) }
        }
    }

    fun cancelDelete() = controls.update { it.copy(pendingDelete = null) }

    fun confirmDelete(songId: Long) {
        viewModelScope.launch {
            controls.update { it.copy(pendingDelete = null) }
            val snapshot = runCatching { repository.removeSong(songId) }.getOrNull() ?: return@launch
            removed = snapshot
            controls.update { it.copy(undoTitle = snapshot.song.title) }
        }
    }

    fun undoDelete() {
        val snapshot = removed ?: return
        removed = null
        controls.update { it.copy(undoTitle = null) }
        viewModelScope.launch { repository.restoreSong(snapshot) }
    }

    /** The snackbar timed out; Undo is no longer offered. */
    fun dismissUndo() {
        removed = null
        controls.update { it.copy(undoTitle = null) }
    }

    /** The returned job completes once the entry is stored, so the screen can confirm it. */
    fun addToSetlist(setlistId: Long, songId: Long): Job =
        viewModelScope.launch { repository.addSongs(setlistId, listOf(songId)) }

    class Factory(private val repository: Repository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repository) as T
    }
}

private fun arrange(all: List<Song>, query: String, sort: LibrarySort): List<Song> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) {
        all
    } else {
        all.filter { it.title.contains(q, ignoreCase = true) || it.artist?.contains(q, ignoreCase = true) == true }
    }
    val byTitle = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.artist.orEmpty() }
    return when (sort) {
        LibrarySort.AZ -> filtered.sortedWith(byTitle)
        LibrarySort.TEMPO -> filtered.sortedWith(compareBy<Song> { it.rhythm.tempo }.then(byTitle))
        LibrarySort.RECENT -> filtered.sortedWith(compareByDescending<Song> { it.createdAt }.thenByDescending { it.id })
    }
}
