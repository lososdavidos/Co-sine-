@file:OptIn(ExperimentalMaterial3Api::class)

package app.sine.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.LocalSayName
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseTheme
import app.sine.ui.phase.Signal
import kotlinx.coroutines.delay

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val NOW_PLAYING = "now-playing"
    const val SETTINGS = "settings"
    const val ADD_ACCOUNT = "add-account"
    const val REVIEW = "review"
    const val DOWNLOADED = "downloaded"
    private const val ARTIST = "artist/"
    private const val ALBUM = "album/"
    private const val PLAYLIST = "playlist/"
    private const val ADD = "add?text="
    private const val CORRECT = "review/"

    fun artist(id: String) = ARTIST + Uri.encode(id)
    fun album(id: String) = ALBUM + Uri.encode(id)
    fun playlist(id: String) = PLAYLIST + Uri.encode(id)
    fun add(text: String = "") = ADD + Uri.encode(text)
    fun correct(track: String) = CORRECT + Uri.encode(track)

    /** What a route of the kind [prefix] names, decoded; null for a route of another kind. */
    private fun arg(route: String, prefix: String): String? =
        if (route.startsWith(prefix)) Uri.decode(route.removePrefix(prefix)) else null

    fun artistOf(route: String) = arg(route, ARTIST)
    fun albumOf(route: String) = arg(route, ALBUM)
    fun playlistOf(route: String) = arg(route, PLAYLIST)
    fun addOf(route: String) = arg(route, ADD)
    fun correctionOf(route: String) = arg(route, CORRECT)
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

// Three destinations (§6.2), icons in the shelf, the one you're in named. Add is a Library action, Jam lives in now-playing.
private val destinations = listOf(
    Destination(Routes.HOME, "Home", PhaseIcons.Home),
    Destination(Routes.SEARCH, "Search", PhaseIcons.Search),
    Destination(Routes.LIBRARY, "Library", PhaseIcons.Library),
)

@Composable
private fun MainScaffold(session: AccountSession) {
    val graph = LocalContext.current.graph
    val pages = rememberPages(destinations.map { it.route })
    val network by graph.network.state.collectAsStateWithLifecycle()
    val reachable by session.reachable.collectAsStateWithLifecycle()
    val offline = !network.connected || !reachable
    val account by session.accountFlow.collectAsStateWithLifecycle()
    val share by graph.incomingShare.collectAsStateWithLifecycle()
    val shelf = remember { ShelfController() }

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
            pages.navigate(Routes.add(text), singleTop = true)
        }
    }

    // A question belongs to the page that asked it.
    LaunchedEffect(pages.top.id) { shelf.question = null }
    // Sideways goes nowhere while something is selected or asked (§6.16).
    SideEffect { pages.swipes = shelf.selection == null && shelf.question == null }
    // Losing the server is said once, in the shelf; the Offline mark stays in the top bar.
    LaunchedEffect(offline) {
        if (offline) say(Voice.serverUnreachable(session.account.isCosine), Signal.Server)
    }

    // An icon button held says its name in the shelf.
    CompositionLocalProvider(LocalShelf provides shelf, LocalPages provides pages, LocalSayName provides { say(it) }) {
        PageHost(
            pages,
            shelf = {
                Shelf(
                    destinations, pages.here,
                    onNavigate = pages::go,
                    onOpenPlayer = pages::openPlayer,
                    onPlayerDrag = pages::dragPlayer,
                    onPlayerRelease = pages::release,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            },
        ) { page ->
            // The account control (§6.11): every page except now playing, and Settings itself.
            val chrome = Chrome(
                offline = offline,
                onRetry = { session.forceOnlineRetry() },
                onAccount = if (page.route == Routes.SETTINGS) null else ({ pages.navigate(Routes.SETTINGS, singleTop = true) }),
            )
            CompositionLocalProvider(LocalChrome provides chrome) {
                Box(Modifier.fillMaxSize().background(PhaseColor.Ground)) {
                    Screen(session, pages, page)
                    if (page.route !in pages.destinations && !page.isSheet && page.route != Routes.ADD_ACCOUNT) {
                        DetailBar(
                            back = backLabel(pages.below(page)?.route),
                            cancel = page.form != null,
                            onBack = { pages.popBackStack() },
                            showAccount = page.route != Routes.SETTINGS,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }
}

/** The screen a page shows. */
@Composable
private fun Screen(session: AccountSession, pages: Pages, page: Page) {
    page.form?.let { return it() }
    val route = page.route
    when (route) {
        Routes.HOME -> HomeScreen(session, pages)
        Routes.SEARCH -> SearchScreen(session, pages)
        Routes.LIBRARY -> LibraryScreen(session, pages)
        Routes.NOW_PLAYING -> NowPlayingScreen(session, pages, onClose = { pages.popBackStack() })
        Routes.SETTINGS -> SettingsScreen(session, pages)
        Routes.REVIEW -> ReviewScreen(session, onOpen = { pages.navigate(Routes.correct(it)) })
        Routes.DOWNLOADED -> DownloadedScreen(session, pages)
        Routes.ADD_ACCOUNT -> AddAccountScreen(onDone = { pages.popBackStack() }, onCancel = { pages.popBackStack() })
        else -> {
            Routes.artistOf(route)?.let { return ArtistScreen(session, it, pages) }
            Routes.albumOf(route)?.let { return AlbumScreen(session, it, pages) }
            Routes.playlistOf(route)?.let { return PlaylistScreen(session, it, pages) }
            Routes.addOf(route)?.let { return AddScreen(session, it, onCorrect = { track -> pages.navigate(Routes.correct(track)) }) }
            Routes.correctionOf(route)?.let { return CorrectionScreen(session, it, onDone = { pages.popBackStack() }) }
        }
    }
}

/** Where the back button goes, said when it's held and to a screen reader; a page about one thing says Back. */
private fun backLabel(route: String?) = when {
    route == Routes.HOME -> "Home"
    route == Routes.SEARCH -> "Search"
    route == Routes.LIBRARY -> "Library"
    route == Routes.SETTINGS -> "Settings"
    route == Routes.REVIEW -> "Review"
    route != null && Routes.addOf(route) != null -> "Add"
    else -> "Back"
}
