package me.jhot.jtmpo.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.clicksPerMinute

data class PlaybackState(
    val setlistId: Long?,
    val entries: List<EffectiveSong>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val previewRhythm: Rhythm?,
    /** A temporary click from the song editor; audible without touching what the player shows. */
    val auditionRhythm: Rhythm? = null,
) {
    val current: EffectiveSong? get() = entries.getOrNull(currentIndex)
    /** What the player shows: the current entry's live edit preview, else its saved rhythm. */
    val effectiveRhythm: Rhythm? get() = previewRhythm ?: current?.rhythm
    /** What [ClickOutput] plays: an editor audition wins over the loaded rhythm. */
    internal val outputRhythm: Rhythm? get() = auditionRhythm ?: effectiveRhythm
    val isAuditioning: Boolean get() = auditionRhythm != null
}

/** Pure state machine; every transition that changes the effective rhythm pushes the new cpm to [output]. */
class PlaybackController(private val output: ClickOutput) {
    private val _state = MutableStateFlow(PlaybackState(null, emptyList(), -1, false, null))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * Loads a setlist. Never starts the click: loaded while paused it stays paused. Loaded while the
     * click is playing, it switches at once and keeps playing at the new song's rate, because a click
     * that goes silent mid-set is worse than a switch (an empty setlist stops it).
     */
    @Synchronized
    fun load(setlistId: Long, entries: List<EffectiveSong>, startIndex: Int) {
        val old = _state.value
        val index = if (entries.isEmpty()) -1 else startIndex.coerceIn(0, entries.lastIndex)
        // An audition belongs to the sheet that started it, not to whatever setlist loads next.
        if (old.isAuditioning) output.stop()
        val keepPlaying = old.isPlaying && index >= 0
        if (old.isPlaying && !keepPlaying) output.stop()
        apply(old, PlaybackState(setlistId, entries, index, keepPlaying, null))
    }

    /** Stops the click and forgets the loaded setlist, e.g. after a restore replaced all data. */
    @Synchronized
    fun unload() {
        val old = _state.value
        if (old.isPlaying || old.isAuditioning) output.stop()
        _state.value = PlaybackState(null, emptyList(), -1, false, null)
    }

    @Synchronized
    fun play() {
        val s = _state.value
        val rhythm = s.effectiveRhythm ?: return
        if (s.isPlaying) return
        // A sheet preview never blocks real playback: it ends as the loaded click starts.
        if (s.isAuditioning) output.stop()
        val clean = s.copy(isPlaying = true, auditionRhythm = null)
        output.setClicksPerMinute(rhythm.clicksPerMinute())
        output.start()
        _state.value = clean
    }

    @Synchronized
    fun pause() {
        val s = _state.value
        if (!s.isPlaying && !s.isAuditioning) return
        output.stop()
        _state.value = s.copy(isPlaying = false, auditionRhythm = null)
    }

    @Synchronized
    fun next() = select(_state.value.currentIndex + 1)

    @Synchronized
    fun previous() = select(_state.value.currentIndex - 1)

    @Synchronized
    fun select(index: Int) {
        val s = _state.value
        if (index !in s.entries.indices || index == s.currentIndex) return
        apply(s, s.copy(currentIndex = index, previewRhythm = null))
    }

    /**
     * Fresh entries of [setlistId]. An emission for a setlist that is no longer loaded (buffered
     * before another setlist was loaded) is ignored.
     */
    @Synchronized
    fun updateEntries(setlistId: Long, entries: List<EffectiveSong>) {
        val s = _state.value
        if (s.setlistId != setlistId) return
        val currentId = s.current?.entryId
        val keptIndex = if (currentId == null) -1 else entries.indexOfFirst { it.entryId == currentId }
        if (keptIndex >= 0) {
            // A preview that the refreshed entry now matches was saved: drop it in this same update so
            // the click never falls back to the stale rhythm in between.
            val saved = s.previewRhythm != null && entries[keptIndex].rhythm == s.previewRhythm
            apply(s, s.copy(entries = entries, currentIndex = keptIndex, previewRhythm = if (saved) null else s.previewRhythm))
            return
        }
        // Current entry is gone (or there was none): the next entry slides into its slot; paused.
        val index = if (entries.isEmpty()) -1 else s.currentIndex.coerceIn(0, entries.lastIndex)
        if (s.isPlaying) output.stop()
        apply(s, s.copy(entries = entries, currentIndex = index, isPlaying = false, previewRhythm = null))
    }

    @Synchronized
    fun preview(rhythm: Rhythm) {
        val s = _state.value
        apply(s, s.copy(previewRhythm = rhythm))
    }

    @Synchronized
    fun clearPreview() {
        val s = _state.value
        apply(s, s.copy(previewRhythm = null))
    }

    /**
     * Starts a temporary click from the song editor. Any loaded click stops first: only one click
     * can use the output, and the audition is what the user is listening to. It never counts as
     * transport playback, so the player, notification and mini player stay paused.
     */
    @Synchronized
    fun startAudition(rhythm: Rhythm) {
        val s = _state.value
        if (s.isPlaying) output.stop()
        _state.value = s.copy(isPlaying = false, auditionRhythm = rhythm)
        output.setClicksPerMinute(rhythm.clicksPerMinute())
        output.start()
    }

    /** Live changes from the editor while an audition is running. */
    @Synchronized
    fun updateAudition(rhythm: Rhythm) {
        val s = _state.value
        if (s.auditionRhythm == null) return
        apply(s, s.copy(auditionRhythm = rhythm))
    }

    /** Ends the audition; whatever was loaded stays paused. */
    @Synchronized
    fun stopAudition() {
        val s = _state.value
        if (s.auditionRhythm == null) return
        output.stop()
        _state.value = s.copy(auditionRhythm = null)
    }

    private fun apply(old: PlaybackState, new: PlaybackState) {
        _state.value = new
        val rhythm = new.outputRhythm
        if (rhythm != null && rhythm != old.outputRhythm) output.setClicksPerMinute(rhythm.clicksPerMinute())
    }
}
