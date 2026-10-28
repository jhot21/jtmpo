package me.jhot.jtmpo.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.navArgument
import me.jhot.jtmpo.AppGraph
import me.jhot.jtmpo.R
import me.jhot.jtmpo.ui.library.LibraryScreen
import me.jhot.jtmpo.ui.player.PlayerScreen
import me.jhot.jtmpo.ui.settings.ImportScreen
import me.jhot.jtmpo.ui.settings.SettingsScreen
import me.jhot.jtmpo.ui.setlists.SetlistDetailScreen
import me.jhot.jtmpo.ui.setlists.SetlistsScreen
import me.jhot.jtmpo.ui.setlists.SetlistsViewModel
import me.jhot.jtmpo.ui.theme.Surface0
import me.jhot.jtmpo.ui.theme.SurfaceBar
import me.jhot.jtmpo.ui.theme.TextSecondary

object Routes {
    const val SETLISTS = "setlists"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val SETLIST_PATTERN = "setlist/{id}"
    const val PLAYER = "player"
    const val IMPORT = "import"

    fun setlist(id: Long) = "setlist/$id"
}

/** The player is full screen; every other destination keeps the bottom nav. */
fun showsBottomNav(route: String?): Boolean = route != Routes.PLAYER

/** The tab a route belongs to; null for the full-screen player. */
fun tabOf(route: String?): String? = when (route) {
    Routes.SETLISTS, Routes.SETLIST_PATTERN -> Routes.SETLISTS
    Routes.LIBRARY -> Routes.LIBRARY
    Routes.SETTINGS, Routes.IMPORT -> Routes.SETTINGS
    else -> null
}

sealed interface TabBack {
    data object Default : TabBack
    data object LeaveApp : TabBack
    data class SwitchTo(val tab: String) : TabBack
}

/**
 * Setlists and Library are both top level, so Back leaves the app from either. Settings is a side trip:
 * Back returns to the tab it was opened from.
 */
fun tabBack(route: String?, settingsOrigin: String): TabBack = when (route) {
    Routes.LIBRARY -> TabBack.LeaveApp
    Routes.SETTINGS -> TabBack.SwitchTo(settingsOrigin)
    else -> TabBack.Default
}

private data class TopLevel(val route: String, val labelRes: Int, val iconRes: Int)

private val topLevel = listOf(
    TopLevel(Routes.SETLISTS, R.string.nav_setlists, R.drawable.ic_nav_setlists),
    TopLevel(Routes.LIBRARY, R.string.nav_library, R.drawable.ic_nav_library),
    TopLevel(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_nav_settings),
)

@Composable
fun AppNav(graph: AppGraph) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playback by graph.playback.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var settingsOrigin by rememberSaveable { mutableStateOf(Routes.SETLISTS) }

    fun openTab(target: String) {
        val current = tabOf(route)
        if (target == Routes.SETTINGS && current != null && current != Routes.SETTINGS) settingsOrigin = current
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = Surface0,
        bottomBar = {
            if (showsBottomNav(route)) {
                Column {
                    // Leaving the player never pauses; the bar just takes over as the control.
                    MiniPlayer(
                        state = playback,
                        onToggle = { if (playback.isPlaying) graph.playback.pause() else graph.playback.play() },
                        onNext = graph.playback::next,
                        onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                        onDismiss = graph.playback::unload,
                    )
                    NavigationBar(containerColor = SurfaceBar) {
                        topLevel.forEach { item ->
                            // Setlist detail belongs to the Setlists tab.
                            val selected = backStack?.destination?.hierarchy?.any { it.route == item.route } == true ||
                                (item.route == Routes.SETLISTS && route == Routes.SETLIST_PATTERN) ||
                                (item.route == Routes.SETTINGS && route == Routes.IMPORT)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { openTab(item.route) },
                                icon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                                label = { Text(stringResource(item.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.SETLISTS,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.SETLISTS) { SetlistsScreen(graph, onOpen = { nav.navigate(Routes.setlist(it)) }) }
            composable(Routes.LIBRARY) { LibraryScreen(graph) }
            composable(Routes.SETTINGS) { SettingsScreen(graph, onImportFzt = { nav.navigate(Routes.IMPORT) }) }
            composable(Routes.IMPORT) { ImportScreen(graph, onBack = { nav.popBackStack() }) }
            composable(
                Routes.SETLIST_PATTERN,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                // The delete runs in the Setlists screen's view model (the start destination, always below),
                // so it outlives this screen and that screen can offer Undo.
                val setlistsOwner = remember(entry) { runCatching { nav.getBackStackEntry(Routes.SETLISTS) }.getOrDefault(entry) }
                val setlistsVm: SetlistsViewModel = viewModel(setlistsOwner, factory = SetlistsViewModel.Factory(graph.repository))
                SetlistDetailScreen(
                    graph,
                    id,
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    onDelete = {
                        setlistsVm.delete(id)
                        nav.popBackStack()
                    },
                )
            }
            composable(Routes.PLAYER) { PlayerScreen(graph, onBack = { nav.popBackStack() }) }
        }
        // After the NavHost so it wins over the host's own pop-to-start-destination handler.
        val back = tabBack(route, settingsOrigin)
        BackHandler(enabled = back != TabBack.Default) {
            when (back) {
                TabBack.LeaveApp -> activity?.moveTaskToBack(true) // what the system does at the root
                is TabBack.SwitchTo -> openTab(back.tab)
                TabBack.Default -> Unit
            }
        }
    }
}
