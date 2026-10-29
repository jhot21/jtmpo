package me.jhot.jtmpo.ui.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.DuplicateSongException
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.Song
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TempoLimits
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.domain.clicksPerMinute
import me.jhot.jtmpo.domain.remapPattern
import me.jhot.jtmpo.playback.PlaybackController

data class EditUi(
    val loaded: Boolean = false,
    /** Editing a setlist entry (both save actions) rather than a library song. */
    val isEntry: Boolean = false,
    val isNewSong: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val setlistSongTitle: String = "",
    val rhythm: Rhythm = Rhythm(120, TimeSignature(4, 4), ClickPattern(4)),
    val notes: String = "",
    val libraryRhythm: Rhythm? = null,
    val clicksPerMinute: Double = 120.0,
    /** What will really play when the saved library value differs from this setlist's override. */
    val activeOverrideText: String? = null,
    val entryOverridden: Boolean = false,
    val notesOverridden: Boolean = false,
    val libraryNotes: String? = null,
    val canSave: Boolean = false,
    /** The editor's draft rhythm is being auditioned through the click output. */
    val previewing: Boolean = false,
    /** True once a save completed and the sheet should close. */
    val finished: Boolean = false,
    /** The last save was rejected because another song has this title and artist. */
    val duplicate: Boolean = false,
)

/**
 * Edits one setlist entry ([entryId]), one library song ([songId] only), or a new library song
 * (both null). Takes plain dependencies so it can be built without Android context.
 */
