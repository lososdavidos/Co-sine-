package app.sine.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.core.cosine.CosineException
import app.sine.core.download.PinnedBudget
import app.sine.core.home.TileKind
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
import app.sine.ui.phase.Ambience
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.People
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PlayingBars
import app.sine.ui.phase.RowNumber
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.Tag
import app.sine.ui.phase.numberColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * An artist and everything of theirs, in one list (§6.9). A single shows as
 * the song it is: tapping plays it and the singles after it, and a long
 * press selects, as on a release.
 */
@Composable
fun ArtistScreen(session: AccountSession, id: String, nav: Pages) {
    val context = LocalContext.current
    val player = context.graph.player
    val scope = rememberCoroutineScope()
    val shelf = LocalShelf.current
    val playing = playingId()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val liking by session.liking.collectAsStateWithLifecycle()
    val like = rememberLike(session)
    val load = rememberLoad(session, id, refreshOn = mirrored) {
        session.artist(id).let { s ->
            Sourced(s.value to ArtistPage.entries(s.value.albums) { session.knownTracks(it, s.fromDownloads) }, s.fromDownloads)
        }
    }
    LoadContent(load) { (detail, entries), fromDownloads ->
        val singles = entries.filterIsInstance<ArtistEntry.Single>().map { it.track }
        var selected by remember(singles) { mutableStateOf(setOf<String>()) }
        ShowSelection(
            if (selected.isEmpty()) null else {
                val chosen = singles.filter { it.id in selected }
                trackSelection(
                    context, session, chosen, nav, scope, shelf,
                    canRemoveFromLibrary = session.can(Capability.REMOVE),
                    onAddToPlaylist = { nav.form { AddToPlaylistForm(session, chosen) } },
                ) { selected = emptySet() }
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item {
                DetailHeader(
                    art = session.artwork(detail.artist.coverArt),
                    title = detail.artist.name,
                    round = true,
                    meta = listOf(detail.artist.subtitle()),
                    quiet = { PinToHome(session, TileKind.ARTIST, detail.artist.id, detail.artist.name) },
                ) {
                    if (!fromDownloads) Pill(
                        null,
                        { startDownload(context, session, DownloadNode.OfArtist(detail.artist.id), null, nav) },
                        icon = PhaseIcons.OnPhone, contentDescription = "Download all",
                    )
                }
            }
            item { SectionLabel("Releases and singles", end = "Oldest first") }
            items(entries, key = { it.album.id }) { entry ->
                when (entry) {
                    is ArtistEntry.Release -> {
                        val a = entry.album
                        ItemRow(
                            a.name,
                            listOfNotNull(a.year?.toString(), a.trackCount.takeIf { it > 0 }?.let { Voice.songs(it) }).joinToString(" · "),
                            session.artwork(a.coverArt, 150),
                            end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
                        ) { nav.navigate(Routes.album(a.id)) }
                    }
                    is ArtistEntry.Single -> {
                        val track = entry.track
                        val playable = session.playSource(track) != PlaySource.Unavailable
                        val on = track.id in selected
                        ItemRow(
                            title = track.title,
                            subtitle = listOfNotNull("Single", entry.album.year?.toString()).joinToString(" · "),
                            art = session.artwork(entry.album.coverArt, 150),
                            trailing = track.durationText(),
                            onPhone = track.id in downloads,
                            playing = track.id == playing,
                            selected = on,
                            dimmed = !playable,
                            liked = liking[track.id] ?: track.liked,
                            onLike = { like(track.id, track.title, it) },
                            onLongClick = { selected = selected.toggle(track.id) },
                        ) {
                            when {
                                selected.isNotEmpty() -> selected = selected.toggle(track.id)
                                playable -> player.play(session, singles, singles.indexOf(track))
                                else -> say(Voice.TRACK_UNAVAILABLE)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(session: AccountSession, id: String, nav: Pages) {
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val load = rememberLoad(session, id, refreshOn = mirrored) { session.album(id) }
    LoadContent(load) { detail, fromDownloads ->
        TrackListScreen(
            session = session,
            title = detail.album.name,
            kind = "Album",
            coverArt = detail.album.coverArt,
            tracks = detail.tracks,
            node = if (fromDownloads) null else DownloadNode.OfAlbum(detail.album.id),
            showTrackArtist = false,
            nav = nav,
            byline = detail.album.artist.ifBlank { null },
            meta = listOfNotNull(detail.album.year?.toString()),
            removable = true,
            onByline = detail.album.artistId?.let { artistId -> { nav.navigate(Routes.artist(artistId)) } },
            quietActions = { PinToHome(session, TileKind.ALBUM, detail.album.id, detail.album.name) },
        )
    }
}

/**
 * A release or playlist. Long-press selects rows (the whole row changes, §6.5)
 * and the shelf turns into the actions for them; tapping while a selection
 * exists extends it. A playlist of your own adds taking songs out of it
 * ([onRemoveFromPlaylist], with their positions as listed) and, while
 * [onMove] is set, moving them.
 */
@Composable
internal fun TrackListScreen(
    session: AccountSession,
    title: String,
    kind: String,
    coverArt: String?,
    tracks: List<Track>,
    node: DownloadNode?,
    showTrackArtist: Boolean,
    nav: Pages,
    byline: String? = null,
    onByline: (() -> Unit)? = null,
    meta: List<String> = emptyList(),
    people: List<String> = emptyList(),
    peopleLine: String? = null,
    status: String? = null,
    // A release's songs can be taken out of the library; a playlist's are
    // the playlist's, where "remove" would mean something else.
    removable: Boolean = false,
    empty: String? = null,
    onRemoveFromPlaylist: ((List<Int>) -> Unit)? = null,
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    // What stands in for a cover it doesn't have: Liked songs' heart.
    mark: ImageVector? = null,
    // Taking songs out is called this here: Liked songs' is "Stop liking".
    removeLabel: String = "Remove from playlist",
    quietActions: @Composable RowScope.() -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val player = context.graph.player
    val scope = rememberCoroutineScope()
    val shelf = LocalShelf.current
    val playing = playingId()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val liking by session.liking.collectAsStateWithLifecycle()
    val like = rememberLike(session)
    var selected by remember(tracks) { mutableStateOf(setOf<String>()) }
    val canRemove = removable && session.can(Capability.REMOVE)
    val downloadedHere = tracks.count { it.id in downloads }
    val numbers = numberColumn(if (showTrackArtist) tracks.size else tracks.maxOfOrNull { it.trackNumber ?: 0 } ?: 0)

    ShowSelection(
        if (selected.isEmpty() || onMove != null) null else {
            val chosen = tracks.filter { it.id in selected }
            trackSelection(
                context, session, chosen, nav, scope, shelf,
                canRemoveFromLibrary = canRemove,
                onAddToPlaylist = { nav.form { AddToPlaylistForm(session, chosen) } },
                onRemoveFromPlaylist = onRemoveFromPlaylist?.let { remove ->
                    { remove(PlaylistEdits.positionsOf(tracks, selected)); selected = emptySet() }
                },
                removeLabel = removeLabel,
                onRemovedFromLibrary = { removed -> if (removed.size == tracks.size) nav.popBackStack() },
            ) { selected = emptySet() }
        },
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            val minutes = tracks.sumOf { it.durationSec } / 60
            DetailHeader(
                art = session.artwork(coverArt),
                title = title,
                kind = kind,
                mark = mark,
                meta = meta + listOfNotNull(
                    Voice.songs(tracks.size) + if (minutes > 0) " · ${durationWords(minutes)}" else "",
                    downloadedHere.takeIf { it > 0 }?.let { "$it on this phone" },
                ),
                byline = byline,
                onByline = onByline,
                extra = {
                    if (people.isNotEmpty() && peopleLine != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            People(people, size = 26.dp)
                            Text(peopleLine, color = PhaseColor.OnScrim, style = PhaseType.Small.copy(fontSize = 14.sp))
                        }
                    }
                    if (status != null) StatusLine(status, signal = Signal.Server, color = PhaseColor.OnScrim)
                },
                quiet = quietActions,
            ) {
                if (tracks.isNotEmpty() && onMove == null) {
                    Pill(null, { player.play(session, tracks) }, kind = PillKind.Primary, icon = PhaseIcons.Play, width = 80.dp, contentDescription = "Play")
                    Pill(null, { player.play(session, tracks, shuffle = true) }, icon = PhaseIcons.Shuffle, contentDescription = "Shuffle")
                    if (node != null && downloadedHere < tracks.size) {
                        // Part of it is here already: the icon, and how many more it gets.
                        val more = tracks.size - downloadedHere
                        Pill(
                            null, { startDownload(context, session, node, tracks, nav) },
                            icon = PhaseIcons.OnPhone,
                            count = if (downloadedHere > 0) "+$more" else null,
                            contentDescription = if (downloadedHere > 0) "Get $more more" else "Download",
                        )
                    }
                }
                extraActions()
            }
        }
        if (tracks.isEmpty() && empty != null) item {
            Text(empty, Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Body)
        }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, track ->
            if (onMove != null) {
                MoveRow(track, i, tracks.size, onMove)
                return@itemsIndexed
            }
            val playable = session.playSource(track) != PlaySource.Unavailable
            val on = track.id in selected
            val isPlaying = track.id == playing
            val number = if (showTrackArtist) i + 1 else track.trackNumber
            ItemRow(
                title = track.title,
                subtitle = if (showTrackArtist) track.artist else null,
                art = null,
                showArt = false,
                leading = {
                    if (isPlaying) PlayingBars(Modifier.width(numbers), color = if (on) PhaseColor.Ink else PhaseColor.Sine)
                    else if (number != null) RowNumber(number, numbers, color = if (on) PhaseColor.Ink else PhaseColor.Text3)
                    else Spacer(Modifier.width(numbers))
                },
                playing = isPlaying,
                // A mark, not a highlight: downloaded is the common case (§6.8).
                onPhone = track.id in downloads,
                trailing = track.durationText(),
                selected = on,
                dimmed = !playable,
                liked = liking[track.id] ?: track.liked,
                onLike = { like(track.id, track.title, it) },
                onLongClick = { selected = selected.toggle(track.id) },
            ) {
                when {
                    selected.isNotEmpty() -> selected = selected.toggle(track.id)
                    playable -> player.play(session, tracks, i)
                    else -> say(Voice.TRACK_UNAVAILABLE)
                }
            }
        }
        // Last on the page, apart from Play (§6.18's rule for destructive actions).
        if (canRemove && tracks.isNotEmpty() && onMove == null) item {
            Pill(
                "Remove from library",
                { askRemove(context, session, tracks, scope, shelf) { done -> nav.popBackStack(); done() } },
                Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                kind = PillKind.QuietFault, icon = PhaseIcons.Trash, height = 44.dp,
            )
        }
    }
}

private fun durationWords(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)} min" else "$minutes min"

/**
 * A page's head: its subject's ambience (§6.7), the cover or the person,
 * the name large, the facts in mono beside it, then what you can do.
 */
@Composable
internal fun DetailHeader(
    art: Any?,
    title: String,
    round: Boolean = false,
    kind: String? = null,
    mark: ImageVector? = null,
    meta: List<String> = emptyList(),
    byline: String? = null,
    onByline: (() -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
    quiet: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    Ambience(art, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TopSpace()
            if (round) {
                Art(art, 112.dp, round = true)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Art(art, 148.dp, mark = mark)
                    Column(Modifier.padding(bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (kind != null) Tag(kind)
                        for (m in meta) PhaseLabel(m, color = PhaseColor.OnScrim)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title, color = PhaseColor.Text1, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = PhaseType.display(if (title.length > 22) 40.sp else 52.sp),
                )
                if (round) for (m in meta) PhaseLabel(m, color = PhaseColor.OnScrim)
                if (byline != null) {
                    Text(
                        byline,
                        if (onByline == null) Modifier else Modifier
                            .clip(PhaseShape.Square)
                            .clickable(role = Role.Button, onClick = onByline)
                            .padding(vertical = 6.dp),
                        color = PhaseColor.OnScrim, style = PhaseType.Body.copy(fontSize = 17.sp),
                    )
                }
            }
            extra()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = quiet,
            )
        }
    }
}

/** A song while a playlist is being put in order: moved one place at a time. */
@Composable
private fun MoveRow(track: Track, index: Int, count: Int, onMove: (Int, Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Pill(null, { onMove(index, index - 1) }, kind = PillKind.Quiet, icon = PhaseIcons.Up, height = 44.dp, width = 48.dp, enabled = index > 0, contentDescription = "Move up")
        Pill(null, { onMove(index, index + 1) }, kind = PillKind.Quiet, icon = PhaseIcons.Down, height = 44.dp, width = 48.dp, enabled = index < count - 1, contentDescription = "Move down")
    }
}

internal fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

/** The actions on selected songs (§6.5): what the shelf offers while they're selected. */
internal fun trackSelection(
    context: Context,
    session: AccountSession,
    chosen: List<Track>,
    nav: Pages,
    scope: CoroutineScope,
    shelf: ShelfController,
    canRemoveFromLibrary: Boolean,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    removeLabel: String = "Remove from playlist",
    onRemovedFromLibrary: (List<Track>) -> Unit = {},
    onClear: () -> Unit,
): ShelfSelection {
    val player = context.graph.player
    val actions = buildList {
        add(ShelfAction("Play", icon = PhaseIcons.Play) { player.play(session, chosen); onClear() })
        add(ShelfAction("Add to queue", icon = PhaseIcons.QueueAdd) {
            val n = player.addToQueue(session, chosen)
            say(if (n == 0) Voice.TRACK_UNAVAILABLE else if (n == 1) "Added to the queue." else "$n songs added to the queue.")
            onClear()
        })
        add(ShelfAction("Download", icon = PhaseIcons.OnPhone) {
            chosen.filterNot { it.id in session.downloads }.forEach {
                startDownload(context, session, DownloadNode.OfTrack(it.id), chosen, nav, quiet = true)
            }
            say(Voice.downloadQueued(chosen.size))
            onClear()
        })
        if (chosen.any { it.id in session.downloads }) add(ShelfAction("Remove download", icon = PhaseIcons.OffPhone) {
            scope.launch { Downloads.remove(context, session, chosen.map { it.id }) }
            onClear()
        })
        if (onAddToPlaylist != null && session.canEditPlaylists) add(ShelfAction("Add to playlist", icon = PhaseIcons.PlaylistAdd, onClick = onAddToPlaylist))
        // Last, apart from Play (§6.18).
        if (onRemoveFromPlaylist != null) add(ShelfAction(removeLabel, PillKind.SecondaryFault, PhaseIcons.PlaylistRemove, onRemoveFromPlaylist))
        if (canRemoveFromLibrary) add(ShelfAction("Remove from library", PillKind.SecondaryFault, PhaseIcons.Trash) {
            askRemove(context, session, chosen, scope, shelf) { done -> onRemovedFromLibrary(chosen); done() }
            onClear()
        })
    }
    return ShelfSelection(if (chosen.size == 1) "1 selected" else "${chosen.size} selected", actions, onClear)
}

/**
 * Asks, in the shelf, before taking songs out of the library, then does it
 * (§4.1): the server first, then their downloads here. [scope] is the
 * screen's, so the work outlives the question.
 */
private fun askRemove(
    context: Context,
    session: AccountSession,
    tracks: List<Track>,
    scope: CoroutineScope,
    shelf: ShelfController,
    onRemoved: (done: () -> Unit) -> Unit,
) {
    val app = context.applicationContext
    shelf.ask(
        ShelfQuestion(
            Voice.removeQuestion(tracks.size),
            Voice.removeExplained(tracks.size),
            listOf(
                ShelfAction("Keep") {},
                ShelfAction(Voice.removeAction(tracks.size), PillKind.Fault) {
                    scope.launch {
                        val ids = tracks.map { it.id }
                        try {
                            val n = session.remove(ids)
                            Downloads.remove(app, session, ids)
                            onRemoved { say(Voice.removed(n)) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CosineException) {
                            say(e.message ?: "Cosine refused that.", Signal.Fault)
                        } catch (e: IOException) {
                            say(Voice.serverUnreachable(isCosine = true), Signal.Server)
                        }
                    }
                },
            ),
        ),
    )
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
    nav: Pages,
    quiet: Boolean = false,
) {
    val graph = context.graph
    val settings = graph.settings.state.value
    if (settings.downloadTreeUri == null) {
        say(Voice.CHOOSE_DOWNLOAD_FOLDER)
        nav.navigate(Routes.SETTINGS, singleTop = true)
        return
    }
    val accountSettings = settings.forAccount(session.account.id)
    if (!quiet && tracks != null) {
        val adding = tracks.filterNot { it.id in session.downloads }.sumOf { it.sizeBytes ?: 0L }
        val check = PinnedBudget.check(session.downloads.totalBytes, adding, accountSettings.pinnedBudgetBytes)
        if (check is PinnedBudget.Check.Over) say(Voice.overBudget(check.byBytes))
    }
    Downloads.enqueue(context, session, node, accountSettings.downloadOnMetered)
    if (quiet) return
    val waiting = graph.network.state.value.metered && !accountSettings.downloadOnMetered
    say(if (waiting) "Queued. Waiting for an unmetered connection." else "Download queued.")
}
