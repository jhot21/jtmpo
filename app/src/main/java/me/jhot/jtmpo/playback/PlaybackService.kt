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
import me.jhot.jtmpo.practice.PracticeController
import me.jhot.jtmpo.practice.PracticeState

/**
 * Keeps the process in the foreground while a setlist is loaded or the practice click is engaged,
 * so the click survives screen-off and backgrounding. Mirrors [PlaybackController] and
 * [PracticeController] state into a notification and a platform [MediaSession] whose only actions
 * are play and pause (media buttons and lock screen).
 */
class PlaybackService : Service() {
    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1
        internal const val ACTION_TOGGLE = "me.jhot.jtmpo.action.TOGGLE"
        private const val PRACTICE_TITLE = "Practice"
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
    private lateinit var practice: PracticeController
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
        practice = graph.practice
        session = MediaSession(this, "jtmpo").apply {
            setCallback(object : MediaSession.Callback() {
                // Whichever click owns the output is what the media button must control.
                override fun onPlay() {
                    if (practice.state.value.engaged) practice.play() else playback.play()
                }

                override fun onPause() {
                    if (practice.state.value.engaged) practice.pause() else playback.pause()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        combine(playback.state, practice.state, graph.repository.setlists()) { state, practiceState, setlists ->
            setlistName = setlists.firstOrNull { it.id == state.setlistId }?.name
            state to practiceState
        }.onEach { (state, practiceState) -> render(state, practiceState) }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(playback.state.value, practice.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        foreground = true
        if (intent?.action == ACTION_TOGGLE) {
            if (practice.state.value.engaged) {
                if (practice.state.value.isPlaying) practice.pause() else practice.play()
            } else if (playback.state.value.isPlaying) {
                playback.pause()
            } else {
                playback.play()
            }
        }
        // Practice keeps the service alive with no setlist: its click must survive screen-off too.
        if (playback.state.value.current == null && !practice.state.value.engaged) stop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun render(state: PlaybackState, practiceState: PracticeState) {
        if (!practiceState.engaged && state.current == null) {
            if (foreground) stop()
            return
        }
        val playing = isPlaying(state, practiceState)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title(state, practiceState))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle(state, practiceState))
                .build(),
        )
        session.setPlaybackState(
            SessionState.Builder()
                .setActions(SESSION_ACTIONS)
                .setState(
                    if (playing) SessionState.STATE_PLAYING else SessionState.STATE_PAUSED,
                    SessionState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(state, practiceState))
    }

    private fun isPlaying(state: PlaybackState, practiceState: PracticeState) =
        if (practiceState.engaged) practiceState.isPlaying else state.isPlaying

    private fun title(state: PlaybackState, practiceState: PracticeState): String =
        if (practiceState.engaged) PRACTICE_TITLE else state.current?.title ?: getString(R.string.app_name)

    /** The line under the title: the practice tempo, or the song's artist and setlist position. */
    private fun subtitle(state: PlaybackState, practiceState: PracticeState): String? {
        if (practiceState.engaged) return "${practiceState.tempo} bpm"
        val current = state.current ?: return null
        return nowPlayingSubtitle(current.artist, positionText(setlistName, state.currentIndex, state.entries.size))
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(state: PlaybackState, practiceState: PracticeState): Notification {
        val playing = isPlaying(state, practiceState)
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
        return Notification.Builder(this, JtmpoApp.PLAYBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(state, practiceState))
            .setContentText(subtitle(state, practiceState))
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
