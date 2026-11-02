package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.home.HomeEdits
import app.sine.core.home.Tile
import app.sine.core.home.TileKind
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Track
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.ui.phase.AmbientSource
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val PagePadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 48.dp)

@Composable
fun AlbumsPage(session: AccountSession) {
    val nav = LocalNav.current
    val mirrored by session.mirrored.collectAsState()
    val load = rememberLoad(session, mirrored) { session.allAlbums().value }
    LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PagePadding, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageTitle("Albums", label = (load as? Load.Done)?.value?.size?.let { "$it albums" }) }
        when (load) {
            is Load.Done -> items(load.value, key = { it.id }) { a -> AlbumCard(session, a) { nav.go(Dest.Album(a.id, a.name)) } }
            else -> item(span = { GridItemSpan(maxLineSpan) }) { Loaded(load) {} }
        }
    }
}

@Composable
fun AlbumCard(session: AccountSession, album: Album, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.hoverable(hover).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val model = remember(album.coverArt) { session.artwork(album.coverArt, 400) }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(PhaseShape.Square).background(if (hovered) PhaseColor.Lift else PhaseColor.Raised)) {
            if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column {
            Text(album.name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "), color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ArtistsPage(session: AccountSession) {
    val nav = LocalNav.current
    val mirrored by session.mirrored.collectAsState()
    val load = rememberLoad(session, mirrored) { session.artists().value }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PagePadding, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageTitle("Artists", label = (load as? Load.Done)?.value?.size?.let { "$it artists" }) }
        when (load) {
            is Load.Done -> items(load.value, key = { it.id }) { a -> ArtistCard(session, a) { nav.go(Dest.Artist(a.id, a.name)) } }
            else -> item(span = { GridItemSpan(maxLineSpan) }) { Loaded(load) {} }
        }
    }
}

@Composable
private fun ArtistCard(session: AccountSession, artist: Artist, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val model = remember(artist.coverArt) { session.artwork(artist.coverArt, 400) }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.CircleShape).background(PhaseColor.Raised)) {
            if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(artist.name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistsPage(session: AccountSession) {
    val nav = LocalNav.current
    val changed by session.playlistsChanged.collectAsState()
    val mirrored by session.mirrored.collectAsState()
    val load = rememberLoad(session, changed, mirrored) { session.playlists().value }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding) {
        item { PageTitle("Playlists") }
        when (load) {
            is Load.Done -> items(load.value, key = { it.id }) { p ->
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(PhaseShape.Square)
                        .background(if (hovered) PhaseColor.Raised else PhaseColor.Ground)
                        .hoverable(hover).clickable { nav.go(Dest.Playlist(p.id, p.name)) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Cover(session, p.coverArt, 44.dp, px = 150)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(Voice.songs(p.trackCount), p.owner?.takeIf { !p.mine }?.let { Voice.sharedBy(it) }).joinToString(" · "),
                            color = PhaseColor.Text2, style = PhaseType.Small,
                        )
                    }
                    if (p.isCosineSmart) PhaseLabel("Smart")
                }
            }
            else -> item { Loaded(load) {} }
        }
    }
}

@Composable
fun DownloadsPage(session: AccountSession) {
    val graph = LocalGraph.current
    val downloaded by session.downloads.tracks.collectAsState()
    val progress by graph.downloads.progress.collectAsState()
    val tracks = remember(downloaded) {
        downloaded.values.map { it.track }.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
    }
    SongTable(session, tracks, Modifier.fillMaxSize()) {
        item {
            Column {
                PageTitle("On this computer", label = Voice.songs(tracks.size)) {
                    Pill("Shuffle", { graph.player.shuffle(session, tracks) }, kind = PillKind.Secondary, icon = PhaseIcons.Shuffle, height = 44.dp, enabled = tracks.isNotEmpty())
                }
                Text("In ${graph.music.path}. They play with no server.", Modifier.padding(bottom = 16.dp), color = PhaseColor.Text2, style = PhaseType.Small)
                progress.current?.let { StatusLine("Downloading $it · ${progress.waiting} to go", Modifier.padding(bottom = 16.dp), signal = Signal.Server) }
                if (tracks.isEmpty()) Text(Voice.NO_DOWNLOADS + " Right-click songs, or use Download on an album or playlist.", color = PhaseColor.Text2, style = PhaseType.Body)
            }
        }
    }
}

