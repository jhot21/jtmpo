package me.jhot.jtmpo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.view.WindowManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(permission)
        }
    }
}
