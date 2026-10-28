package me.jhot.jtmpo.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.LastPositionStore
import me.jhot.jtmpo.data.Repository

/**
 * Remembers where the player is (by entry id) and puts it back after a restart. Restoring only
 * ever loads the controller paused; it never starts the click, and never replaces a setlist that
 * something else already loaded.
 */
class LastPositionTracker(
    private val controller: PlaybackController,
    private val repository: Repository,
    private val store: LastPositionStore,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            restore()
            // Saving starts only after the restore attempt, so the empty initial state can't clobber it.
            // Unloading (a dismissed mini player) forgets the position; the empty state before anything
            // was ever loaded must not, or a failed restore would wipe what it couldn't read.
            var seenPosition = false
            controller.state
                .map { s -> s.setlistId?.let { id -> s.current?.let { id to it.entryId } } }
                .distinctUntilChanged()
                .collect { position ->
                    if (position != null) {
                        seenPosition = true
                        store.save(position.first, position.second)
                    } else if (seenPosition) {
                        seenPosition = false
                        store.clear()
                    }
                }
        }
    }

    private suspend fun restore() {
        val (setlistId, entryId) = runCatching { store.load() }.getOrNull() ?: return
        val entries = runCatching { repository.entries(setlistId).first() }.getOrNull() ?: return
        if (entries.isEmpty()) return
        val index = entries.indexOfFirst { it.entryId == entryId }.coerceAtLeast(0)
        // Re-checked at the last moment: a setlist the user already started wins.
        synchronized(controller) {
            if (controller.state.value.setlistId == null) controller.load(setlistId, entries, index)
        }
    }
}
