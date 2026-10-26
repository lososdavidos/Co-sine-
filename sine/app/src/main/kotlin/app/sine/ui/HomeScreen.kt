package app.sine.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.sine.core.cosine.IngestJob
import app.sine.core.home.HomeDefaults
import app.sine.core.home.HomeEdits
import app.sine.core.home.Placed
import app.sine.core.home.Tile
import app.sine.core.home.TileGrid
import app.sine.core.home.TileKind
import app.sine.core.home.TileSize
import app.sine.core.model.Track
import app.sine.core.server.Capability
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.graph
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.PlayingBars
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Home (§6.4): a canvas of tiles, the person's to arrange. Four columns;
 * tiles small, medium or wide; each shows something of theirs and keeps it
 * current, and whatever is inside a tile may slide, roll or fade while it
 * changes. The tile itself stays where it is until they move it.
 */

@Composable
fun HomeScreen(session: AccountSession, nav: Pages) {
    val saved by session.home.layout.collectAsStateWithLifecycle()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val account by session.accountFlow.collectAsStateWithLifecycle()
    // Until the person arranges something, the defaults, worked out afresh from what's here.
    val tiles = saved?.tiles ?: remember(mirrored, account) { defaultTiles(session) }
    var selected by remember { mutableStateOf<String?>(null) }
    val chosen = tiles.firstOrNull { it.id == selected }
    val edit: ((List<Tile>) -> List<Tile>) -> Unit = { change -> session.editHome(change(tiles)) }

    ShowSelection(chosen?.let { tile -> tileSelection(tile, tiles, edit, onReselect = { selected = it }) { selected = null } })
    Column(Modifier.fillMaxSize()) {
        TitleBar("Home") {
            Pill(
                null, { nav.form { AddTileForm(session) } }, kind = PillKind.Quiet, icon = PhaseIcons.Plus,
                height = 40.dp, width = 44.dp, contentDescription = "Add a tile",
            )
        }
        TileCanvas(
            tiles, selected,
            onDrop = { tile, index -> edit { HomeEdits.move(it, tile.id, index) } },
        ) { tile, order, modifier ->
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            TileFrame(
                tile, selected = tile.id == selected, modifier = modifier,
                onTap = {
                    when (selected) {
                        null -> scope.launch { tap(context, session, nav, tile) }
                        tile.id -> selected = null
                        else -> selected = tile.id
                    }
                },
                onLongPress = { selected = tile.id },
            ) { TileFace(session, tile, order) }
        }
    }
}

private fun defaultTiles(session: AccountSession): List<Tile> {
    val caps = session.account.kind.capabilities
    return HomeDefaults.tiles(
        smart = session.mirroredSmart(),
        mirrored = session.libraryTracks() != null,
        review = session.cosine != null && Capability.REVIEW in caps,
        ingest = session.cosine != null && Capability.INGEST in caps,
    )
}

/** What a tile is called where it isn't showing its own name: in the shelf, and to a screen reader. */
private fun tileLabel(tile: Tile) = when (tile.kind) {
    TileKind.NOW_PLAYING -> "Now playing"
    TileKind.SHUFFLE_ALL -> "Shuffle all"
    TileKind.DOWNLOADED -> "On this phone"
    TileKind.REVIEW -> "Review"
    TileKind.INGEST -> "Adding"
    else -> tile.name ?: "Tile"
}

/** A selected tile's actions (§6.4.4): resize, move one place either way, how it opens, unpin. Dragging moves it too. */
private fun tileSelection(
    tile: Tile,
    tiles: List<Tile>,
    edit: ((List<Tile>) -> List<Tile>) -> Unit,
    onReselect: (String) -> Unit,
    onClear: () -> Unit,
): ShelfSelection {
    val i = tiles.indexOfFirst { it.id == tile.id }
    val actions = buildList {
        add(ShelfAction("Resize", icon = PhaseIcons.Resize) { edit { HomeEdits.resize(it, tile.id) } })
        if (i > 0) add(ShelfAction("Earlier", icon = PhaseIcons.Earlier) { edit { HomeEdits.move(it, tile.id, i - 1) } })
        if (i < tiles.lastIndex) add(ShelfAction("Later", icon = PhaseIcons.Later) { edit { HomeEdits.move(it, tile.id, i + 1) } })
        val other = tile.copy(shuffle = !tile.shuffle)
        if (TileKind.pinned(tile.kind) && tiles.none { it.id == other.id }) {
            add(ShelfAction(if (tile.shuffle) "Open on tap" else "Shuffle on tap", icon = if (tile.shuffle) PhaseIcons.Open else PhaseIcons.Shuffle) {
                edit { list -> list.map { if (it.id == tile.id) other else it } }
                onReselect(other.id)
            })
        }
        add(ShelfAction("Unpin", icon = PhaseIcons.Unpin) {
            edit { HomeEdits.remove(it, tile.id) }
            onClear()
        })
    }
    return ShelfSelection(tileLabel(tile), actions, onClear, note = "Drag it to move it.")
}

