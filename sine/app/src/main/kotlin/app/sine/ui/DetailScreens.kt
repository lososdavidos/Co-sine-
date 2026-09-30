package app.sine.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.sine.core.download.PinnedBudget
import app.sine.core.model.DownloadNode
import app.sine.core.model.Track
import app.sine.core.play.PlaySource
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.download.Downloads
import app.sine.graph
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(session: AccountSession, id: String, nav: NavController) {
    val context = LocalContext.current
    val load = rememberLoad(session.account.id, id) { session.artist(id) }
    LoadContent(load) { detail, fromDownloads ->
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Header(
                    art = session.artwork(detail.artist.coverArt),
                    title = detail.artist.name,
                    subtitle = detail.artist.subtitle(),
                ) {
                    if (!fromDownloads) OutlinedButton(onClick = {
                        startDownload(context, session, DownloadNode.OfArtist(detail.artist.id), null, nav)
                    }) { Text("Download all") }
                }
            }
            itemsIndexed(detail.albums, key = { _, a -> a.id }) { _, album -> AlbumRow(session, album, nav) }
        }
    }
}

@Composable
fun AlbumScreen(session: AccountSession, id: String, nav: NavController) {
    val load = rememberLoad(session.account.id, id) { session.album(id) }
    LoadContent(load) { detail, fromDownloads ->
        TrackListScreen(
            session = session,
            title = detail.album.name,
            subtitle = detail.album.subtitle(),
            coverArt = detail.album.coverArt,
            tracks = detail.tracks,
            node = if (fromDownloads) null else DownloadNode.OfAlbum(detail.album.id),
            showTrackArtist = false,
            nav = nav,
            onTitleClick = detail.album.artistId?.let { artistId -> { nav.navigate(Routes.artist(artistId)) } },
        )
    }
}

@Composable
fun PlaylistScreen(session: AccountSession, id: String, nav: NavController) {
    val load = rememberLoad(session.account.id, id) {
        session.fetch(offline = { throw app.sine.data.NotAvailableOffline() }, remote = { it.playlist(id) })
    }
    LoadContent(load) { detail, _ ->
        if (detail.tracks.isEmpty()) {
            Message(Voice.EMPTY_PLAYLIST)
            return@LoadContent
        }
        TrackListScreen(
            session = session,
            title = detail.playlist.name,
            subtitle = detail.playlist.subtitle(),
            coverArt = detail.playlist.coverArt,
            tracks = detail.tracks,
            node = DownloadNode.OfPlaylist(detail.playlist.id),
            showTrackArtist = true,
            nav = nav,
        )
    }
}

/**
 * A release or playlist. Long-press selects rows (the whole row changes, §6.5)
 * and swaps in an action row; tapping while a selection exists extends it.
 */
@Composable
private fun TrackListScreen(
    session: AccountSession,
    title: String,
    subtitle: String,
    coverArt: String?,
    tracks: List<Track>,
    node: DownloadNode?,
    showTrackArtist: Boolean,
    nav: NavController,
    onTitleClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val player = context.graph.player
    val scope = rememberCoroutineScope()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    var selected by remember(tracks) { mutableStateOf(setOf<String>()) }
    val downloadedHere = tracks.count { it.id in downloads }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            val chosen = tracks.filter { it.id in selected }
            SelectionBar(
                count = selected.size,
                onPlay = { player.play(session, chosen); selected = emptySet() },
                onDownload = {
                    chosen.filterNot { it.id in downloads }.forEach {
                        startDownload(context, session, DownloadNode.OfTrack(it.id), chosen, nav, quiet = true)
                    }
                    Toast.makeText(context, Voice.downloadQueued(chosen.size), Toast.LENGTH_SHORT).show()
                    selected = emptySet()
                },
                onRemove = {
                    scope.launch { Downloads.remove(context, session, chosen.map { it.id }) }
                    selected = emptySet()
                },
                onClear = { selected = emptySet() },
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Header(session.artwork(coverArt), title, subtitle, onTitleClick) {
                    OutlinedButton(onClick = { player.play(session, tracks) }) { Text("Play") }
                    OutlinedButton(onClick = { player.play(session, tracks, shuffle = true) }) { Text("Shuffle") }
                    if (node != null && downloadedHere < tracks.size) {
                        OutlinedButton(onClick = { startDownload(context, session, node, tracks, nav) }) { Text("Download") }
                    }
                    if (downloadedHere > 0) {
                        OutlinedButton(onClick = {
                            scope.launch { Downloads.remove(context, session, tracks.map { it.id }) }
                        }) { Text("Remove download") }
                    }
                }
            }
            itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, track ->
                val isDownloaded = track.id in downloads
                val playable = session.playSource(track) != PlaySource.Unavailable
                val number = track.trackNumber?.let { "$it. " }.takeUnless { showTrackArtist } ?: ""
                ItemRow(
                    title = number + track.title,
                    subtitle = if (showTrackArtist) track.artist else null,
                    art = null,
                    showArt = false,
                    // A mark, not a highlight: downloaded is the common case (§6.8).
                    trailing = (if (isDownloaded) "↓ " else "") + track.durationText(),
                    selected = track.id in selected,
                    dimmed = !playable,
                    onLongClick = { selected = selected.toggle(track.id) },
                ) {
                    when {
                        selected.isNotEmpty() -> selected = selected.toggle(track.id)
                        playable -> player.play(session, tracks, i)
                        else -> Toast.makeText(context, Voice.TRACK_UNAVAILABLE, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

@Composable
private fun SelectionBar(
    count: Int,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = onPlay) { Text("Play") }
        TextButton(onClick = onDownload) { Text("Download") }
        TextButton(onClick = onRemove) { Text("Remove download") }
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun Header(
    art: Any?,
    title: String,
    subtitle: String,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Art(art, 160.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle.isNotBlank()) {
            if (onTitleClick != null) {
                TextButton(onClick = onTitleClick) { Text(subtitle) }
            } else {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
    }
}

/**
 * Queues a pinned download. The folder must have been chosen; the budget is
 * soft and only warns (Q33); on a metered network the download waits unless
 * the account allows mobile data.
 */
fun startDownload(
    context: Context,
    session: AccountSession,
    node: DownloadNode,
    tracks: List<Track>?,
    nav: NavController,
    quiet: Boolean = false,
) {
    val graph = context.graph
    val settings = graph.settings.state.value
    if (settings.downloadTreeUri == null) {
        Toast.makeText(context, Voice.CHOOSE_DOWNLOAD_FOLDER, Toast.LENGTH_LONG).show()
        nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
        return
    }
    val accountSettings = settings.forAccount(session.account.id)
    if (!quiet && tracks != null) {
        val adding = tracks.filterNot { it.id in session.downloads }.sumOf { it.sizeBytes ?: 0L }
        val check = PinnedBudget.check(session.downloads.totalBytes, adding, accountSettings.pinnedBudgetBytes)
        if (check is PinnedBudget.Check.Over) {
            Toast.makeText(context, Voice.overBudget(check.byBytes), Toast.LENGTH_LONG).show()
        }
    }
    Downloads.enqueue(context, session, node, accountSettings.downloadOnMetered)
    if (quiet) return
    val waiting = graph.network.state.value.metered && !accountSettings.downloadOnMetered
    val text = if (waiting) "Queued. Waiting for an unmetered connection." else "Download queued."
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
