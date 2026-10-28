package me.jhot.jtmpo.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.jhot.jtmpo.data.Repository

/**
 * Feeds repository changes for the loaded setlist into the controller, so edits, removals and
 * reorders made anywhere reach the running click. Following the loaded setlist id, the previous
 * collection is cancelled whenever another setlist is loaded.
 */
class PlaybackBinding(
    private val repository: Repository,
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        controller.state
            .map { it.setlistId }
            .distinctUntilChanged()
            // Each emission carries its setlist id, so one buffered from before a switch can be told apart.
            .flatMapLatest { id -> if (id == null) emptyFlow() else repository.entries(id).map { id to it } }
            .onEach { (id, entries) -> controller.updateEntries(id, entries) }
            .launchIn(scope)
    }
}
