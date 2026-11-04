package me.jhot.jtmpo.practice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.jhot.jtmpo.data.SettingsStore

/**
 * Remembers Practice across launches: restores the stored settings once, then saves every later
 * change. One collector in one scope, so a burst of changes can never be written out of order.
 */
class PracticePersistence(
    private val settings: SettingsStore,
    private val controller: PracticeController,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            val stored = settings.practice.first()
            controller.restore(stored)
            // The restore itself is not news: skip emissions until the settings differ from what was
            // stored, and write every change after that.
            controller.state.map { it.settings }.distinctUntilChanged().dropWhile { it == stored }.collect {
                settings.setPractice(it)
            }
        }
    }
}