// ---------------------------------------------------------------- the grid

private val Gap = 8.dp
private val Side = 12.dp

/**
 * The tiles in four columns (§6.4.2), placed in order where each first
 * fits. A tile that moves or changes size goes there on the 160ms move
 * (§6.16); a selected one follows the finger and drops where it's let go.
 */
@Composable
private fun TileCanvas(
    tiles: List<Tile>,
    selected: String?,
    onDrop: (Tile, Int) -> Unit,
    tile: @Composable (tile: Tile, order: Int, modifier: Modifier) -> Unit,
) {
    val placed = TileGrid.place(tiles)
    val rows = TileGrid.height(placed)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = (maxWidth - Side * 2 - Gap * (TileGrid.COLUMNS - 1)) / TileGrid.COLUMNS
        val step = unit + Gap
        val stepPx = with(LocalDensity.current) { step.toPx() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Side, vertical = 4.dp).height(step * rows - Gap)) {
                placed.forEachIndexed { order, p ->
                    key(p.tile.id) {
                        val move = tween<androidx.compose.ui.unit.Dp>(160)
                        val x by animateDpAsState(step * p.column, move, label = "x")
                        val y by animateDpAsState(step * p.row, move, label = "y")
                        val w by animateDpAsState(step * p.tile.size.columns - Gap, move, label = "w")
                        val h by animateDpAsState(step * p.tile.size.rows - Gap, move, label = "h")
                        var drag by remember { mutableStateOf(Offset.Zero) }
                        val dragging = p.tile.id == selected
                        tile(
                            p.tile, order,
                            Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .offset(x, y)
                                .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                                .size(w, h)
                                .then(
                                    if (!dragging) Modifier else Modifier.pointerInput(p.tile.id) {
                                        detectDragGestures(
                                            onDragEnd = {
                                                dropAt(placed, p, drag, stepPx)?.let { onDrop(p.tile, tiles.indexOf(it)) }
                                                    ?: run { if (pastEnd(placed, p, drag, stepPx)) onDrop(p.tile, tiles.size) }
                                                drag = Offset.Zero
                                            },
                                            onDragCancel = { drag = Offset.Zero },
                                        ) { change, delta ->
                                            change.consume()
                                            drag += delta
                                        }
                                    },
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(LocalShelf.current.reserve))
        }
    }
}

/** The cell under the middle of [p] once dragged by [drag]: the tile there, if it's another. */
private fun dropAt(placed: List<Placed>, p: Placed, drag: Offset, step: Float): Tile? {
    val (column, row) = cellUnder(p, drag, step)
    return placed.firstOrNull { q ->
        q !== p && column in q.column until q.column + q.tile.size.columns && row in q.row until q.row + q.tile.size.rows
    }?.tile
}

private fun pastEnd(placed: List<Placed>, p: Placed, drag: Offset, step: Float) = cellUnder(p, drag, step).second >= TileGrid.height(placed)

private fun cellUnder(p: Placed, drag: Offset, step: Float): Pair<Int, Int> {
    val cx = (p.column + p.tile.size.columns / 2f) * step + drag.x
    val cy = (p.row + p.tile.size.rows / 2f) * step + drag.y
    return (cx / step).toInt().coerceIn(0, TileGrid.COLUMNS - 1) to (cy / step).toInt().coerceAtLeast(0)
}

/** A tile's slab: tap does what it's for, a long press selects it, and selected it wears Sine (§6.1). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(
    tile: Tile,
    selected: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(PhaseShape.Slab)
            .background(PhaseColor.Raised)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress, onLongClickLabel = "Select")
            .semantics { contentDescription = tileLabel(tile) },
    ) {
        content()
        if (selected) Box(Modifier.matchParentSize().border(3.dp, PhaseColor.Sine, PhaseShape.Slab))
    }
}

// ---------------------------------------------------------------- what a tap does

private suspend fun tap(context: Context, session: AccountSession, nav: Pages, tile: Tile) {
    val player = context.graph.player
    val ref = tile.ref
    if (tile.shuffle && ref != null) {
        val tracks = runCatching { tracksOf(session, tile.kind, ref) }.getOrNull().orEmpty()
        if (tracks.isEmpty()) say(Voice.TRACK_UNAVAILABLE) else player.play(session, tracks, shuffle = true)
        return
    }
    when (tile.kind) {
        TileKind.ARTIST -> ref?.let { nav.navigate(Routes.artist(it)) }
        TileKind.ALBUM -> ref?.let { nav.navigate(Routes.album(it)) }
        TileKind.PLAYLIST -> ref?.let { nav.navigate(Routes.playlist(it)) }
        TileKind.DOWNLOADED -> nav.navigate(Routes.DOWNLOADED)
        TileKind.REVIEW -> nav.navigate(Routes.REVIEW)
        TileKind.INGEST -> nav.navigate(Routes.add())
        TileKind.SHUFFLE_ALL -> {
            val all = session.libraryTracks() ?: session.offline().all()
            if (all.isEmpty()) say(Voice.NO_DOWNLOADS) else player.play(session, all, shuffle = true)
        }
        TileKind.NOW_PLAYING -> {
            if (player.state.value.current != null) return player.togglePlay()
            // Nothing loaded: pick up what played last, where it got to (§6.4.5).
            val last = player.last.value?.takeIf { it.account == session.account.id } ?: return
            try {
                player.play(session, listOf(session.track(last.trackId).value), startMs = last.positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(Voice.TRACK_UNAVAILABLE)
            }
        }
    }
}

private suspend fun tracksOf(session: AccountSession, kind: String, ref: String): List<Track> = when (kind) {
    TileKind.ALBUM -> session.album(ref).value.tracks
    TileKind.PLAYLIST -> session.playlist(ref).value.tracks
    TileKind.ARTIST -> session.artist(ref).value.albums.flatMap { a -> session.knownTracks(a, fromDownloads = false) ?: session.album(a.id).value.tracks }
    else -> emptyList()
}

// ---------------------------------------------------------------- faces

/** What a tile of something in the library shows: covers to go through, its name, a second line. */
private class Face(val covers: List<Any?>, val name: String, val line: String?)

@Composable
private fun TileFace(session: AccountSession, tile: Tile, order: Int) {
    when (tile.kind) {
        TileKind.NOW_PLAYING -> NowPlayingFace(session, tile.size, order)
        TileKind.SHUFFLE_ALL -> {
            val mirrored by session.mirrored.collectAsStateWithLifecycle()
            val n = remember(mirrored) { session.libraryTracks()?.size ?: session.offline().all().size }
            QuietFace(tile.size, PhaseIcons.Shuffle, "Shuffle all", Voice.songs(n))
        }
        TileKind.DOWNLOADED -> DownloadedFace(session, tile.size, order)
        TileKind.REVIEW -> ReviewFace(session, tile.size)
        TileKind.INGEST -> IngestFace(session, tile.size)
        else -> {
            val mirrored by session.mirrored.collectAsStateWithLifecycle()
            val changed by session.playlistsChanged.collectAsStateWithLifecycle()
            val face by produceState<Face?>(null, tile.id, mirrored, changed) { value = face(session, tile) }
            val f = face
            // Until it's loaded, or when it can't be (gone, or the server's needed): its name as pinned.
            if (f == null) QuietFace(tile.size, if (tile.shuffle) PhaseIcons.Shuffle else PhaseIcons.Wave, tile.name ?: "", null)
            else ArtFace(f.covers, f.name, f.line, tile.size, order, label = if (tile.shuffle) "Shuffle" else null)
        }
    }
}

private suspend fun face(session: AccountSession, tile: Tile): Face? {
    val ref = tile.ref ?: return null
    return try {
        when (tile.kind) {
            TileKind.ARTIST -> session.artist(ref).value.let { d ->
                Face((listOf(d.artist.coverArt) + d.albums.map { it.coverArt }).filterNotNull().distinct().map { session.artwork(it) }, d.artist.name, d.artist.subtitle())
            }
            TileKind.ALBUM -> session.album(ref).value.let { d -> Face(listOf(session.artwork(d.album.coverArt)), d.album.name, d.album.subtitle()) }
            TileKind.PLAYLIST -> session.playlist(ref).value.let { d ->
                val covers = d.tracks.mapNotNull { it.coverArt }.distinct().take(COVERS).map { session.artwork(it) }
                Face(covers, d.playlist.name, d.playlist.comment?.takeIf { it.isNotBlank() } ?: Voice.songs(d.playlist.trackCount))
            }
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** At most this many covers go round on one tile. */
private const val COVERS = 12

/**
 * Artwork as the tile's ground (§6.4.3), darkened at a constant floor under
 * the words, never sampled for a colour. Small and medium tiles are the
 * cover; a wide one has the cover whole at its left on its own ambience.
 * The covers take turns, each sliding in over the last.
 */
@Composable
private fun ArtFace(
    covers: List<Any?>,
    name: String,
    line: String?,
    size: TileSize,
    order: Int,
    label: String? = null,
    mark: (@Composable () -> Unit)? = null,
) {
    val turn = rememberTurn(covers.size, order)
    val cover = covers.getOrNull(turn)
    if (size == TileSize.Wide) {
        Box(Modifier.fillMaxSize()) {
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(cover).size(24).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
                )
                Box(Modifier.matchParentSize().background(PhaseColor.Scrim))
            }
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(PhaseShape.Square).background(PhaseColor.Lift)) {
                    Covers(cover)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                    if (label != null) PhaseLabel(label, color = PhaseColor.OnScrim)
                    FadingText(name, PhaseType.Title, PhaseColor.Text1, lines = 2)
                    if (line != null) FadingText(line, PhaseType.Small, PhaseColor.OnScrim, lines = 1)
                    mark?.let { Spacer(Modifier.height(6.dp)); it() }
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Covers(cover)
        if (size == TileSize.Small) {
            mark?.let { Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { it() } }
            return@Box
        }
        // A constant floor under the words, whatever the cover (§6.4.3).
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, PhaseColor.Ground.copy(alpha = 0.88f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            if (label != null) PhaseLabel(label, color = PhaseColor.OnScrim)
            FadingText(name, PhaseType.Name.copy(fontWeight = FontWeight.SemiBold), PhaseColor.Text1, lines = 2)
            if (line != null) FadingText(line, PhaseType.Small.copy(fontSize = 12.sp), PhaseColor.OnScrim, lines = 1)
            mark?.let { Spacer(Modifier.height(4.dp)); it() }
        }
    }
}

/** The cover, and the next one sliding in over it from the right. */
@Composable
private fun Covers(cover: Any?) {
    AnimatedContent(
        targetState = cover,
        transitionSpec = { slideInHorizontally(tween(700)) { it } togetherWith ExitTransition.KeepUntilTransitionsFinished },
        label = "cover",
        modifier = Modifier.fillMaxSize(),
    ) { c ->
        if (c != null) AsyncImage(model = c, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(PhaseColor.Raised))
    }
}

/** Words that fade through to new ones. */
@Composable
private fun FadingText(text: String, style: TextStyle, color: Color, lines: Int) {
    Crossfade(text, animationSpec = tween(400), label = "text") {
        Text(it, color = color, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}

/** A number that rolls over to the next, like a counter's drum. */
@Composable
private fun RollingCount(n: Int, style: TextStyle, color: Color) {
    AnimatedContent(
        targetState = n,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut()) using
                SizeTransform(clip = true)
        },
        label = "count",
    ) { Text(it.toString(), color = color, style = style) }
}

/**
 * Which of [count] covers a tile shows: the next every 8 to 10 seconds,
 * staggered by its place so neighbours don't change together, and only
 * while Sine is in front (§6.4).
 */
@Composable
private fun rememberTurn(count: Int, order: Int): Int {
    var turn by remember(count) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(count, order) {
        if (count < 2) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(2_000L + (order * 1_700L) % 8_000L)
            while (true) {
                turn = (turn + 1) % count
                delay(8_000L + Random.nextLong(2_000L))
            }
        }
    }
    return turn
}

/** A tile with no artwork (§6.4.3): a raised slab, a mark, its name; a signal only where it means something. */
@Composable
private fun QuietFace(
    size: TileSize,
    icon: ImageVector?,
    name: String,
    line: String?,
    corner: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(if (size == TileSize.Small) 10.dp else 14.dp)) {
        if (icon != null) PhaseIcon(icon, null, size = if (size == TileSize.Small) 26.dp else 30.dp)
        corner?.let { Box(Modifier.align(Alignment.TopEnd)) { it() } }
        if (size == TileSize.Small) {
            // A label, as Phase sets them, but on two lines: a small tile is narrow.
            Text(name.uppercase(Locale.ROOT), Modifier.align(Alignment.BottomStart), color = PhaseColor.Text2, style = PhaseType.Label, maxLines = 2)
            return@Box
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            FadingText(name, if (size == TileSize.Wide) PhaseType.Title else PhaseType.Name.copy(fontWeight = FontWeight.SemiBold), PhaseColor.Text1, lines = 2)
            if (line != null) FadingText(line, PhaseType.Small.copy(fontSize = 12.sp), PhaseColor.Text2, lines = 2)
            below?.let { Spacer(Modifier.height(6.dp)); it() }
        }
    }
}

@Composable
private fun NowPlayingFace(session: AccountSession, size: TileSize, order: Int) {
    val player = LocalContext.current.graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val last by player.last.collectAsStateWithLifecycle()
    val item = state.current
    val playing = state.isPlaying
    when {
        item != null -> ArtFace(
            listOf(item.mediaMetadata.artworkUri), item.mediaMetadata.title?.toString().orEmpty(),
            item.mediaMetadata.artist?.toString(), size, order,
            label = if (playing) "Playing" else "Paused",
            mark = if (playing) ({ PlayingBars() }) else null,
        )
        // Nothing loaded: what played last, ready to pick up (§6.4.5).
        last?.account == session.account.id -> {
            val l = last!!
            ArtFace(listOf(l.art), l.title, l.artist, size, order, label = "Last played")
        }
        else -> QuietFace(size, PhaseIcons.Play, "Now playing", "Nothing played yet.")
    }
}

@Composable
private fun DownloadedFace(session: AccountSession, size: TileSize, order: Int) {
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val covers = remember(downloads) {
        session.offline().albums().mapNotNull { it.coverArt }.distinct().take(COVERS).map { session.artwork(it) }
    }
    if (covers.isEmpty()) QuietFace(size, PhaseIcons.OnPhone, "On this phone", Voice.NO_DOWNLOADS)
    else ArtFace(covers, "On this phone", Voice.songs(downloads.size), size, order)
}

/** The review queue's count is a Cosine pill; nothing to review is a resting state, plain (§6.12). */
@Composable
private fun ReviewFace(session: AccountSession, size: TileSize) {
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    var waiting by remember { mutableStateOf<Int?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mirrored) {
        val cosine = session.cosine ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { cosine.reviewQueue(limit = 1).total }.getOrNull()?.let { waiting = it }
                delay(60_000)
            }
        }
    }
    val n = waiting ?: 0
    QuietFace(
        size, null, "Review",
        line = when {
            waiting == null -> null
            n == 0 -> "Nothing to look at."
            n == 1 -> "1 song to look at."
            else -> "$n songs to look at."
        },
        corner = {
            Box(
                Modifier
                    .clip(PhaseShape.Pill)
                    .background(if (n > 0) PhaseColor.Cosine else PhaseColor.Lift)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) { RollingCount(n, PhaseType.Data.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), if (n > 0) PhaseColor.OnCosine else PhaseColor.Text2) }
        },
    )
}

