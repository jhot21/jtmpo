package me.jhot.jtmpo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.jhot.jtmpo.playback.PlaybackService
import me.jhot.jtmpo.share.pruneShareFiles

class JtmpoApp : Application() {
    companion object {
        const val PLAYBACK_CHANNEL = "playback"
    }

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createPlaybackChannel()
        pruneShareFiles(cacheDir)
        // The service goes foreground whenever playback starts and stops itself once nothing is loaded.
        graph.playback.state
            .map { it.isPlaying }
            .distinctUntilChanged()
            .filter { it }
            .onEach { PlaybackService.start(this) }
            .launchIn(MainScope())
    }

    private fun createPlaybackChannel() {
        val channel = NotificationChannel(
            PLAYBACK_CHANNEL,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
