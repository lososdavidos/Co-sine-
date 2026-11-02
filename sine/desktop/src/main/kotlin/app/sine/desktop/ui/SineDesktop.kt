package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.sine.core.session.AccountSession
import app.sine.desktop.DesktopGraph
import app.sine.ui.phase.Ambient
import app.sine.ui.phase.LocalArtClient
import app.sine.ui.phase.LocalSayName
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseTheme
import app.sine.ui.phase.Signal
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.delay

/** What every page needs: the app, and the account it shows. */
val LocalGraph = staticCompositionLocalOf<DesktopGraph> { error("No graph") }

/** A line of status at the foot of the page, like the phone's shelf saying it: "Added to the queue." */
class Say {
    var line by mutableStateOf<Pair<String, Signal>?>(null)
        private set
    private var n = 0
    var shown by mutableStateOf(0)
        private set

    operator fun invoke(text: String, signal: Signal = Signal.None) {
        line = text to signal
        shown = ++n
    }

    fun clear() {
        line = null
    }
}

val LocalSay = staticCompositionLocalOf { Say() }

/** Sine on the desktop: sign in, or the app. */
@Composable
fun SineDesktop(graph: DesktopGraph) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }.build()
    }
    val say = remember { Say() }
    LaunchedEffect(say.shown) {
        if (say.line == null) return@LaunchedEffect
        delay(4_000)
        say.clear()
    }
    val settings by graph.settings.state.collectAsState()
    LaunchedEffect(settings.fixedColour) { Ambient.fixed = Ambient.fixed(settings.fixedColour) }
    CompositionLocalProvider(
        LocalGraph provides graph,
        LocalSay provides say,
        LocalArtClient provides graph.http,
        LocalSayName provides { say(it) },
    ) {
        PhaseTheme {
            Box(Modifier.fillMaxSize().background(PhaseColor.Ground)) {
                val session by graph.activeSession.collectAsState()
                val s: AccountSession? = session
                if (s == null) SignIn(Modifier.fillMaxSize()) else Shell(s)
            }
        }
    }
}
