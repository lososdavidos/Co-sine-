@file:OptIn(ExperimentalMaterial3Api::class)

package app.sine.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val ARTIST = "artist/{id}"
    const val ALBUM = "album/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val NOW_PLAYING = "now-playing"
    const val SETTINGS = "settings"
    const val ADD_ACCOUNT = "add-account"

    fun artist(id: String) = "artist/${Uri.encode(id)}"
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun playlist(id: String) = "playlist/${Uri.encode(id)}"
}

@Composable
fun SineRoot() {
    val graph = LocalContext.current.graph
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val accounts by graph.accounts.state.collectAsStateWithLifecycle()
        val session by graph.activeSession.collectAsStateWithLifecycle()
        Surface(Modifier.fillMaxSize()) {
            val active = session
            if (accounts.accounts.isEmpty() || active == null) {
                AddAccountScreen(onDone = {}, onCancel = null)
            } else {
                // Switching accounts rebuilds everything below: no state leaks between accounts.
                key(active.account.id) { MainScaffold(active) }
            }
        }
    }
}

private data class Destination(val route: String, val label: String, val icon: ImageVector)

// Three destinations (§6.2). Add is a Library action, Jam lives in now-playing.
private val destinations = listOf(
    Destination(Routes.HOME, "Home", Icons.Default.Home),
    Destination(Routes.SEARCH, "Search", Icons.Default.Search),
    Destination(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.List),
)

@Composable
private fun MainScaffold(session: AccountSession) {
    val graph = LocalContext.current.graph
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = destinations.any { it.route == route }
    val network by graph.network.state.collectAsStateWithLifecycle()
    val reachable by session.reachable.collectAsStateWithLifecycle()
    val offline = !network.connected || !reachable

    Scaffold(
        topBar = {
            if (route != Routes.NOW_PLAYING) {
                TopAppBar(
                    title = { Text(titleFor(route)) },
                    navigationIcon = {
                        if (!topLevel && nav.previousBackStackEntry != null) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        // The account control (§6.11): every screen except now-playing.
                        if (route != Routes.SETTINGS) {
                            IconButton(onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }) {
                                Icon(Icons.Default.AccountCircle, contentDescription = "Accounts and settings")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (route != Routes.NOW_PLAYING) {
                Column {
                    MiniPlayer(onOpen = { nav.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } })
                    NavigationBar {
                        destinations.forEach { d ->
                            NavigationBarItem(
                                selected = route == d.route,
                                onClick = { nav.navigateTopLevel(d.route) },
                                icon = { Icon(d.icon, contentDescription = null) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (offline) {
                Text(
                    Voice.serverUnreachable(session.account.isCosine),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { session.forceOnlineRetry() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.weight(1f)) {
                composable(Routes.HOME) { HomeScreen(session, nav) }
                composable(Routes.SEARCH) { SearchScreen(session, nav) }
                composable(Routes.LIBRARY) { LibraryScreen(session, nav) }
                composable(Routes.ARTIST, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ArtistScreen(session, it.arguments?.getString("id").orEmpty(), nav)
                }
                composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    AlbumScreen(session, it.arguments?.getString("id").orEmpty(), nav)
                }
                composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    PlaylistScreen(session, it.arguments?.getString("id").orEmpty(), nav)
                }
                composable(Routes.NOW_PLAYING) { NowPlayingScreen(onClose = { nav.popBackStack() }) }
                composable(Routes.SETTINGS) { SettingsScreen(session, nav) }
                composable(Routes.ADD_ACCOUNT) {
                    AddAccountScreen(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun titleFor(route: String?) = when (route) {
    Routes.HOME -> "Home"
    Routes.SEARCH -> "Search"
    Routes.LIBRARY -> "Library"
    Routes.SETTINGS -> "Settings"
    Routes.ADD_ACCOUNT -> "Add account"
    else -> ""
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val player = LocalContext.current.graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val item = state.current ?: return
    val meta = item.mediaMetadata
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Art(meta.artworkUri, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(meta.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { player.togglePlay() }) { Text(if (state.isPlaying) "Pause" else "Play") }
        TextButton(onClick = { player.next() }) { Text("Next") }
    }
}
