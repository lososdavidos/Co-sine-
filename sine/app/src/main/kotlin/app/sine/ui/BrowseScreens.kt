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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.sine.core.model.SearchHit
import app.sine.core.model.SearchRanking
import app.sine.core.server.Capability
import app.sine.core.subsonic.AlbumListType
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.download.Downloads
import app.sine.graph
import app.sine.ui.phase.Chip
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Segmented
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.StatusLine
import kotlinx.coroutines.delay

@Composable
fun SectionHeader(text: String) = SectionLabel(text)

/** The id of the song playing now, to mark its row. */
@Composable
fun playingId(): String? = LocalContext.current.graph.player.state.collectAsStateWithLifecycle().value.current?.mediaId

// ---------------------------------------------------------------- Home

/**
 * Placeholder for the tile canvas (§6.4), which isn't built yet. For now:
 * the user's own history in plain lists — nothing here suggests music from
 * outside the library (NG3).
 */
@Composable
fun HomeScreen(session: AccountSession, nav: Pages) {
    val player = LocalContext.current.graph.player
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val load = rememberLoad(session) {
        session.fetch(
            offline = { lib -> listOf("Downloaded" to lib.albums()) },
            remote = { c ->
                listOf(
                    "Recently added" to c.albums(AlbumListType.NEWEST, 12),
                    "Recently played" to c.albums(AlbumListType.RECENT, 12),
                    "Most played" to c.albums(AlbumListType.FREQUENT, 12),
                )
            },
        )
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Home")
        LoadContent(load) { sections, _ ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                if (downloads.isNotEmpty()) item {
                    Pill(
                        "Shuffle downloads", { player.play(session, session.offline().all(), shuffle = true) },
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        icon = PhaseIcons.Shuffle, count = downloads.size.toString(),
                    )
                }
                for ((title, albums) in sections) {
                    if (albums.isEmpty()) continue
                    item(key = "h:$title") { SectionHeader(title) }
                    items(albums, key = { "$title:${it.id}" }) { album -> AlbumRow(session, album, nav) }
                }
                if (sections.all { it.second.isEmpty() }) item { Message(Voice.NO_DOWNLOADS) }
            }
        }
    }
}

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
 * result's kind is a mono label, not a plane.
 */
@Composable
fun SearchScreen(session: AccountSession, nav: Pages) {
    val player = LocalContext.current.graph.player
    val playing = playingId()
    var query by rememberSaveable { mutableStateOf("") }
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    // Searching the mirror is instant: one load for every keystroke keeps
    // the last results up while typing. Searching the server shows it's
    // working, so each query starts afresh.
    val local = session.searchMirror("") != null
    val load = rememberLoad(session, if (local) Unit else query, refreshOn = query to mirrored) {
        if (query.isBlank()) return@rememberLoad Sourced(emptyList<SearchHit>(), false)
        // The mirror answers as you type (§6.8); the server gets a pause first.
        session.searchMirror(query)?.let {
            return@rememberLoad Sourced(SearchRanking.rank(query, it, session.searchPlaylists(query)), false)
        }
        delay(250) // debounce: a new keystroke cancels this and starts again
        session.fetch(
            offline = { lib -> SearchRanking.rank(query, lib.search(query)) },
            remote = { c -> SearchRanking.rank(query, c.search(query)) },
        )
    }
    Column(Modifier.fillMaxSize()) {
        TitleBar("Search")
        PhaseField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            placeholder = "Artists, albums, songs",
            leading = PhaseIcons.Search,
            onClear = { query = "" },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        if (query.isBlank()) return@Column
        LoadContent(load) { hits, _ ->
            if (hits.isEmpty()) {
                ListMessage(Voice.NO_SEARCH_RESULTS)
                return@LoadContent
            }
            val tracks = hits.filterIsInstance<SearchHit.OfTrack>().map { it.track }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
                items(hits) { hit ->
                    when (hit) {
                        is SearchHit.OfArtist -> ItemRow(hit.name, null, session.artwork(hit.artist.coverArt, 150), roundArt = true, label = "Artist") {
                            nav.navigate(Routes.artist(hit.artist.id))
                        }
                        is SearchHit.OfAlbum -> ItemRow(hit.name, hit.album.artist, session.artwork(hit.album.coverArt, 150), label = "Album") {
                            nav.navigate(Routes.album(hit.album.id))
                        }
                        is SearchHit.OfPlaylist -> ItemRow(hit.name, null, session.artwork(hit.playlist.coverArt, 150), label = "Playlist") {
                            nav.navigate(Routes.playlist(hit.playlist.id))
                        }
                        is SearchHit.OfTrack -> ItemRow(
                            hit.name, hit.track.artist, session.artwork(hit.track.coverArt, 150),
                            label = "Song", playing = hit.track.id == playing,
                        ) {
                            player.play(session, tracks, tracks.indexOf(hit.track))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Library

private val segments = listOf("Artists", "Albums", "Playlists")

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
        Segmented(segments, segment, { segment = it }, Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (segment < 2) Chip("On phone", downloadedOnly, { downloadedOnly = !downloadedOnly })
            Spacer(Modifier.weight(1f))
            if (canReview) {
                val n = toReview ?: 0
                Pill(
                    "Review", { nav.navigate(Routes.REVIEW) }, height = 40.dp,
                    kind = if (n > 0) PillKind.Server else PillKind.Secondary,
                    count = if (n > 0) n.toString() else null,
                )
            }
            if (canIngest) Pill("Add", { nav.navigate(Routes.add()) }, height = 40.dp, icon = PhaseIcons.Plus)
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