/** What Cosine is fetching (§6.10): its progress in Cosine, a failure in red, nothing doing at rest. */
@Composable
private fun IngestFace(session: AccountSession, size: TileSize) {
    var jobs by remember { mutableStateOf<List<IngestJob>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        val cosine = session.cosine ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { cosine.jobs(limit = 20) }.getOrNull()?.let { jobs = it }
                delay(if (jobs.orEmpty().any { it.isActive }) 3_000 else 30_000)
            }
        }
    }
    val active = jobs.orEmpty().filter { it.isActive }
    val failed = jobs.orEmpty().count { it.isFailed }
    val now = active.firstOrNull()
    val progress by animateFloatAsState(now?.progress?.toFloat() ?: 0f, tween(400), label = "progress")
    QuietFace(
        size, PhaseIcons.Plus, if (active.isEmpty()) "Add" else "Adding",
        line = when {
            now != null -> now.title
            jobs == null -> null
            else -> "Nothing being added."
        },
        corner = if (active.size > 1 || (size == TileSize.Small && active.isNotEmpty())) ({
            Box(Modifier.clip(PhaseShape.Pill).background(PhaseColor.Cosine).padding(horizontal = 10.dp, vertical = 2.dp)) {
                RollingCount(active.size, PhaseType.Data.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), PhaseColor.OnCosine)
            }
        }) else null,
        below = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (now != null) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(PhaseShape.Pill).background(PhaseColor.Line)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(4.dp).background(PhaseColor.Cosine))
                    }
                }
                if (failed > 0) StatusLine(if (failed == 1) "1 didn't come." else "$failed didn't come.", signal = Signal.Fault, color = PhaseColor.Text2)
            }
        },
    )
}