/** A detail page's head: the cover, what it is and its name, the facts, and what to do with it. */
@Composable
private fun DetailHead(
    session: AccountSession,
    coverArt: String?,
    label: String,
    title: String,
    facts: String,
    tracks: List<Track>,
    pin: Tile?,
    round: Boolean = false,
    sub: (@Composable () -> Unit)? = null,
) {
    val graph = LocalGraph.current
    val say = LocalSay.current
    val layout by session.home.layout.collectAsState()
    AmbientSource(session.artwork(coverArt, 300))
    Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
        Cover(session, coverArt, 200.dp, round = round, px = 600)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PhaseLabel(label)
            Text(title, color = PhaseColor.Text1, style = PhaseType.display(60.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub?.invoke()
            Text(facts, color = PhaseColor.Text2, style = PhaseType.Data)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Play", { graph.player.play(session, tracks, 0) }, kind = PillKind.Primary, icon = PhaseIcons.Play, enabled = tracks.isNotEmpty())
                Pill("Shuffle", { graph.player.shuffle(session, tracks) }, kind = PillKind.Secondary, icon = PhaseIcons.Shuffle, enabled = tracks.isNotEmpty())
                val downloaded by session.downloads.tracks.collectAsState()
                val all = tracks.isNotEmpty() && tracks.all { it.id in downloaded }
                if (all) {
                    Pill(null, { graph.downloads.remove(session, tracks.map { it.id }); say("Removed from this computer.") }, icon = PhaseIcons.OffPhone, height = 44.dp, contentDescription = "Remove download")
                } else {
                    Pill(null, { graph.downloads.download(session, tracks); say("Downloading ${Voice.songs(tracks.size)}.", Signal.Server) }, icon = PhaseIcons.OnPhone, height = 44.dp, contentDescription = "Download", enabled = tracks.isNotEmpty())
                }
                if (pin != null) {
                    val tiles = layout?.tiles ?: desktopDefaults(session)
                    val pinned = tiles.any { it.id == pin.id }
                    Pill(
                        null,
                        {
                            session.editHome(if (pinned) HomeEdits.remove(tiles, pin.id) else HomeEdits.add(tiles, pin))
                            say(if (pinned) "Off Home." else "Pinned to Home.")
                        },
                        icon = if (pinned) PhaseIcons.Unpin else PhaseIcons.Pin, height = 44.dp,
                        contentDescription = if (pinned) "Take off Home" else "Pin to Home",
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumPage(session: AccountSession, dest: Dest.Album) {
    val nav = LocalNav.current
    val mirrored by session.mirrored.collectAsState()
    val load = rememberLoad(session, dest.id, mirrored) { session.album(dest.id).value }
    when (load) {
        is Load.Done -> {
            val (album, tracks) = load.value
            SongTable(session, tracks, Modifier.fillMaxSize(), numbered = true, showArt = false) {
                item {
                    DetailHead(
                        session, album.coverArt, "Album", album.name,
                        listOfNotNull(album.year?.toString(), Voice.songs(tracks.size), length(tracks.sumOf { it.durationSec }), tracks.firstOrNull()?.suffix?.uppercase()).joinToString(" · "),
                        tracks, Tile(TileKind.ALBUM, album.id, name = album.name),
                    ) {
                        Text(
                            album.artist, color = PhaseColor.Text1, style = PhaseType.Name,
                            modifier = Modifier.clickable(enabled = album.artistId != null) { album.artistId?.let { nav.go(Dest.Artist(it, album.artist)) } },
                        )
                    }
                }
            }
        }
        else -> Column(Modifier.padding(PagePadding)) { PageTitle(dest.name, label = "Album"); Loaded(load) {} }
    }
}

@Composable
fun ArtistPage(session: AccountSession, dest: Dest.Artist) {
    val nav = LocalNav.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val mirrored by session.mirrored.collectAsState()
    val load = rememberLoad(session, dest.id, mirrored) { session.artist(dest.id).value }
    when (load) {
        is Load.Done -> {
            val (artist, albums) = load.value
            LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize(), contentPadding = PagePadding, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val layout by session.home.layout.collectAsState()
                    AmbientSource(session.artwork(artist.coverArt, 300))
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Bottom) {
                        Cover(session, artist.coverArt, 200.dp, round = true, px = 600)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PhaseLabel("Artist")
                            Text(artist.name, color = PhaseColor.Text1, style = PhaseType.display(60.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (albums.size == 1) "1 album" else "${albums.size} albums", color = PhaseColor.Text2, style = PhaseType.Data)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Shuffle", {
                                    scope.launch {
                                        val tracks = albums.flatMap { runCatching { session.album(it.id).value.tracks }.getOrDefault(emptyList()) }
                                        graph.player.shuffle(session, tracks)
                                    }
                                }, kind = PillKind.Primary, icon = PhaseIcons.Shuffle)
                                val tiles = layout?.tiles ?: desktopDefaults(session)
                                val pin = Tile(TileKind.ARTIST, artist.id, name = artist.name)
                                val pinned = tiles.any { it.id == pin.id }
                                Pill(
                                    null, { session.editHome(if (pinned) HomeEdits.remove(tiles, pin.id) else HomeEdits.add(tiles, pin)) },
                                    icon = if (pinned) PhaseIcons.Unpin else PhaseIcons.Pin, height = 44.dp,
                                    contentDescription = if (pinned) "Take off Home" else "Pin to Home",
                                )
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { PhaseLabel("Albums", Modifier.padding(top = 16.dp)) }
                items(albums, key = { it.id }) { a -> AlbumCard(session, a) { nav.go(Dest.Album(a.id, a.name)) } }
            }
        }
        else -> Column(Modifier.padding(PagePadding)) { PageTitle(dest.name, label = "Artist"); Loaded(load) {} }
    }
}

@Composable
fun PlaylistPage(session: AccountSession, dest: Dest.Playlist) {
    val say = LocalSay.current
    val scope = rememberCoroutineScope()
    val changed by session.playlistsChanged.collectAsState()
    val mirrored by session.mirrored.collectAsState()
    val load = rememberLoad(session, dest.id, changed, mirrored) { session.playlist(dest.id).value }
    when (load) {
        is Load.Done -> {
            val (playlist, tracks) = load.value
            val editable = playlist.mine && !playlist.readonly && !playlist.isLiked && !playlist.isCosineSmart && session.canEditPlaylists
            SongTable(
                session, tracks, Modifier.fillMaxSize(),
                extras = SongExtras(
                    removeFromPlaylist = if (!editable) null else { positions ->
                        scope.launch {
                            runCatching { session.removeFromPlaylist(playlist.id, positions, tracks) }
                                .onSuccess { say(Voice.removedFromPlaylist(positions.size)) }
                                .onFailure { say(it.message ?: "That didn't work.", Signal.Fault) }
                        }
                    },
                ),
            ) {
                item {
                    DetailHead(
                        session, playlist.coverArt, if (playlist.isCosineSmart) "Smart playlist" else "Playlist", playlist.name,
                        listOfNotNull(Voice.songs(tracks.size), length(tracks.sumOf { it.durationSec }), playlist.owner?.takeIf { !playlist.mine }?.let { Voice.sharedBy(it) }).joinToString(" · "),
                        tracks, Tile(TileKind.PLAYLIST, playlist.id, name = playlist.name),
                    )
                    if (tracks.isEmpty()) Text(if (playlist.isLiked) "Nothing liked yet. Like a song from the player." else Voice.EMPTY_PLAYLIST, color = PhaseColor.Text2, style = PhaseType.Body)
                }
            }
        }
        else -> Column(Modifier.padding(PagePadding)) { PageTitle(dest.name, label = "Playlist"); Loaded(load) {} }
    }
}