class EditSongViewModel(
    private val entryId: Long?,
    private val songId: Long?,
    private val repository: Repository,
    private val playback: PlaybackController,
) : ViewModel() {
    private val _ui = MutableStateFlow(EditUi(isEntry = entryId != null, isNewSong = entryId == null && songId == null))
    val ui: StateFlow<EditUi> = _ui.asStateFlow()

    private var persisted: EffectiveSong? = null
    private var librarySong: Song? = null
    private var libraryJustSaved = false
    private var rhythmEdited = false
    private var editedThisSession = false
    private var notesEdited = false
    private var saved = false
    /** This view model, not another sheet, started the audition that is currently running. */
    private var ownsPreview = false

    init {
        viewModelScope.launch { load() }
        // Keep the button in step when the audition ends elsewhere (real playback, lost output).
        viewModelScope.launch {
            playback.state.map { it.isAuditioning }.distinctUntilChanged().collect { playing ->
                if (!playing) ownsPreview = false
                _ui.update { it.copy(previewing = ownsPreview && playing) }
            }
        }
    }

    private suspend fun load() {
        when {
            entryId != null -> {
                val entry = repository.entry(entryId) ?: return
                persisted = entry
                // The click may already be playing an unsaved change for this entry (the player was left
                // and reopened, or the pencil was tapped on the current row): the draft must start from
                // what is audible, or saving would store the stale value.
                val state = playback.state.value
                val preview = state.previewRhythm?.takeIf { state.current?.entryId == entryId }
                if (preview != null) rhythmEdited = true
                _ui.value = EditUi(
                    loaded = true,
                    isEntry = true,
                    title = entry.title,
                    artist = entry.artist.orEmpty(),
                    setlistSongTitle = entry.title,
                    rhythm = preview ?: entry.rhythm,
                    notes = entry.notes.orEmpty(),
                    libraryRhythm = entry.libraryRhythm,
                    entryOverridden = entry.rhythmOverridden,
                    notesOverridden = entry.notesOverridden,
                    libraryNotes = entry.libraryNotes,
                ).derived()
            }
            songId != null -> {
                val song = repository.song(songId) ?: return
                librarySong = song
                _ui.value = EditUi(
                    loaded = true,
                    title = song.title,
                    artist = song.artist.orEmpty(),
                    rhythm = song.rhythm,
                    notes = song.notes.orEmpty(),
                    libraryRhythm = song.rhythm,
                ).derived()
            }
            else -> _ui.value = EditUi(loaded = true, isNewSong = true).derived()
        }
    }

    private fun EditUi.derived(): EditUi = copy(
        clicksPerMinute = rhythm.clicksPerMinute(),
        canSave = loaded && (isEntry || title.isNotBlank()),
        activeOverrideText = overrideText(),
    )

    private fun EditUi.overrideText(): String? {
        val entry = persisted ?: return null
        if (!libraryJustSaved || !(entry.rhythmOverridden || entry.notesOverridden)) return null
        val parts = buildList {
            if (entry.rhythmOverridden) {
                val r = entry.rhythm
                val sig = if (r.timeSignature != libraryRhythm?.timeSignature) ", ${r.timeSignature}" else ""
                add("overrides to ${r.tempo} BPM$sig")
            }
            if (entry.notesOverridden) add("overrides the notes")
        }
        return "This setlist still " + parts.joinToString(" and ")
    }

    private fun edit(rhythm: Boolean = false, notes: Boolean = false, transform: (EditUi) -> EditUi) {
        libraryJustSaved = false
        if (rhythm) {
            rhythmEdited = true
            editedThisSession = true
            rhythmEditedInSheet = true
        }
        if (notes) notesEdited = true
        _ui.update { transform(it).derived() }
        if (rhythm) pushPreview()
    }

    private fun pushPreview() {
        val rhythm = _ui.value.rhythm
        // While auditioning, the sheet's draft is what the click must follow.
        if (_ui.value.previewing) playback.updateAudition(rhythm)
        val id = entryId ?: return
        if (playback.state.value.current?.entryId == id) playback.preview(rhythm)
    }

    /** Starts or stops auditioning the draft rhythm in the edit sheet. */
    fun togglePreview() {
        if (!_ui.value.loaded) return
        if (ownsPreview) stopPreview() else startPreview()
    }

    private fun startPreview() {
        ownsPreview = true
        playback.startAudition(_ui.value.rhythm)
        _ui.update { it.copy(previewing = true) }
    }

    /** Ends only an audition this sheet started; another sheet's preview is left alone. */
    private fun stopPreview() {
        if (!ownsPreview) return
        ownsPreview = false
        playback.stopAudition()
        _ui.update { it.copy(previewing = false) }
    }

    fun setTempo(tempo: Int) = edit(rhythm = true) { it.copy(rhythm = it.rhythm.copy(tempo = tempo.coerceIn(TempoLimits.MIN, TempoLimits.MAX))) }

    /** Typed tempo on commit (IME done / blur): out-of-range numbers are clamped; non-numbers rejected. */
    fun commitTempoInput(text: String): Boolean {
        val n = text.trim().toIntOrNull() ?: return false
        // Unchanged is not an edit: it must not flag the rhythm as edited or touch the preview.
        if (n.coerceIn(TempoLimits.MIN, TempoLimits.MAX) == _ui.value.rhythm.tempo) return true
        setTempo(n)
        return true
    }

    fun setTimeSignature(ts: TimeSignature) {
        if (ts.numerator !in 1..32 || ts.denominator !in listOf(2, 4, 8, 16)) return
        edit(rhythm = true) {
            val r = it.rhythm
            r.timeSignature.let { from ->
                it.copy(rhythm = r.copy(timeSignature = ts, pattern = remapPattern(r.pattern, from, ts)))
            }
        }
    }

    fun setPattern(pattern: ClickPattern) = edit(rhythm = true) { it.copy(rhythm = it.rhythm.copy(pattern = pattern)) }

    fun setNotes(notes: String) = edit(notes = true) { it.copy(notes = notes) }

    fun setTitle(title: String) = edit { it.copy(title = title, duplicate = false) }

    fun setArtist(artist: String) = edit { it.copy(artist = artist, duplicate = false) }

    fun saveToSetlist(): Job? {
        val id = entryId ?: return null
        // Before the entry loads the draft is the default 120 4/4; a very early tap must not store it.
        if (!_ui.value.loaded) return null
        stopPreview()
        return viewModelScope.launch {
            val s = _ui.value
            repository.saveToSetlist(id, s.rhythm, s.notes.ifEmpty { null })
            finish()
        }
    }

    fun saveToLibrary(): Job? {
        val s = _ui.value
        // canSave is false until loaded, so this is a no-op before the song is read.
        if (!s.canSave) return null
        stopPreview()
        return viewModelScope.launch {
            val notes = s.notes.ifEmpty { null }
            if (entryId != null) {
                // An override the user never touched is setlist-specific; it must not leak into the library.
                val before = persisted
                val libRhythm = if (before?.rhythmOverridden == true && !rhythmEdited) before.libraryRhythm else s.rhythm
                val libNotes = if (before?.notesOverridden == true && !notesEdited) before.libraryNotes else notes
                repository.saveToLibrary(entryId, libRhythm, libNotes)
                reloadEntry()
                libraryJustSaved = true
                val now = persisted
                if (now != null && (now.rhythmOverridden || now.notesOverridden)) {
                    // The override keeps playing: show it (not the draft), drop the preview, stay open.
                    rhythmEdited = false
                    notesEdited = false
                    playback.clearPreview()
                    _ui.update { it.copy(rhythm = now.rhythm, notes = now.notes.orEmpty()).derived() }
                    rebaseSheet()
                    return@launch
                }
            } else {
                val artist = s.artist.trim().ifEmpty { null }
                val existing = librarySong
                try {
                    if (existing != null) {
                        repository.updateSong(existing.copy(title = s.title.trim(), artist = artist, rhythm = s.rhythm, notes = notes))
                    } else {
                        repository.createSong(s.title.trim(), artist, s.rhythm, notes)
                    }
                } catch (_: DuplicateSongException) {
                    _ui.update { it.copy(duplicate = true) }
                    return@launch
                }
            }
            finish()
        }
    }

    /** Drops this setlist's rhythm override so the library rhythm plays again. */
    fun clearOverride(): Job? {
        val id = entryId ?: return null
        return viewModelScope.launch {
            repository.clearRhythmOverride(id)
            reloadEntry()
            libraryJustSaved = false
            rhythmEdited = false
            _ui.update { it.copy(rhythm = it.libraryRhythm ?: it.rhythm).derived() }
            pushPreview()
            rebaseSheet()
        }
    }

    /** Drops this setlist's notes override so the library notes show again. */
    fun clearNotesOverride(): Job? {
        val id = entryId ?: return null
        return viewModelScope.launch {
            repository.clearNotesOverride(id)
            reloadEntry()
            libraryJustSaved = false
            notesEdited = false
            _ui.update { it.copy(notes = it.libraryNotes.orEmpty()).derived() }
            rebaseSheet()
        }
    }

    /** What a sheet found when it opened, so cancelling it can put exactly that back. */
    private class SheetBaseline(
        val draft: EditUi,
        val rhythmEdited: Boolean,
        val notesEdited: Boolean,
        val preview: Rhythm?,
    )

    private var sheet: SheetBaseline? = null
    private var rhythmEditedInSheet = false

    /**
     * Called when an edit sheet opens on this view model. The player shares one view model between
     * its tempo drag and the sheet, so cancelling the sheet must undo only what was changed in it.
     */
    fun openSheet() {
        val state = playback.state.value
        sheet = SheetBaseline(
            draft = _ui.value,
            rhythmEdited = rhythmEdited,
            notesEdited = notesEdited,
            preview = state.previewRhythm?.takeIf { entryId != null && state.current?.entryId == entryId },
        )
        rhythmEditedInSheet = false
    }

    // A change written to the database while the sheet is open is not undone by cancelling it.
    private fun rebaseSheet() {
        if (sheet != null) openSheet()
    }

    /**
     * Cancel / swipe-away reverts the live preview. After a save the preview is left for the
     * controller to drop when the refreshed entries arrive, so the click never falls back to the old tempo.
     */
    fun dismiss() {
        stopPreview()
        val baseline = sheet
        sheet = null
        if (entryId == null || saved) return
        if (baseline == null) {
            // Only a change made through this view model is reverted; merely opening and cancelling
            // must not touch a preview that was already playing.
            if (editedThisSession) playback.clearPreview()
            return
        }
        // Back to the draft as it was when the sheet opened (only if it had loaded by then).
        if (baseline.draft.loaded) {
            rhythmEdited = baseline.rhythmEdited
            notesEdited = baseline.notesEdited
            _ui.update { it.copy(rhythm = baseline.draft.rhythm, notes = baseline.draft.notes).derived() }
        }
        // A sheet in which the rhythm never changed leaves the click untouched.
        if (!rhythmEditedInSheet || playback.state.value.current?.entryId != entryId) return
        if (baseline.preview != null) playback.preview(baseline.preview) else playback.clearPreview()
    }

    /** Drops the live preview unconditionally (the player's Revert). */
    fun revert() {
        stopPreview()
        if (entryId != null && !saved) playback.clearPreview()
    }

    private suspend fun reloadEntry() {
        val id = entryId ?: return
        val entry = repository.entry(id) ?: return
        persisted = entry
        _ui.update {
            it.copy(
                libraryRhythm = entry.libraryRhythm,
                entryOverridden = entry.rhythmOverridden,
                notesOverridden = entry.notesOverridden,
                libraryNotes = entry.libraryNotes,
            )
        }
    }

    private fun finish() {
        stopPreview()
        saved = true
        _ui.update { it.copy(finished = true) }
    }

    override fun onCleared() {
        if (ownsPreview) playback.stopAudition()
        super.onCleared()
    }

    class Factory(
        private val entryId: Long?,
        private val songId: Long?,
        private val repository: Repository,
        private val playback: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EditSongViewModel(entryId, songId, repository, playback) as T
    }
}
