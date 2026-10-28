package me.jhot.jtmpo.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Tracks the view models a test creates and stops their collectors before teardown.
 *
 * Room and DataStore emit from their own threads, and a view model collector resumes through
 * `Dispatchers.Main`. A collector left running when `Dispatchers.resetMain()` executes can touch Main
 * on that thread at the same moment, which throws "Dispatchers.Main is used concurrently". Call
 * [shutdown] first in `@After`, before closing the database and resetting Main.
 */
class TestViewModels {
    private val tracked = mutableListOf<ViewModel>()

    fun <T : ViewModel> track(vm: T): T = vm.also { tracked += it }

    fun shutdown() = runBlocking {
        withTimeout(5_000) {
            tracked.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
        tracked.clear()
    }
}
