package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sine.core.home.HomeDefaults
import app.sine.core.home.HomeEdits
import app.sine.core.home.Placed
import app.sine.core.home.Tile
import app.sine.core.home.TileGrid
import app.sine.core.home.TileKind
import app.sine.core.home.TileSize
import app.sine.core.server.Capability
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.People
import java.awt.Desktop
import java.net.URI

/** A tile's unit, as on the phone: a wide window gains columns, never bigger tiles (§6.15). */
private val Unit = 86.dp
private val Gap = 8.dp

/** The tiles Home starts with on the desktop, before you arrange it. */
fun desktopDefaults(session: AccountSession): List<Tile> {
    val caps = session.account.kind.capabilities
    val base = HomeDefaults.tiles(
        session.mirroredSmart(), mirrored = session.libraryTracks() != null,
        review = session.cosine != null && Capability.REVIEW in caps,
        ingest = session.cosine != null && Capability.INGEST in caps,
    )
    return base + listOfNotNull(
        Tile(TileKind.JAM, size = TileSize.Medium).takeIf { session.can(Capability.JAM) },
        Tile(TileKind.RESUME, size = TileSize.Small).takeIf { session.can(Capability.SESSIONS) },
    )
}

/** Home: the desktop's own arrangement (§6.15), synced with Cosine as the phone's is. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomePage(session: AccountSession) {
    val layout by session.home.layout.collectAsState()
    val mirrored by session.mirrored.collectAsState()
    val tiles = layout?.tiles ?: remember(mirrored) { desktopDefaults(session) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        PageTitle("Home") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(null, { adding = true }, icon = PhaseIcons.Plus, height = 44.dp, contentDescription = "Add a tile")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = ((maxWidth + Gap) / (Unit + Gap)).toInt().coerceAtLeast(TileGrid.COLUMNS)
            val placed = remember(tiles, columns) { TileGrid.place(tiles, columns) }
            val step = Unit + Gap
            Box(Modifier.fillMaxWidth().height(step * TileGrid.height(placed))) {
                for (p in placed) {
                    Box(
                        Modifier
                            .offset(step * p.column, step * p.row)
                            .size(step * p.tile.size.columns - Gap, step * p.tile.size.rows - Gap)
                            .onPointerEvent(PointerEventType.Press) { e -> if (e.buttons.isSecondaryPressed) menuFor = p.tile.id },
                    ) {
                        TileFace(session, p)
                        if (menuFor == p.tile.id) {
                            TileMenu(
                                onDismiss = { menuFor = null },
                                onResize = { session.editHome(HomeEdits.resize(tiles, p.tile.id)) },
                                onEarlier = { session.editHome(HomeEdits.move(tiles, p.tile.id, (tiles.indexOfFirst { it.id == p.tile.id } - 1).coerceAtLeast(0))) },
                                onLater = { session.editHome(HomeEdits.move(tiles, p.tile.id, (tiles.indexOfFirst { it.id == p.tile.id } + 1).coerceAtMost(tiles.lastIndex))) },
                                onRemove = { session.editHome(HomeEdits.remove(tiles, p.tile.id)) },
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Right-click a tile to resize, move or remove it. Pin albums, artists and playlists from their pages.",
            Modifier.padding(top = 28.dp), color = PhaseColor.Text3, style = PhaseType.Small,
        )
    }
    if (adding) AddTile(session, tiles, onDone = { adding = false })
}

@Composable
private fun TileMenu(onDismiss: () -> Unit, onResize: () -> Unit, onEarlier: () -> Unit, onLater: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(
        expanded = true, onDismissRequest = onDismiss, containerColor = PhaseColor.Float, shape = PhaseShape.Floating,
        modifier = Modifier.border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating).width(220.dp),
    ) {
        @Composable
        fun item(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tone: IconTone, f: () -> Unit, fault: Boolean = false) =
            DropdownMenuItem(
                text = { Text(text, color = if (fault) PhaseColor.Fault else PhaseColor.Text1, style = PhaseType.Name) },
                onClick = { onDismiss(); f() },
                leadingIcon = { PhaseIcon(icon, null, size = 20.dp, tone = tone) },
            )
        item("Resize", PhaseIcons.Resize, IconTone.Sine, onResize)
        item("Earlier", PhaseIcons.Earlier, IconTone.SineFar, onEarlier)
        item("Later", PhaseIcons.Later, IconTone.Sine, onLater)
        item("Remove", PhaseIcons.Unpin, IconTone.Fault, onRemove, fault = true)
    }
}

/** What a tile shows, and what it does when clicked. */
@Composable
private fun TileFace(session: AccountSession, p: Placed) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val tile = p.tile
    val small = tile.size == TileSize.Small
    when (tile.kind) {
        TileKind.NOW_PLAYING -> NowPlayingFace(session, tile.size)
        TileKind.SHUFFLE_ALL -> QuietFace(PhaseIcons.Shuffle, IconTone.Sine, "Shuffle all", null, small) {
            session.libraryTracks()?.let { graph.player.shuffle(session, it) }
        }
        TileKind.DOWNLOADED -> {
            val downloaded by session.downloads.tracks.collectAsState()
            QuietFace(PhaseIcons.OnPhone, IconTone.Sine, "On this computer", if (small) null else Voice.songs(downloaded.size), small) { nav.go(Dest.Downloads) }
        }
        TileKind.REVIEW -> ServerFace(PhaseIcons.Review, "To review", "Opens Cosine's dashboard", small) { openDashboard(session, "review") }
        TileKind.INGEST -> QuietFace(PhaseIcons.Upload, IconTone.Cosine, "Adding", if (small) null else "Opens Cosine's dashboard", small) { openDashboard(session, "") }
        TileKind.STATS -> QuietFace(PhaseIcons.Wave, IconTone.SineFar, "Stats", if (small) null else "Opens Cosine's dashboard", small) { openDashboard(session, "stats") }
        TileKind.RESUME -> QuietFace(PhaseIcons.Device, IconTone.SineFar, "Pick up", if (small) null else "From another device", small) { nav.go(Dest.Devices) }
        TileKind.JAM -> JamFace(small)
        TileKind.ALBUM, TileKind.PLAYLIST, TileKind.ARTIST -> PinnedFace(session, tile)
        else -> QuietFace(PhaseIcons.Open, IconTone.Dim, tile.name ?: tile.kind, null, small) {}
    }
}