// ---------------------------------------------------------------- adding tiles

/**
 * Generated tiles and smart playlists, added from Home (§6.4.1). Pinned
 * items come from their own pages; Resume, Jam and Favourites wait for
 * their features.
 */
@Composable
internal fun AddTileForm(session: AccountSession) {
    val pages = LocalPages.current
    val saved by session.home.layout.collectAsStateWithLifecycle()
    val tiles = saved?.tiles ?: remember { defaultTiles(session) }
    val caps = session.account.kind.capabilities
    val made = listOfNotNull(
        Tile(TileKind.NOW_PLAYING, size = TileSize.Wide),
        Tile(TileKind.SHUFFLE_ALL, size = TileSize.Small),
        Tile(TileKind.DOWNLOADED),
        Tile(TileKind.REVIEW, size = TileSize.Small).takeIf { session.cosine != null && Capability.REVIEW in caps },
        Tile(TileKind.INGEST).takeIf { session.cosine != null && Capability.INGEST in caps },
    ).filter { t -> tiles.none { it.id == t.id } }
    val smart = session.mirroredSmart().map { Tile(TileKind.PLAYLIST, it.id, name = it.name) }.filter { t -> tiles.none { it.id == t.id } }
    fun add(tile: Tile) {
        session.editHome(HomeEdits.add(tiles, tile))
        say("${tileLabel(tile)} is on Home.")
        pages.popBackStack()
    }
    FormPage("Add a tile") {
        if (made.isEmpty() && smart.isEmpty()) {
            Text("Everything Sine can make is on Home already.", color = PhaseColor.Text2, style = PhaseType.Body)
        }
        if (made.isNotEmpty()) PhaseLabel("Made by Sine", Modifier.padding(top = 8.dp))
        for (t in made) AddTileRow(tileLabel(t)) { add(t) }
        if (smart.isNotEmpty()) PhaseLabel("Your smart playlists", Modifier.padding(top = 8.dp))
        for (t in smart) AddTileRow(t.name.orEmpty()) { add(t) }
        Text("To pin an artist, album or playlist, use Pin to Home on its page.", color = PhaseColor.Text3, style = PhaseType.Small)
    }
}

