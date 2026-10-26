package app.sine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Playlist
import app.sine.core.model.Track
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.ui.phase.OnPhoneMark
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.PlayingBars
import app.sine.ui.phase.rowInk
import app.sine.ui.phase.rowSurface
import coil3.compose.AsyncImage
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/*
 * Shared pieces of Sine's screens, in Phase (§6): loading and its messages,
 * artwork, and the row every list is made of.
 */

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T, val fromDownloads: Boolean) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * A screen's data, and the pull that asks for it again. A refresh keeps what
 * is on screen until the answer arrives, then shows exactly what a fresh
 * visit would: the server's answer, or downloads if it can't be reached.
 */
@Stable
class Loader<T> internal constructor() {
    var load: Load<T> by mutableStateOf(Load.Loading)
        internal set
    var refreshing by mutableStateOf(false)
        internal set
    internal var pulls by mutableIntStateOf(0)
    // A pull asks the server once, even if the answer restarts the load.
    internal var askServer = false

    fun refresh() {
        refreshing = true
        askServer = true
        pulls++
    }
}

/**
 * Loads for [session], again whenever [keys] change or the list is pulled
 * down. A change to [refreshOn] (the mirror, after a sync) reloads in place,
 * keeping what's shown until the new answer is there.
 */
@Composable
fun <T> rememberLoad(session: AccountSession, vararg keys: Any?, refreshOn: Any? = null, block: suspend () -> Sourced<T>): Loader<T> {
    val loader = remember(session, *keys) { Loader<T>() }
    LaunchedEffect(loader, loader.pulls, refreshOn) {
        // Asked for by hand: ask the server even if it failed a moment ago,
        // and catch the mirror up.
        if (loader.askServer) {
            loader.askServer = false
            session.refresh()
        }
        loader.load = try {
            val s = block()
            Load.Ready(s.value, s.fromDownloads)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Load.Failed(Voice.serverUnreachable(session.account.isCosine))
        } catch (e: Exception) {
            Load.Failed(e.message ?: e.javaClass.simpleName)
        }
        loader.refreshing = false
    }
    return loader
}

/** The loaded content, or why there is none; either way, pulling down refreshes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadContent(loader: Loader<T>, content: @Composable (T, Boolean) -> Unit) {
    PullToRefreshBox(isRefreshing = loader.refreshing, onRefresh = loader::refresh, modifier = Modifier.fillMaxSize()) {
        when (val load = loader.load) {
            Load.Loading -> ListMessage("Loading…")
            is Load.Failed -> ListMessage(load.message)
            is Load.Ready -> content(load.value, load.fromDownloads)
        }
    }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = PhaseColor.Text2, style = PhaseType.Body, textAlign = TextAlign.Center)
    }
}

/** A [Message] in place of a list. It scrolls, so it can be pulled down to refresh like the list. */
@Composable
fun ListMessage(text: String) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Message(text, Modifier.fillParentMaxSize()) }
    }
}

/**
 * Artwork. A cover is square, nearly: a sleeve. An artist's picture is
 * round, because circles are people (§6.9). With no artwork, a raised
 * square, never a generated colour.
 */
@Composable
fun Art(model: Any?, size: Dp, modifier: Modifier = Modifier, round: Boolean = false) {
    val shape = if (round) CircleShape else PhaseShape.Square
    Box(modifier.size(size).clip(shape).background(PhaseColor.Raised)) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The row every list is made of. Quiet by default: a name, a second line,
 * the length in mono. Something true of the row adds one mark (playing, on
 * this phone, a tag). Selected, the whole row is Sine with black ink (§6.8).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    title: String,
    subtitle: String?,
    art: Any?,
    modifier: Modifier = Modifier,
    showArt: Boolean = true,
    roundArt: Boolean = false,
    trailing: String? = null,
    selected: Boolean = false,
    dimmed: Boolean = false,
    playing: Boolean = false,
    onPhone: Boolean = false,
    label: String? = null,
    leading: (@Composable () -> Unit)? = null,
    end: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .rowSurface(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = if (showArt) 64.dp else 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        if (showArt) Art(art, 44.dp, round = roundArt)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, color = rowInk(selected, dimmed),
                style = PhaseType.Name.copy(fontWeight = if (playing || selected) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, color = rowInk(selected, dimmed, secondary = true),
                    style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (label != null) PhaseLabel(label, color = rowInk(selected, dimmed, secondary = true))
        if (playing && leading == null) PlayingBars(color = if (selected) PhaseColor.Ink else PhaseColor.Sine)
        if (onPhone) OnPhoneMark()
        if (trailing != null) {
            Text(trailing, color = rowInk(selected, dimmed, secondary = true), style = PhaseType.Data, maxLines = 1)
        }
        end?.invoke(this)
    }
}

fun Track.durationText() = Voice.formatDuration(durationSec)
fun Artist.subtitle() = if (albumCount == 1) "1 album" else "$albumCount albums"
fun Album.subtitle() = listOfNotNull(artist.ifBlank { null }, year?.toString()).joinToString(" · ")
fun Playlist.subtitle(): String {
    val count = Voice.songs(trackCount)
    val owner = owner
    return when {
        // A smart playlist says what its rule picks.
        readonly && !comment.isNullOrBlank() -> "$comment · $count"
        !mine && owner != null -> Voice.sharedBy(owner) + " · $count"
        sharedWith.isNotEmpty() -> Voice.sharedWith(sharedWith) + " · $count"
        else -> count
    }
}
