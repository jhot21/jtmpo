package me.jhot.jtmpo.playback

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.jhot.jtmpo.JtmpoApp
import me.jhot.jtmpo.MainActivity
import me.jhot.jtmpo.R

/**
 * Keeps the process in the foreground while a setlist is loaded, so the click survives screen-off
 * and backgrounding. Mirrors [PlaybackController] state into a notification and a platform
 * [MediaSession] whose only actions are play and pause (media buttons and lock screen).
 */
class PlaybackService : Service() {
    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "me.jhot.jtmpo.action.TOGGLE"
        private const val SESSION_ACTIONS =
            SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or SessionState.ACTION_PLAY_PAUSE

        /** Starts the service; call when playback starts, from the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: IllegalStateException) {
                // Background start refused (Android 12+); the click itself is unaffected.
                Log.w(TAG, "could not start playback service", e)
            }
        }
    }

    private val scope = MainScope()
    private lateinit var playback: PlaybackController
    private lateinit var session: MediaSession

    // stopSelf() before the first startForeground() crashes a service started with
    // startForegroundService(), so the empty-state stop waits for onStartCommand.
    private var foreground = false

    // Not part of PlaybackState; resolved from the repository so the subtitle can name the setlist.
    private var setlistName: String? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as JtmpoApp).graph
        playback = graph.playback
        session = MediaSession(this, "jtmpo").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = playback.play()
                override fun onPause() = playback.pause()
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        combine(playback.state, graph.repository.setlists()) { state, setlists ->
            setlistName = setlists.firstOrNull { it.id == state.setlistId }?.name
            state
        }.onEach(::render).launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(playback.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foreground = true
        if (intent?.action == ACTION_TOGGLE) {
            if (playback.state.value.isPlaying) playback.pause() else playback.play()
        }
        if (playback.state.value.current == null) stop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun render(state: PlaybackState) {
        val current = state.current
        if (current == null) {
            if (foreground) stop()
            return
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, current.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(state, current.artist))
                .build(),
        )
        session.setPlaybackState(
            SessionState.Builder()
                .setActions(SESSION_ACTIONS)
                .setState(
                    if (state.isPlaying) SessionState.STATE_PLAYING else SessionState.STATE_PAUSED,
                    SessionState.PLAYBACK_POSITION_UNKNOWN,
                    if (state.isPlaying) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
    }

    private fun subtitle(state: PlaybackState, artist: String?) =
        nowPlayingSubtitle(artist, positionText(setlistName, state.currentIndex, state.entries.size))

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(state: PlaybackState): Notification {
        val playing = state.isPlaying
        val toggle = PendingIntent.getService(
            this,
            0,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            getString(if (playing) R.string.pause else R.string.play),
            toggle,
        ).build()
        val song = state.current
        return Notification.Builder(this, JtmpoApp.PLAYBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.let { subtitle(state, it.artist) })
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
