package me.jhot.jtmpo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.jhot.jtmpo.share.IncomingResult
import me.jhot.jtmpo.share.receiveIncoming
import me.jhot.jtmpo.ui.player.keepScreenOnFlow
import me.jhot.jtmpo.ui.nav.AppNav
import me.jhot.jtmpo.ui.nav.requestedOrientation
import me.jhot.jtmpo.ui.theme.JtmpoTheme

class MainActivity : ComponentActivity() {
    // The answer doesn't matter: the service runs either way. Media-session notifications are exempt
    // from POST_NOTIFICATIONS on Android 13+, so the controls show regardless; the request is still made
    // so the playback notification is visible wherever that exemption does not apply.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Asked at launch so it's settled before the first play; the media notification carries
        // the play/pause control.
        if (savedInstanceState == null) requestNotificationPermission()
        // Held here rather than in the player so it also covers the other tabs while the click plays.
        val graph = (application as JtmpoApp).graph
        lifecycleScope.launch {
            keepScreenOnFlow(graph.playback, graph.playerOpen, graph.settings).distinctUntilChanged().collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // Overrides the manifest's userPortrait, which lets the system (Tasker, say) rotate to upside down.
        lifecycleScope.launch {
            combine(graph.settings.flipScreen, graph.settings.flipPlayerOnly, graph.playerOpen, ::requestedOrientation)
                .distinctUntilChanged()
                .collect { requestedOrientation = it }
        }
        setContent {
            JtmpoTheme { AppNav(graph) }
        }
        if (shouldReceiveLaunchIntent(intent?.flags ?: 0, restored = savedInstanceState != null)) receive(intent)
    }

    // singleTask: a file opened from another app while jtmpo runs arrives here, not in a second instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    /** Reads a VIEW intent's file right away, while the sender's read grant is certain to hold. */
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val graph = (application as JtmpoApp).graph
        lifecycleScope.launch {
            val message = when (receiveIncoming(this@MainActivity, uri, graph.incoming)) {
                IncomingResult.Accepted -> return@launch
                IncomingResult.TooLarge -> R.string.incoming_too_large
                IncomingResult.Unreadable -> R.string.incoming_unreadable
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(permission)
        }
    }
}

/**
 * A recreated activity still holds the intent it was started with, and a relaunch from Recents replays
 * the task's original one; either way that file was already taken, so it must not be offered again.
 */
fun shouldReceiveLaunchIntent(flags: Int, restored: Boolean): Boolean =
    !restored && flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