private fun openDashboard(session: AccountSession, path: String) {
    runCatching { Desktop.getDesktop().browse(URI(session.account.serverUrl.trimEnd('/') + "/" + path)) }
}

@Composable
private fun Slab(onClick: () -> Unit, modifier: Modifier = Modifier, fill: androidx.compose.ui.graphics.Color = PhaseColor.Raised, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().clip(PhaseShape.Slab).background(fill).clickable(onClick = onClick).then(modifier)) { content() }
}

@Composable
private fun QuietFace(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: IconTone, title: String, subtitle: String?, small: Boolean, onClick: () -> Unit) {
    Slab(onClick, Modifier.padding(if (small) 12.dp else 14.dp)) {
        Column(Modifier.fillMaxSize()) {
            PhaseIcon(icon, null, size = if (small) 24.dp else 30.dp, tone = tone)
            Spacer(Modifier.weight(1f))
            Text(title, color = PhaseColor.Text1, style = if (small) PhaseType.Small.copy(fontSize = 13.sp) else PhaseType.Name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ServerFace(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, small: Boolean, onClick: () -> Unit) {
    Slab(onClick, Modifier.padding(if (small) 12.dp else 14.dp), fill = PhaseColor.Cosine) {
        Column(Modifier.fillMaxSize()) {
            PhaseIcon(icon, null, size = if (small) 24.dp else 30.dp, tone = IconTone.OnCosine)
            Spacer(Modifier.weight(1f))
            Text(title, color = PhaseColor.OnCosine, style = if (small) PhaseType.Small.copy(fontSize = 13.sp) else PhaseType.Name, maxLines = 2)
            if (!small) Text(subtitle, color = PhaseColor.OnCosine, style = PhaseType.Small, maxLines = 1)
        }
    }
}

@Composable
private fun NowPlayingFace(session: AccountSession, size: TileSize) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsState()
    val last by graph.player.last.collectAsState()
    val track = state.current ?: last?.let { l -> l.queue.getOrNull(l.index) }
    val wide = size == TileSize.Wide
    Slab({ if (state.current != null) graph.player.togglePlay() else graph.player.restore().also { graph.player.resume() } }, Modifier.padding(12.dp)) {
        if (track == null) {
            Column {
                PhaseIcon(PhaseIcons.Play, null, size = 30.dp, tone = IconTone.Sine)
                Spacer(Modifier.weight(1f))
                Text("Now playing", color = PhaseColor.Text1, style = PhaseType.Name)
                Text("Nothing played yet.", color = PhaseColor.Text2, style = PhaseType.Small)
            }
            return@Slab
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (wide || size == TileSize.Medium) Cover(session, track.coverArt, if (wide) 156.dp else 64.dp, px = 300)
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                PhaseLabel(if (state.current != null) "Now playing" else "Last played")
                Spacer(Modifier.weight(1f))
                Text(track.title, color = PhaseColor.Text1, style = if (wide) PhaseType.Title else PhaseType.Name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = if (state.current != null && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                Box(Modifier.padding(top = 10.dp, bottom = 6.dp).fillMaxWidth().height(3.dp).clip(PhaseShape.Pill).background(PhaseColor.Bar)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(PhaseColor.Sine))
                }
            }
        }
    }
}

@Composable
private fun JamFace(small: Boolean) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val jam by graph.jam.jam.collectAsState()
    val invitations by graph.jam.invitations.collectAsState()
    val j = jam
    Slab({ nav.go(Dest.Jam) }, Modifier.padding(if (small) 12.dp else 14.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PhaseIcon(PhaseIcons.Jam, null, size = if (small) 24.dp else 30.dp, tone = IconTone.Cosine)
            Spacer(Modifier.weight(1f))
            if (j != null && !small) People(j.members.filter { it.present }.map { it.name }, Modifier.padding(bottom = 6.dp), ring = PhaseColor.Raised)
            Text("Jam", color = PhaseColor.Text1, style = PhaseType.Name)
            if (!small) {
                Text(
                    when {
                        j != null -> Voice.people(j.members.count { it.present })
                        invitations.isNotEmpty() -> "${invitations.first().host} asked you"
                        else -> "Listen together"
                    },
                    color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PinnedFace(session: AccountSession, tile: Tile) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val ref = tile.ref ?: return
    val name = tile.name ?: ""
    val open: () -> Unit = {
        when (tile.kind) {
            TileKind.ALBUM -> nav.go(Dest.Album(ref, name))
            TileKind.ARTIST -> nav.go(Dest.Artist(ref, name))
            else -> nav.go(Dest.Playlist(ref, name))
        }
    }
    val cover = session.coverOf(ref)
    if (tile.kind == TileKind.ARTIST) {
        Slab(open, Modifier.padding(12.dp)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                val side: Dp = if (tile.size == TileSize.Small) 40.dp else 100.dp
                Cover(session, cover, side, round = true)
                if (tile.size != TileSize.Small) Text(name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Slab({ if (tile.shuffle) shufflePinned(graph, session, tile) else open() }) {
        val model = remember(cover) { session.artwork(cover, 600) }
        if (model != null) coil3.compose.AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (tile.size != TileSize.Small) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(PhaseColor.Scrim).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tile.shuffle) PhaseLabel("Shuffle", color = PhaseColor.OnScrim)
            }
        }
    }
}

private fun shufflePinned(graph: app.sine.desktop.DesktopGraph, session: AccountSession, tile: Tile) {
    val ref = tile.ref ?: return
    graph.scope.launchCatching {
        val tracks = when (tile.kind) {
            TileKind.ALBUM -> session.album(ref).value.tracks
            TileKind.PLAYLIST -> session.playlist(ref).value.tracks
            else -> emptyList()
        }
        graph.player.shuffle(session, tracks)
    }
}

/** Tiles Sine makes that aren't on Home yet. */
@Composable
private fun AddTile(session: AccountSession, tiles: List<Tile>, onDone: () -> Unit) {
    val options = desktopDefaults(session).filterNot { t -> tiles.any { it.id == t.id } }
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.width(420.dp).clip(PhaseShape.Floating).background(PhaseColor.Float).border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add a tile", color = PhaseColor.Text1, style = PhaseType.Title)
            if (options.isEmpty()) Text("Every tile Sine makes is on Home. Pin albums, artists and playlists from their pages.", color = PhaseColor.Text2, style = PhaseType.Body)
            for (t in options) {
                Row(
                    Modifier.fillMaxWidth().clip(PhaseShape.Pill).clickable { session.editHome(HomeEdits.add(tiles, t)); onDone() }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhaseIcon(PhaseIcons.Plus, null, size = 20.dp, tone = IconTone.Sine)
                    Text(tileLabel(t), color = PhaseColor.Text1, style = PhaseType.Name)
                }
            }
            Pill("Done", onDone, kind = PillKind.Quiet, height = 44.dp)
        }
    }
}

private fun tileLabel(t: Tile): String = when (t.kind) {
    TileKind.NOW_PLAYING -> "Now playing"
    TileKind.SHUFFLE_ALL -> "Shuffle all"
    TileKind.DOWNLOADED -> "On this computer"
    TileKind.REVIEW -> "Review"
    TileKind.INGEST -> "Adding"
    TileKind.RESUME -> "Pick up"
    TileKind.STATS -> "Stats"
    TileKind.JAM -> "Jam"
    else -> t.name ?: t.kind
}
