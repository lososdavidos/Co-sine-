@file:OptIn(ExperimentalMaterial3Api::class)

package app.sine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Recent
import app.sine.core.model.SearchHit
import app.sine.core.model.SearchRanking
import app.sine.core.play.PlaySource
import app.sine.core.server.Capability
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.download.Downloads
import app.sine.graph
import app.sine.ui.phase.Chip
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Segmented
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.StatusLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SectionHeader(text: String) = SectionLabel(text)

/** The id of the song playing now, to mark its row. */
@Composable
fun playingId(): String? = LocalContext.current.graph.player.state.collectAsStateWithLifecycle().value.current?.mediaId

// ---------------------------------------------------------------- Home

@Composable
fun AlbumRow(session: AccountSession, album: Album, nav: Pages) {
    ItemRow(album.name, album.subtitle(), session.artwork(album.coverArt, 150)) {
        nav.navigate(Routes.album(album.id))
    }
}

// ---------------------------------------------------------------- Search

/**
 * One ranked list (§6.8), always global. Against the server in compat mode —
 * there is no local mirror there — and against downloads when offline. Each
 * result's kind is a mono label, not a plane. The results on screen stay
 * until the next ones are there, so typing never blanks the list.
 * Empty, the field shows what was opened from it before. With +, the
 * same field searches the sources for music to add (§6.10), as you type.
 */
