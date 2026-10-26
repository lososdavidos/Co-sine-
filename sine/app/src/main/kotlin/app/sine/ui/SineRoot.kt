@file:OptIn(ExperimentalMaterial3Api::class)

package app.sine.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseTheme
import app.sine.ui.phase.Signal
import kotlinx.coroutines.delay

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
    const val ADD = "add?text={text}"
    const val REVIEW = "review"
    const val CORRECT = "review/{track}"

    fun artist(id: String) = "artist/${Uri.encode(id)}"
    fun album(id: String) = "album/${Uri.encode(id)}"
    fun playlist(id: String) = "playlist/${Uri.encode(id)}"
    fun add(text: String = "") = "add?text=${Uri.encode(text)}"
    fun correct(track: String) = "review/${Uri.encode(track)}"
}

@Composable
fun SineRoot() {
    val graph = LocalContext.current.graph
    PhaseTheme {
        val accounts by graph.accounts.state.collectAsStateWithLifecycle()
        val session by graph.activeSession.collectAsStateWithLifecycle()
        val share by graph.incomingShare.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // A share goes to the share target, chosen by construction (§6.10): the
        // most recently added Cosine account. No setting, no picker.
        LaunchedEffect(share) {
            if (share == null) return@LaunchedEffect
            val target = graph.accounts.state.value.shareTarget
            when {
                target == null -> {
                    Toast.makeText(context, "No Cosine account to add music to.", Toast.LENGTH_LONG).show()
                    graph.incomingShare.value = null
                }
                target.id != graph.activeSession.value?.account?.id -> graph.accounts.setActive(target.id)
            }
        }

        Surface(Modifier.fillMaxSize(), color = PhaseColor.Ground, contentColor = PhaseColor.Text1) {
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

// Three destinations (§6.2), words in the shelf. Add is a Library action, Jam lives in now-playing.
private val destinations = listOf(
    Destination(Routes.HOME, "Home"),
    Destination(Routes.SEARCH, "Search"),
    Destination(Routes.LIBRARY, "Library"),
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
    val account by session.accountFlow.collectAsStateWithLifecycle()
    val share by graph.incomingShare.collectAsStateWithLifecycle()
    val shelf = remember { ShelfController() }
    // Which of the three the shelf shows as here: the last one you were on.
    var here by rememberSaveable { mutableStateOf(Routes.HOME) }

    // The mirror (§5.2) catches up every minute while Sine is on screen, and
    // straight away when it comes back on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session, network.connected) {
        if (!network.connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                session.sync()
                delay(60_000)
            }
        }
    }

    // Open Add with the shared text once this is the share target's scaffold.
    LaunchedEffect(share, account.id) {
        val text = share ?: return@LaunchedEffect
        if (graph.accounts.state.value.shareTarget?.id == account.id) {
            graph.incomingShare.value = null
            nav.navigate(Routes.add(text)) { launchSingleTop = true }
        }
    }

    LaunchedEffect(route) {
        if (topLevel && route != null) here = route
        shelf.question = null // a question belongs to the screen that asked it
    }
    // Losing the server is said once, in the shelf; the Offline mark stays in the top bar.
    LaunchedEffect(offline) {
        if (offline) say(Voice.serverUnreachable(session.account.isCosine), Signal.Server)
    }

    val chrome = Chrome(
        offline = offline,
        onRetry = { session.forceOnlineRetry() },
        // The account control (§6.11): every screen except now-playing, and Settings itself.
        onAccount = if (route == Routes.SETTINGS) null else ({ nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }),
    )

    CompositionLocalProvider(LocalShelf provides shelf, LocalChrome provides chrome) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(160)) }, exitTransition = { fadeOut(tween(160)) },
            ) {
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
                // The sheet (§6.6) comes in on the overlay's 220ms and leaves on 160, opacity only.
                composable(
                    Routes.NOW_PLAYING,
                    enterTransition = { fadeIn(tween(220)) }, exitTransition = { fadeOut(tween(160)) },
                ) { NowPlayingScreen(onClose = { nav.popBackStack() }) }
                composable(Routes.SETTINGS) { SettingsScreen(session, nav) }
                composable(
                    Routes.ADD,
                    arguments = listOf(navArgument("text") { type = NavType.StringType; defaultValue = "" }),
                ) {
                    AddScreen(session, it.arguments?.getString("text").orEmpty(), onCorrect = { track -> nav.navigate(Routes.correct(track)) })
                }
                composable(Routes.REVIEW) { ReviewScreen(session, onOpen = { nav.navigate(Routes.correct(it)) }) }
                composable(Routes.CORRECT, arguments = listOf(navArgument("track") { type = NavType.StringType })) {
                    CorrectionScreen(session, it.arguments?.getString("track").orEmpty(), onDone = { nav.popBackStack() })
                }
                composable(Routes.ADD_ACCOUNT) {
                    AddAccountScreen(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() })
                }
            }
            if (!topLevel && route != null && route != Routes.NOW_PLAYING && route != Routes.ADD_ACCOUNT) {
                DetailBar(
                    back = backLabel(nav.previousBackStackEntry?.destination?.route),
                    onBack = { nav.popBackStack() },
                    showAccount = route != Routes.SETTINGS,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            if (route != Routes.NOW_PLAYING && route != Routes.ADD_ACCOUNT) {
                Shelf(
                    destinations, here,
                    onNavigate = { nav.navigateTopLevel(it) },
                    onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) { launchSingleTop = true } },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** The back pill names where it goes when it can; a page about one thing says Back. */
private fun backLabel(route: String?) = when (route) {
    Routes.HOME -> "Home"
    Routes.SEARCH -> "Search"
    Routes.LIBRARY -> "Library"
    Routes.SETTINGS -> "Settings"
    Routes.REVIEW -> "Review"
    Routes.ADD -> "Add"
    else -> "Back"
}
