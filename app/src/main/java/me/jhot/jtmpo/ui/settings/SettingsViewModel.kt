package me.jhot.jtmpo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jhot.jtmpo.backup.BackupCodec
import me.jhot.jtmpo.backup.BackupException
import me.jhot.jtmpo.data.BackupSnapshot
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.data.SettingsStore
import me.jhot.jtmpo.playback.PlaybackController

/** One-shot results shown as a snackbar. */
enum class SettingsMessage { EXPORTED, EXPORT_FAILED, RESTORED, RESTORE_UNREADABLE, RESTORE_NEWER, RESTORE_DUPLICATES, RESTORE_FAILED }

data class SettingsUi(
    val keepAwake: Boolean = true,
    val pulseLeadMs: Int = SettingsStore.DEFAULT_PULSE_LEAD_MS,
    val flipScreen: Boolean = false,
    val flipPlayerOnly: Boolean = true,
    /** A decoded, validated backup waiting for the user to confirm replacing everything. */
    val pendingRestore: SettingsViewModel.PendingRestore? = null,
)

class SettingsViewModel(
    private val repository: Repository,
    private val settings: SettingsStore,
    private val playback: PlaybackController,
) : ViewModel() {
    data class PendingRestore(val songs: Int, val setlists: Int)

    private val pending = MutableStateFlow<BackupSnapshot?>(null)
    private val messageChannel = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = messageChannel.receiveAsFlow()

    val ui: StateFlow<SettingsUi> = combine(
        settings.keepAwake,
        settings.pulseLeadMs,
        settings.flipScreen,
        settings.flipPlayerOnly,
        pending,
    ) { keepAwake, pulseLeadMs, flipScreen, flipPlayerOnly, snapshot ->
        SettingsUi(
            keepAwake,
            pulseLeadMs,
            flipScreen,
            flipPlayerOnly,
            snapshot?.let { PendingRestore(it.songs.size, it.setlists.size) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    fun setKeepAwake(value: Boolean) {
        viewModelScope.launch { settings.setKeepAwake(value) }
    }

    fun setFlipScreen(value: Boolean) {
        viewModelScope.launch { settings.setFlipScreen(value) }
    }

    fun setFlipPlayerOnly(value: Boolean) {
        viewModelScope.launch { settings.setFlipPlayerOnly(value) }
    }

    fun setPulseLeadMs(value: Int) {
        viewModelScope.launch { settings.setPulseLeadMs(value) }
    }

    /** [write] receives the backup text and stores it wherever the user picked. */
    fun export(write: suspend (String) -> Unit) {
        viewModelScope.launch {
            // Leaving the screen must not cut the file off half written.
            val ok = withContext(NonCancellable) {
                try {
                    write(BackupCodec.encode(repository.snapshot()))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            messageChannel.send(if (ok) SettingsMessage.EXPORTED else SettingsMessage.EXPORT_FAILED)
        }
    }

    /** Reads and validates a file; only a good backup becomes [SettingsUi.pendingRestore]. */
    fun loadBackup(read: suspend () -> String) {
        viewModelScope.launch {
            pending.value = null
            val text = runCatching { read() }.getOrNull()
            val decoded = if (text == null) null else BackupCodec.decode(text)
            val snapshot = decoded?.getOrNull()
            if (snapshot != null) {
                pending.value = snapshot
                return@launch
            }
            messageChannel.send(
                when (decoded?.exceptionOrNull()) {
                    is BackupException.NewerVersion -> SettingsMessage.RESTORE_NEWER
                    is BackupException.DuplicateSong -> SettingsMessage.RESTORE_DUPLICATES
                    else -> SettingsMessage.RESTORE_UNREADABLE
                },
            )
        }
    }

    fun cancelRestore() {
        pending.value = null
    }

    fun confirmRestore() {
        val snapshot = pending.value ?: return
        pending.value = null
        viewModelScope.launch {
            // Replace and unload together or not at all: a restore cancelled between the two would
            // leave the click playing entries that no longer exist.
            val ok = withContext(NonCancellable) {
                val replaced = try {
                    repository.replaceAll(snapshot)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                // The loaded setlist and its entries may no longer exist (or may now mean something else).
                if (replaced) playback.unload()
                replaced
            }
            messageChannel.send(if (ok) SettingsMessage.RESTORED else SettingsMessage.RESTORE_FAILED)
        }
    }

    class Factory(
        private val repository: Repository,
        private val settings: SettingsStore,
        private val playback: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository, settings, playback) as T
    }
}
