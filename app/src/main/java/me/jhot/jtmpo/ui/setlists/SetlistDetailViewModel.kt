package me.jhot.jtmpo.ui.setlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.RemovedEntry
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.share.SetlistFileCodec
import me.jhot.jtmpo.share.exportSetlist
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.playback.PlaybackController

/** A removal still on offer to undo; [title] is for the snackbar text. */
data class UndoOffer(val entryId: Long, val title: String)

class SetlistDetailViewModel(
    private val setlistId: Long,
    private val repository: Repository,
    private val playback: PlaybackController,
) : ViewModel() {
    val entries: StateFlow<List<EffectiveSong>> =
        repository.entries(setlistId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Null until the setlist loads, and again once it has been deleted. */
    val name: StateFlow<String?> = repository.setlists()
        .map { list -> list.firstOrNull { it.id == setlistId }?.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Picker data for "Add songs". */
    val library: StateFlow<List<Song>> =
        repository.songs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val canPlay: Boolean get() = entries.value.isNotEmpty()

    private val _undo = MutableStateFlow<UndoOffer?>(null)
    val undo: StateFlow<UndoOffer?> = _undo

    private var removed: RemovedEntry? = null

    /** A removal that threw: the screen resets the swipe so the row is not left half-dismissed. */
    private val _removeFailed = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val removeFailed: SharedFlow<Long> = _removeFailed

    private val _navigateToPlayer = MutableStateFlow<Long?>(null)

    /** Non-null once [play] has loaded the controller; the screen navigates and calls [navigated]. */
    val navigateToPlayer: StateFlow<Long?> = _navigateToPlayer

    fun navigated() {
        _navigateToPlayer.value = null
    }

    private val alreadyLoaded: Boolean get() = playback.state.value.setlistId == setlistId

    /**
     * A row tap. On the setlist already loaded this selects the entry; otherwise it loads this
     * setlist. Either way the click switches at once while playing and stays silent while paused.
     * [startIndex] is an entry position, so duplicate songs stay distinct.
     */
    fun play(startIndex: Int) {
        val current = entries.value
        if (current.isEmpty()) return
        if (alreadyLoaded) playback.select(startIndex.coerceIn(0, current.lastIndex)) else playback.load(setlistId, current, startIndex)
        _navigateToPlayer.value = setlistId
        viewModelScope.launch { repository.markPlayed(setlistId) }
    }

    /**
     * The Play button: on the loaded setlist, start the current entry; otherwise load from the top
     * (paused, or still playing if the click was running).
     */
    fun playFromStart() {
        if (entries.value.isEmpty()) return
        if (alreadyLoaded) {
            playback.play()
            _navigateToPlayer.value = setlistId
            viewModelScope.launch { repository.markPlayed(setlistId) }
        } else {
            play(0)
        }
    }

    fun moveEntry(from: Int, to: Int) {
        viewModelScope.launch { repository.moveEntry(setlistId, from, to) }
    }

    fun removeEntry(entryId: Long) {
        val title = entries.value.firstOrNull { it.entryId == entryId }?.title ?: return
        viewModelScope.launch {
            val snapshot = runCatching { repository.removeEntry(entryId) }.getOrNull()
            if (snapshot == null) {
                _removeFailed.tryEmit(entryId)
                return@launch
            }
            removed = snapshot
            _undo.value = UndoOffer(entryId, title)
        }
    }

    fun undoRemove() {
        val snapshot = removed ?: return
        removed = null
        _undo.value = null
        viewModelScope.launch { repository.restoreEntry(snapshot) }
    }

    /** The snackbar timed out; Undo is no longer offered. */
    fun dismissUndo() {
        removed = null
        _undo.value = null
    }

    fun addSongs(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        viewModelScope.launch { repository.addSongs(setlistId, songIds) }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameSetlist(setlistId, trimmed) }
    }

    /** The setlist's name and `.jtmpo` JSON, or null when it is missing or empty. */
    suspend fun shareJson(setlistId: Long): Pair<String, String>? {
        val shared = repository.exportSetlist(setlistId) ?: return null
        return shared.name to SetlistFileCodec.encode(shared)
    }

    class Factory(
        private val setlistId: Long,
        private val repository: Repository,
        private val playback: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SetlistDetailViewModel(setlistId, repository, playback) as T
    }
}
