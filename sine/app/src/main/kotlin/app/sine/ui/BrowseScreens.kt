@file:OptIn(ExperimentalMaterial3Api::class)

package app.sine.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.sine.core.model.Album
import app.sine.core.model.SearchHit
import app.sine.core.model.SearchRanking
import app.sine.core.subsonic.AlbumListType
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.NotAvailableOffline
import app.sine.data.Sourced
import app.sine.download.Downloads
import app.sine.graph
import kotlinx.coroutines.delay

@Composable
fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------- Home

/**
 * Placeholder for the tile canvas (§6.4), which waits on the new design
 * language. For now: plain lists of the user's own history — nothing here
 * suggests music from outside the library (NG3).
 */
@Composable
fun HomeScreen(session: AccountSession, nav: NavController) {
    val player = LocalContext.current.graph.player
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val load = rememberLoad(session.account.id) {
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
    LoadContent(load) { sections, _ ->
        LazyColumn(Modifier.fillMaxSize()) {
            if (downloads.isNotEmpty()) item {
                Button(
                    onClick = { player.play(session, session.offline().all(), shuffle = true) },
                    modifier = Modifier.padding(16.dp),
                ) { Text("Shuffle downloads (${downloads.size})") }
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

@Composable
fun AlbumRow(session: AccountSession, album: Album, nav: NavController) {
    ItemRow(album.name, album.subtitle(), session.artwork(album.coverArt, 150)) {
        nav.navigate(Routes.album(album.id))
    }
}

// ---------------------------------------------------------------- Search

/**
 * One ranked list (§6.8), always global. Against the server in compat mode —
 * there is no local mirror there — and against downloads when offline.
 */
@Composable
fun SearchScreen(session: AccountSession, nav: NavController) {
    val player = LocalContext.current.graph.player
    var query by rememberSaveable { mutableStateOf("") }
    val load = rememberLoad(query, session.account.id) {
        if (query.isBlank()) return@rememberLoad Sourced(emptyList<SearchHit>(), false)
        delay(250) // debounce: a new keystroke cancels this and starts again
        session.fetch(
            offline = { lib -> SearchRanking.rank(query, lib.search(query)) },
            remote = { c -> SearchRanking.rank(query, c.search(query)) },
        )
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Artists, albums, tracks") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (query.isBlank()) return@Column
        LoadContent(load) { hits, _ ->
            if (hits.isEmpty()) {
                Message(Voice.NO_SEARCH_RESULTS)
                return@LoadContent
            }
            val tracks = hits.filterIsInstance<SearchHit.OfTrack>().map { it.track }
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits) { hit ->
                    when (hit) {
                        is SearchHit.OfArtist -> ItemRow(hit.name, null, session.artwork(hit.artist.coverArt, 150), trailing = "Artist") {
                            nav.navigate(Routes.artist(hit.artist.id))
                        }
                        is SearchHit.OfAlbum -> ItemRow(hit.name, hit.album.artist, session.artwork(hit.album.coverArt, 150), trailing = "Album") {
                            nav.navigate(Routes.album(hit.album.id))
                        }
                        is SearchHit.OfPlaylist -> ItemRow(hit.name, null, session.artwork(hit.playlist.coverArt, 150), trailing = "Playlist") {
                            nav.navigate(Routes.playlist(hit.playlist.id))
                        }
                        is SearchHit.OfTrack -> ItemRow(hit.name, hit.track.artist, session.artwork(hit.track.coverArt, 150), trailing = "Track") {
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

/** Segmented root (§6.2): Artists / Albums / Playlists. Downloaded is a filter, not a segment. */
@Composable
fun LibraryScreen(session: AccountSession, nav: NavController) {
    val context = LocalContext.current
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    val workFlow = remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(Downloads.TAG) }
    val work by workFlow.collectAsStateWithLifecycle(emptyList())
    val activeDownloads = work.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            segments.forEachIndexed { i, label ->
                FilterChip(selected = segment == i, onClick = { segment = i }, label = { Text(label) })
            }
            FilterChip(selected = downloadedOnly, onClick = { downloadedOnly = !downloadedOnly }, label = { Text("Downloaded") })
        }
        if (activeDownloads > 0) {
            Text(
                if (activeDownloads == 1) "1 download in progress or waiting." else "$activeDownloads downloads in progress or waiting.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when (segment) {
            0 -> ArtistList(session, downloadedOnly, nav)
            1 -> AlbumList(session, downloadedOnly, nav)
            else -> PlaylistList(session, downloadedOnly, nav)
        }
    }
}

@Composable
private fun ArtistList(session: AccountSession, downloadedOnly: Boolean, nav: NavController) {
    val load = rememberLoad(session.account.id, downloadedOnly) {
        if (downloadedOnly) Sourced(session.offline().artists(), true)
        else session.fetch(offline = { it.artists() }, remote = { it.artists() })
    }
    LoadContent(load) { artists, _ ->
        if (artists.isEmpty()) Message(if (downloadedOnly) Voice.NO_DOWNLOADS else "No artists.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(artists, key = { it.id }) { a ->
                ItemRow(a.name, a.subtitle(), session.artwork(a.coverArt, 150)) { nav.navigate(Routes.artist(a.id)) }
            }
        }
    }
}

@Composable
private fun AlbumList(session: AccountSession, downloadedOnly: Boolean, nav: NavController) {
    val load = rememberLoad(session.account.id, downloadedOnly) {
        if (downloadedOnly) Sourced(session.offline().albums(), true) else session.allAlbums()
    }
    LoadContent(load) { albums, _ ->
        if (albums.isEmpty()) Message(if (downloadedOnly) Voice.NO_DOWNLOADS else "No albums.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(albums, key = { it.id }) { AlbumRow(session, it, nav) }
        }
    }
}

@Composable
private fun PlaylistList(session: AccountSession, downloadedOnly: Boolean, nav: NavController) {
    val load = rememberLoad(session.account.id, downloadedOnly) {
        // Compat mode has no local mirror, so playlists need the server (G2 degrades, §2.1).
        session.fetch(offline = { throw NotAvailableOffline() }, remote = { it.playlists() })
    }
    LoadContent(load) { playlists, _ ->
        if (playlists.isEmpty()) Message("No playlists.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { p ->
                ItemRow(p.name, p.subtitle(), session.artwork(p.coverArt, 150)) { nav.navigate(Routes.playlist(p.id)) }
            }
        }
    }
}