@Composable
private fun AddTileRow(name: String, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PhaseShape.Square)
            .clickable(role = Role.Button, onClickLabel = "Add to Home", onClick = onAdd)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, Modifier.weight(1f), color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PhaseIcon(PhaseIcons.Plus, null, tone = IconTone.Dim)
    }
}

/** Pins what a page is about to Home, or takes it off (§6.4.1): a quiet action in its header. */
@Composable
internal fun PinToHome(session: AccountSession, kind: String, id: String, name: String) {
    val saved by session.home.layout.collectAsStateWithLifecycle()
    val tiles = saved?.tiles ?: remember { defaultTiles(session) }
    val on = tiles.filter { it.kind == kind && it.ref == id }
    if (on.isEmpty()) {
        Pill(null, {
            session.editHome(HomeEdits.add(tiles, Tile(kind, id, name = name)))
            say("$name is on Home.")
        }, kind = PillKind.Quiet, icon = PhaseIcons.Pin, height = 40.dp, contentDescription = "Pin to Home")
    } else {
        Pill(null, {
            session.editHome(tiles.filterNot { it.kind == kind && it.ref == id })
            say("$name is off Home.")
        }, kind = PillKind.Quiet, icon = PhaseIcons.Unpin, height = 40.dp, contentDescription = "Unpin from Home")
    }
}

/** What's on this phone, as one list (§6.14): the Downloaded tile opens it. */
@Composable
fun DownloadedScreen(session: AccountSession, nav: Pages) {
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val load = rememberLoad(session, refreshOn = downloads) { Sourced(session.offline().all(), true) }
    LoadContent(load) { tracks, _ ->
        TrackListScreen(
            session = session,
            title = "On this phone",
            kind = "Downloads",
            coverArt = tracks.firstNotNullOfOrNull { it.coverArt },
            tracks = tracks,
            node = null,
            showTrackArtist = true,
            nav = nav,
            empty = Voice.NO_DOWNLOADS,
        )
    }
}
