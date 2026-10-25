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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.sine.core.cosine.CosineException
import app.sine.core.download.PinnedBudget
import app.sine.core.model.ArtistEntry
import app.sine.core.model.ArtistPage
import app.sine.core.model.DownloadNode
import app.sine.core.model.PlaylistEdits
import app.sine.core.model.Track
import app.sine.core.play.PlaySource
import app.sine.core.server.Capability
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.download.Downloads
import app.sine.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * An artist and everything of theirs, in one list (§6.9). A single shows as
 * the song it is: tapping plays it and the singles after it, and a long
 * press selects, as on a release.
 */
@Composable
fun ArtistScreen(session: AccountSession, id: String, nav: NavController) {
    val context = LocalContext.current
    val player = context.graph.player
    val scope = rememberCoroutineScope()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val load = rememberLoad(session, id, refreshOn = mirrored) {
        session.artist(id).let { s ->
            Sourced(s.value to ArtistPage.entries(s.value.albums) { session.knownTracks(it, s.fromDownloads) }, s.fromDownloads)
        }
    }
    LoadContent(load) { (detail, entries), fromDownloads ->
        val singles = entries.filterIsInstance<ArtistEntry.Single>().map { it.track }
        var selected by remember(singles) { mutableStateOf(setOf<String>()) }
        var removing by remember { mutableStateOf<List<Track>?>(null) }
        var adding by remember { mutableStateOf<List<Track>?>(null) }
        Column(Modifier.fillMaxSize()) {
            if (selected.isNotEmpty()) {
                val chosen = singles.filter { it.id in selected }
                TrackSelectionBar(
                    session, chosen, nav,
                    onRemoveFromLibrary = { removing = chosen }.takeIf { session.can(Capability.REMOVE) },
                    onAddToPlaylist = { adding = chosen },
                ) { selected = emptySet() }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Header(
                        art = session.artwork(detail.artist.coverArt),
                        title = detail.artist.name,
                        subtitle = detail.artist.subtitle(),
                        roundArt = true,
                    ) {
                        if (!fromDownloads) OutlinedButton(onClick = {
                            startDownload(context, session, DownloadNode.OfArtist(detail.artist.id), null, nav)
                        }) { Text("Download all") }
                    }
                }
                items(entries, key = { it.album.id }) { entry ->
                    when (entry) {
                        is ArtistEntry.Release -> AlbumRow(session, entry.album, nav)
                        is ArtistEntry.Single -> {
                            val track = entry.track
                            val playable = session.playSource(track) != PlaySource.Unavailable
                            ItemRow(
                                title = track.title,
                                subtitle = listOfNotNull("Single", entry.album.year?.toString()).joinToString(" · "),
                                art = session.artwork(entry.album.coverArt, 150),
                                trailing = (if (track.id in downloads) "↓ " else "") + track.durationText(),
                                selected = track.id in selected,
                                dimmed = !playable,
                                onLongClick = { selected = selected.toggle(track.id) },
                            ) {
                                when {
                                    selected.isNotEmpty() -> selected = selected.toggle(track.id)
                                    playable -> player.play(session, singles, singles.indexOf(track))
                                    else -> Toast.makeText(context, Voice.TRACK_UNAVAILABLE, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
        RemoveConfirmation(session, removing, scope, onDismiss = { removing = null }) { selected = emptySet() }
        adding?.let { chosen ->
            AddToPlaylistDialog(session, chosen, onDismiss = { adding = null }) { selected = emptySet() }
        }
    }
}

@Composable
fun AlbumScreen(session: AccountSession, id: String, nav: NavController) {
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val load = rememberLoad(session, id, refreshOn = mirrored) { session.album(id) }
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
            removable = true,
            onTitleClick = detail.album.artistId?.let { artistId -> { nav.navigate(Routes.artist(artistId)) } },
        )
    }
}

/**
 * A release or playlist. Long-press selects rows (the whole row changes, §6.5)
 * and swaps in an action row; tapping while a selection exists extends it.
 * A playlist of your own adds taking songs out of it ([onRemoveFromPlaylist],
 * with their positions as listed) and, while [onMove] is set, moving them.
 */
@Composable
internal fun TrackListScreen(
    session: AccountSession,
    title: String,
    subtitle: String,
    coverArt: String?,
    tracks: List<Track>,
    node: DownloadNode?,
    showTrackArtist: Boolean,
    nav: NavController,
    onTitleClick: (() -> Unit)? = null,
    // A release's songs can be taken out of the library; a playlist's are
    // the playlist's, where "remove" would mean something else.
    removable: Boolean = false,
    empty: String? = null,
    onRemoveFromPlaylist: ((List<Int>) -> Unit)? = null,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    extraActions: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val player = context.graph.player
    val scope = rememberCoroutineScope()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    var selected by remember(tracks) { mutableStateOf(setOf<String>()) }
    var removing by remember { mutableStateOf<List<Track>?>(null) }
    var adding by remember { mutableStateOf<List<Track>?>(null) }
    val canRemove = removable && session.can(Capability.REMOVE)
    val downloadedHere = tracks.count { it.id in downloads }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty() && onMove == null) {
            val chosen = tracks.filter { it.id in selected }
            TrackSelectionBar(
                session, chosen, nav,
                onRemoveFromLibrary = if (canRemove) ({ removing = chosen }) else null,
                onAddToPlaylist = { adding = chosen },
                onRemoveFromPlaylist = onRemoveFromPlaylist?.let { remove ->
                    { remove(PlaylistEdits.positionsOf(tracks, selected)); selected = emptySet() }
                },
            ) { selected = emptySet() }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Header(session.artwork(coverArt), title, subtitle, onTitleClick) {
                    if (tracks.isNotEmpty() && onMove == null) {
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
                    extraActions()
                    // Last in the row, away from Play (§6.18's rule for destructive actions).
                    if (canRemove) TextButton(onClick = { removing = tracks }) { Text("Remove from library") }
                }
            }
            if (tracks.isEmpty() && empty != null) item {
                Text(empty, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, track ->
                if (onMove != null) {
                    MoveRow(track, i, tracks.size, onMove)
                    return@itemsIndexed
                }
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
    RemoveConfirmation(session, removing, scope, onDismiss = { removing = null }) { removed ->
        selected = emptySet()
        if (removed.size == tracks.size) nav.popBackStack() // nothing left here
    }
    adding?.let { chosen ->
        AddToPlaylistDialog(session, chosen, onDismiss = { adding = null }) { selected = emptySet() }
    }
}

/** A song while a playlist is being put in order: moved one place at a time. */
@Composable
private fun MoveRow(track: Track, index: Int, count: Int, onMove: (Int, Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < count - 1) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
        }
    }
}

internal fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/** The actions on selected tracks (§6.5), the whole row changed to show them. */
@Composable
private fun TrackSelectionBar(
    session: AccountSession,
    chosen: List<Track>,
    nav: NavController,
    onRemoveFromLibrary: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val player = context.graph.player
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${chosen.size} selected", Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = { player.play(session, chosen); onClear() }) { Text("Play") }
        TextButton(onClick = {
            chosen.filterNot { it.id in session.downloads }.forEach {
                startDownload(context, session, DownloadNode.OfTrack(it.id), chosen, nav, quiet = true)
            }
            Toast.makeText(context, Voice.downloadQueued(chosen.size), Toast.LENGTH_SHORT).show()
            onClear()
        }) { Text("Download") }
        TextButton(onClick = {
            scope.launch { Downloads.remove(context, session, chosen.map { it.id }) }
            onClear()
        }) { Text("Remove download") }
        if (onAddToPlaylist != null && session.canEditPlaylists) {
            TextButton(onClick = onAddToPlaylist) { Text("Add to playlist") }
        }
        TextButton(onClick = onClear) { Text("Clear") }
        // Last, apart from Play (§6.18).
        if (onRemoveFromPlaylist != null) TextButton(onClick = onRemoveFromPlaylist) { Text("Remove from playlist") }
        if (onRemoveFromLibrary != null) TextButton(onClick = onRemoveFromLibrary) { Text("Remove from library") }
    }
}

/**
 * Asks before taking songs out of the library, then does it (§4.1): the
 * server first, then their downloads here. [scope] is the screen's, so the
 * work outlives the dialog.
 */
@Composable
private fun RemoveConfirmation(
    session: AccountSession,
    tracks: List<Track>?,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onRemoved: (List<Track>) -> Unit,
) {
    if (tracks.isNullOrEmpty()) return
    val context = LocalContext.current.applicationContext
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Voice.removeQuestion(tracks.size)) },
        text = { Text(Voice.removeExplained(tracks.size)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                scope.launch {
                    val ids = tracks.map { it.id }
                    val said = try {
                        val n = session.remove(ids)
                        Downloads.remove(context, session, ids)
                        onRemoved(tracks)
                        Voice.removed(n)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CosineException) {
                        e.message ?: "Cosine refused that."
                    } catch (e: IOException) {
                        Voice.serverUnreachable(isCosine = true)
                    }
                    withContext(Dispatchers.Main) { Toast.makeText(context, said, Toast.LENGTH_SHORT).show() }
                }
            }) { Text(Voice.removeAction(tracks.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun Header(
    art: Any?,
    title: String,
    subtitle: String,
    onTitleClick: (() -> Unit)? = null,
    roundArt: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Art(art, 160.dp, round = roundArt)
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
