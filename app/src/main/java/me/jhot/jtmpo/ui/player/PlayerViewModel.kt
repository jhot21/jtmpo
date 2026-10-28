package me.jhot.jtmpo.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.tempoUnit
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.playback.PlaybackController
import me.jhot.jtmpo.playback.PlaybackState
import me.jhot.jtmpo.playback.positionText

data class PlayerUi(
    val hasSong: Boolean = false,
    val entryId: Long? = null,
    val title: String = "",
    val artist: String? = null,
    val notes: String? = null,
    val tempo: Int = 0,
    val timeSignature: String = "",
    /** Bottom number of the time signature: the note the tempo counts. */
    val tempoUnit: String = "\u2669",
    val patternLabel: String = "",
    /** The current entry carries a setlist override (rhythm or notes). */
    val overridden: Boolean = false,
    val isPlaying: Boolean = false,
    /** True while a live tempo/rhythm change has not been saved yet. */
    val unsaved: Boolean = false,
    val setlistName: String = "",
    val positionText: String = "",
    val keepAwake: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/** The note the tempo counts, shown after "BPM": see [tempoUnit]. */
fun tempoUnitNote(ts: TimeSignature): String {
    val unit = ts.tempoUnit
    val note = when (unit.noteDenominator) {
        2 -> "\uD834\uDD57\uD834\uDD65" // half note (void notehead + stem)
        4 -> "\u2669"
        8 -> "\u266A"
        16 -> "\u266C"
        else -> return ""
    }
    return if (unit.dotted) "$note." else note
}

fun patternLabel(p: ClickPattern) = (if (p.dotted) "dotted " else "") + "1/${p.noteDenominator}"

/** The window flag follows this alone: while the click plays or the player is open, and only if the user wants it. */
fun keepScreenOn(isPlaying: Boolean, playerOpen: Boolean, keepAwake: Boolean) = (isPlaying || playerOpen) && keepAwake

/** Shared by the player and the activity (which owns the window flag, so it holds across screens). */
fun keepScreenOnFlow(playback: PlaybackController, playerOpen: Flow<Boolean>, settings: SettingsStore): Flow<Boolean> =
    combine(playback.state.map { it.isPlaying }, playerOpen, settings.keepAwake, ::keepScreenOn)

class PlayerViewModel(
    private val playback: PlaybackController,
    private val repository: Repository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val setlistNames: StateFlow<Map<Long, String>> = repository.setlists()
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val ui: StateFlow<PlayerUi> = combine(playback.state, setlistNames, settings.keepAwake) { state, names, keepAwake ->
        state.toUi(names[state.setlistId].orEmpty(), keepAwake)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerUi())

    val entries: StateFlow<List<EffectiveSong>> = playback.state.map { it.entries }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val setlistId: Long? get() = playback.state.value.setlistId

    /** Picker data for "Add from library". */
    val library: StateFlow<List<Song>> =
        repository.songs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun togglePlay() = if (playback.state.value.isPlaying) playback.pause() else playback.play()

    fun next() = playback.next()

    fun previous() = playback.previous()

    /** Switches at once while playing, waits while paused (the controller decides). */
    fun select(index: Int) = playback.select(index)

    fun moveEntry(from: Int, to: Int) {
        val id = setlistId ?: return
        viewModelScope.launch { repository.moveEntry(id, from, to) }
    }

    fun addSongs(songIds: List<Long>) {
        val id = setlistId ?: return
        if (songIds.isEmpty()) return
        viewModelScope.launch { repository.addSongs(id, songIds) }
    }

    private fun PlaybackState.toUi(setlistName: String, keepAwake: Boolean): PlayerUi {
        val song = current
        val rhythm = effectiveRhythm
        if (song == null || rhythm == null) return PlayerUi(keepAwake = keepAwake, setlistName = setlistName)
        return PlayerUi(
            hasSong = true,
            entryId = song.entryId,
            title = song.title,
            artist = song.artist?.takeIf { it.isNotBlank() },
            notes = song.notes?.takeIf { it.isNotBlank() },
            tempo = rhythm.tempo,
            timeSignature = rhythm.timeSignature.toString(),
            tempoUnit = tempoUnitNote(rhythm.timeSignature),
            patternLabel = patternLabel(rhythm.pattern),
            overridden = song.rhythmOverridden || song.notesOverridden,
            isPlaying = isPlaying,
            unsaved = previewRhythm != null,
            setlistName = setlistName,
            positionText = positionText(setlistName, currentIndex, entries.size),
            keepAwake = keepAwake,
            // The view model only exists while the player is open.
            keepScreenOn = keepScreenOn(isPlaying, playerOpen = true, keepAwake = keepAwake),
        )
    }

    class Factory(
        private val playback: PlaybackController,
        private val repository: Repository,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(playback, repository, settings) as T
    }
}