@Composable
fun SearchScreen(session: AccountSession, nav: Pages) {
    val context = LocalContext.current
    val player = context.graph.player
    val playing = playingId()
    val scope = rememberCoroutineScope()
    val shelf = LocalShelf.current
    val liking by session.liking.collectAsStateWithLifecycle()
    val like = rememberLike(session)
    var query by rememberSaveable { mutableStateOf("") }
    // + : the field looks in the sources instead of the library.
    var finding by rememberSaveable { mutableStateOf(false) }
    val cosine = session.cosine
    val adding = finding && cosine != null && canAdd(session)
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val history = session.searchHistory
    val recents by history.items.collectAsStateWithLifecycle()
    // One loader for every keystroke: the last results stay up while the next
    // load. The mirror answers at once; the server gets a pause first.
    val load = rememberLoad(session, refreshOn = query to mirrored) {
        if (query.isBlank()) return@rememberLoad Sourced(emptyList<SearchHit>(), false)
        session.searchMirror(query)?.let {
            return@rememberLoad Sourced(SearchRanking.rank(query, it, session.searchPlaylists(query)), false)
        }
        delay(250) // debounce: a new keystroke cancels this and starts again
        session.fetch(
            offline = { lib -> SearchRanking.rank(query, lib.search(query)) },
            remote = { c -> SearchRanking.rank(query, c.search(query)) },
        )
    }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // Songs held (§6.5): the shelf takes their actions, as on a release.
    val hits = (load.load as? Load.Ready)?.value.orEmpty()
    val songs = hits.filterIsInstance<SearchHit.OfTrack>().map { it.track }
    ShowSelection(
        if (selected.isEmpty() || adding) null else {
            val chosen = songs.filter { it.id in selected }
            trackSelection(
                context, session, chosen, nav, scope, shelf,
                canRemoveFromLibrary = session.can(Capability.REMOVE),
                onAddToPlaylist = { nav.form { AddToPlaylistForm(session, chosen) } },
            ) { selected = emptySet() }
        },
    )
    LaunchedEffect(query, adding) { selected = emptySet() }

    fun open(r: Recent) {
        history.opened(r)
        when (r.kind) {
            Recent.ARTIST -> nav.navigate(Routes.artist(r.id))
            Recent.ALBUM -> nav.navigate(Routes.album(r.id))
            Recent.PLAYLIST -> nav.navigate(Routes.playlist(r.id))
            Recent.SONG -> scope.launch {
                val track = runCatching { session.track(r.id).value }.getOrNull()
                if (track == null || session.playSource(track) == PlaySource.Unavailable) say(Voice.TRACK_UNAVAILABLE)
                else player.play(session, listOf(track))
            }
        }
    }

    val finder = if (cosine != null && canAdd(session)) rememberSourceFinder(session, cosine) else null
    if (finder != null && adding) LookAsYouType(finder, query)

    Column(Modifier.fillMaxSize()) {
        TitleBar("Search")
        PhaseField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            placeholder = if (adding) "Find music to add, or paste a link" else "Artists, albums, songs",
            leading = PhaseIcons.Search,
            onClear = { query = "" },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (adding) finder?.look(query.trim()) }),
            trailing = if (finder == null) null else ({
                // Where the field looks: the light pill while it's the sources.
                Pill(
                    null, { finding = !finding }, kind = if (adding) PillKind.Secondary else PillKind.Quiet,
                    icon = PhaseIcons.Plus, height = 44.dp, width = 44.dp,
                    contentDescription = if (adding) "Search your library" else "Find music to add",
                )
            }),
        )
        if (adding && finder != null) {
            FinderStatus(finder, Modifier.padding(horizontal = 20.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(top = 4.dp)) {
                if (query.trim().length < MIN_SEARCH && finder.lookup == null) item {
                    Text(
                        "Searches YouTube, Bandcamp and SoundCloud as you type, from the third letter. A pasted link is looked up at once.",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = PhaseColor.Text2, style = PhaseType.Small,
                    )
                }
                finderResults(finder) { track -> nav.navigate(Routes.correct(track)) }
            }
            return@Column
        }
        if (query.isBlank()) {
            RecentList(session, recents, onOpen = ::open, onRemove = history::remove, onClear = history::clear)
            return@Column
        }
        LoadContent(load) { results, _ ->
            if (results.isEmpty()) {
                ListMessage(Voice.NO_SEARCH_RESULTS)
                return@LoadContent
            }
            val tracks = results.filterIsInstance<SearchHit.OfTrack>().map { it.track }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                items(results) { hit ->
                    when (hit) {
                        is SearchHit.OfArtist -> ItemRow(hit.name, null, session.artwork(hit.artist.coverArt, 150), roundArt = true, label = "Artist") {
                            open(Recent(Recent.ARTIST, hit.artist.id, hit.name, coverArt = hit.artist.coverArt))
                        }
                        is SearchHit.OfAlbum -> ItemRow(hit.name, hit.album.artist, session.artwork(hit.album.coverArt, 150), label = "Album") {
                            open(Recent(Recent.ALBUM, hit.album.id, hit.name, hit.album.artist, hit.album.coverArt))
                        }
                        is SearchHit.OfPlaylist -> ItemRow(hit.name, null, session.artwork(hit.playlist.coverArt, 150), label = "Playlist") {
                            open(Recent(Recent.PLAYLIST, hit.playlist.id, hit.name, coverArt = hit.playlist.coverArt))
                        }
                        is SearchHit.OfTrack -> {
                            val t = hit.track
                            ItemRow(
                                hit.name, t.artist, session.artwork(t.coverArt, 150),
                                label = "Song", playing = t.id == playing, selected = t.id in selected,
                                dimmed = session.playSource(t) == PlaySource.Unavailable,
                                liked = liking[t.id] ?: t.liked,
                                onLike = { like(t.id, t.title, it) },
                                onLongClick = { selected = selected.toggle(t.id) },
                            ) {
                                when {
                                    selected.isNotEmpty() -> selected = selected.toggle(t.id)
                                    session.playSource(t) == PlaySource.Unavailable -> say(Voice.TRACK_UNAVAILABLE)
                                    else -> {
                                        history.opened(Recent(Recent.SONG, t.id, t.title, t.artist, t.coverArt))
                                        player.play(session, tracks, tracks.indexOf(t))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What was opened from search before, newest first: one tap opens it again, or takes it off the list. */
@Composable
private fun RecentList(session: AccountSession, recents: List<Recent>, onOpen: (Recent) -> Unit, onRemove: (Recent) -> Unit, onClear: () -> Unit) {
    if (recents.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PhaseLabel("Recently opened", Modifier.weight(1f))
                Pill("Clear", onClear, kind = PillKind.Quiet, height = 40.dp)
            }
        }
        items(recents, key = { it.kind + ":" + it.id }) { r ->
            ItemRow(
                r.name, r.subtitle, session.artwork(r.coverArt, 150),
                roundArt = r.kind == Recent.ARTIST,
                label = when (r.kind) {
                    Recent.ARTIST -> "Artist"
                    Recent.ALBUM -> "Album"
                    Recent.PLAYLIST -> "Playlist"
                    else -> "Song"
                },
                end = {
                    Pill(null, { onRemove(r) }, kind = PillKind.Quiet, icon = PhaseIcons.Close, height = 40.dp, width = 44.dp, contentDescription = "Take ${r.name} off the list")
                },
            ) { onOpen(r) }
        }
    }
}

// ---------------------------------------------------------------- Library

private val segments = listOf("Artists", "Albums", "Playlists")
private val segmentIcons = listOf(PhaseIcons.Artist, PhaseIcons.Album, PhaseIcons.Playlist)

/**
 * Segmented root (§6.2): Artists / Albums / Playlists. On phone is a filter,
 * not a segment. Add and the review queue sit in the Library's header, under
 * its title, only where the server can do them: compat mode has neither (§2.1).
 */
@Composable
fun LibraryScreen(session: AccountSession, nav: Pages) {
    val context = LocalContext.current
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    val workFlow = remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(Downloads.TAG) }
    val work by workFlow.collectAsStateWithLifecycle(emptyList())
    val activeDownloads = work.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val account by session.accountFlow.collectAsStateWithLifecycle()
    val cosine = session.cosine
    val canIngest = cosine != null && Capability.INGEST in account.kind.capabilities
    val canReview = cosine != null && Capability.REVIEW in account.kind.capabilities
    // The queue's count is the Cosine pill; zero is a resting state, not a signal (§6.12).
    var toReview by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(canReview) {
        if (canReview) toReview = runCatching { cosine?.reviewQueue(limit = 1)?.total }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Library")
        Segmented(segments, segment, { segment = it }, Modifier.padding(horizontal = 20.dp), icons = segmentIcons)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (segment < 2) Chip(null, downloadedOnly, { downloadedOnly = !downloadedOnly }, icon = PhaseIcons.OnPhone, contentDescription = "Only what's on this phone")
            Spacer(Modifier.weight(1f))
            if (canReview) {
                val n = toReview ?: 0
                Pill(
                    null, { nav.navigate(Routes.REVIEW) }, height = 40.dp, icon = PhaseIcons.Review,
                    kind = if (n > 0) PillKind.Server else PillKind.Secondary,
                    count = if (n > 0) n.toString() else null,
                    contentDescription = if (n > 0) "Review, $n waiting" else "Review",
                )
            }
            if (canIngest) Pill(null, { nav.navigate(Routes.add()) }, height = 40.dp, icon = PhaseIcons.Plus, contentDescription = "Add music")
        }
        if (activeDownloads > 0) {
            StatusLine(
                if (activeDownloads == 1) "1 download in progress or waiting." else "$activeDownloads downloads in progress or waiting.",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                color = PhaseColor.Text2,
            )
        }
        when (segment) {
            0 -> ArtistList(session, downloadedOnly, nav)
            1 -> AlbumList(session, downloadedOnly, nav)
            else -> PlaylistList(session, nav)
        }
    }
}

/** The letter an artist files under, as a record shop's dividers do: articles ignored. */
private fun Artist.letter(): String {
    val name = name.trim().removePrefix("The ").removePrefix("the ")
    val c = name.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c.isLetter()) c.toString() else "#"
}

@Composable
private fun ArtistList(session: AccountSession, downloadedOnly: Boolean, nav: Pages) {
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val load = rememberLoad(session, downloadedOnly, refreshOn = mirrored) {
        if (downloadedOnly) Sourced(session.offline().artists(), true) else session.artists()
    }
    LoadContent(load) { artists, _ ->
        if (artists.isEmpty()) ListMessage(if (downloadedOnly) Voice.NO_DOWNLOADS else "No artists.")
        else {
            val letters = artists.map { it.letter() }
            // Dividers only when the list really is in alphabetical order.
            val divided = letters.zipWithNext().all { (a, b) -> a == b || a == "#" || (b != "#" && a < b) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(top = 4.dp)) {
                artists.forEachIndexed { i, a ->
                    if (divided && (i == 0 || letters[i] != letters[i - 1])) {
                        item(key = "letter:${letters[i]}") { LetterDivider(letters[i]) }
                    }
                    item(key = a.id) {
                        ItemRow(a.name, a.subtitle(), session.artwork(a.coverArt, 150), roundArt = true) { nav.navigate(Routes.artist(a.id)) }
                    }
                }
            }
        }
    }
}

/** A crate divider: the letter, large, and a hairline. */
@Composable
private fun LetterDivider(letter: String) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(letter, style = PhaseType.display(32.sp), color = PhaseColor.Text1)
        Box(Modifier.weight(1f).height(1.dp).background(PhaseColor.Line))
    }
}

@Composable
private fun AlbumList(session: AccountSession, downloadedOnly: Boolean, nav: Pages) {
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val load = rememberLoad(session, downloadedOnly, refreshOn = mirrored) {
        if (downloadedOnly) Sourced(session.offline().albums(), true) else session.allAlbums()
    }
    LoadContent(load) { albums, _ ->
        if (albums.isEmpty()) ListMessage(if (downloadedOnly) Voice.NO_DOWNLOADS else "No albums.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(top = 4.dp)) {
            items(albums, key = { it.id }) { AlbumRow(session, it, nav) }
        }
    }
}
