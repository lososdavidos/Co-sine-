package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.session.AccountSession
import app.sine.core.session.NotAvailableOffline
import app.sine.core.text.Voice
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/** Something asked of the server or the library: on its way, here, or not. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Loads [block] again whenever [keys] change. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Load<T> {
    var state by remember { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys) {
        state = Load.Loading
        state = try {
            Load.Done(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotAvailableOffline) {
            Load.Failed(e.message ?: "Not available offline.")
        } catch (e: IOException) {
            Load.Failed(Voice.serverUnreachable(isCosine = true))
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Something went wrong.")
        }
    }
    return state
}

/** What a loaded page shows while it waits, or when it can't. */
@Composable
fun <T> Loaded(load: Load<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> Box(modifier.fillMaxWidth().padding(32.dp)) { StatusLine("Loading…", signal = Signal.Server, color = PhaseColor.Text2) }
        is Load.Failed -> Box(modifier.fillMaxWidth().padding(32.dp)) { StatusLine(load.message, signal = Signal.Fault) }
        is Load.Done -> content(load.value)
    }
}

/** A cover: square for things you read, round for people. Grey until it comes, or when there's none. */
@Composable
fun Cover(session: AccountSession, coverArt: String?, size: Dp, modifier: Modifier = Modifier, round: Boolean = false, px: Int = 300) {
    val shape = if (round) CircleShape else PhaseShape.Square
    Box(modifier.size(size).clip(shape).background(PhaseColor.Raised)) {
        val model = remember(coverArt, px) { session.artwork(coverArt, px) }
        if (model != null) {
            AsyncImage(model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/** The top of a page: what it is, in mono, and its name, large. */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier, label: String? = null, end: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (label != null) PhaseLabel(label)
            Text(title, color = PhaseColor.Text1, style = PhaseType.display(44.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        end()
    }
}

/** A length as the phone shows it: 3:05, or 1 h 14 min for an album. */
fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun length(totalSec: Int): String {
    val min = totalSec / 60
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** Runs [block] in the background; a failure is dropped, as a tap that didn't take. */
fun CoroutineScope.launchCatching(block: suspend () -> Unit) = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Nothing to say: the page shows what is.
    }
}
