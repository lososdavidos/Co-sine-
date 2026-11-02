package app.sine.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.sine.core.model.Playlist
import app.sine.core.model.Track
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.desktop.DesktopGraph
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.PlayingBars
import app.sine.ui.phase.Signal
import app.sine.ui.phase.accentFill
import app.sine.ui.phase.rowInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a selection of songs can have done to it, beyond what every list offers. */
class SongExtras(
    /** Takes the songs at these places out of the list's playlist. */
    val removeFromPlaylist: ((List<Int>) -> Unit)? = null,
)

/**
 * Songs you can select the desktop's way (§6.15): a click picks one,
 * Ctrl-click adds or takes away, Shift-click a run. A double click plays
 * from there. Right-click, or the menu key, offers what the phone's
 * selection shelf offers; the same keys work while the list has focus:
 * Enter plays, Q queues, Ctrl+A picks all, Esc lets go.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun SongTable(
    session: AccountSession,
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    numbered: Boolean = false,
    showArt: Boolean = true,
    extras: SongExtras = SongExtras(),
    header: LazyListScope.() -> Unit = {},
) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val say = LocalSay.current
    val scope = rememberCoroutineScope()
    val player by graph.player.state.collectAsState()
    val downloaded by session.downloads.tracks.collectAsState()
    val liking by session.liking.collectAsState()
    val selected = remember(tracks) { mutableStateListOf<Int>() }
    var anchor by remember(tracks) { mutableStateOf(0) }
    var menuAt by remember { mutableStateOf<Pair<Int, DpOffset>?>(null) }
    var choosingPlaylist by remember { mutableStateOf<List<Track>?>(null) }
    var removing by remember { mutableStateOf<List<Track>?>(null) }
    val focus = remember { FocusRequester() }
    var lastClick by remember { mutableStateOf(0L to -1) }
    val density = LocalDensity.current

    fun chosen(): List<Track> = selected.sorted().mapNotNull { tracks.getOrNull(it) }
    fun playFrom(i: Int) = graph.player.play(session, tracks, i)

    val actions = Actions(graph, session, scope, say, nav)

    LazyColumn(
        modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val command = e.isCtrlPressed || e.isMetaPressed
                when {
                    command && e.key == Key.A -> { selected.clear(); selected.addAll(tracks.indices); true }
                    e.key == Key.Escape && selected.isNotEmpty() -> { selected.clear(); true }
                    e.key == Key.Enter && selected.isNotEmpty() -> { actions.play(chosen()); true }
                    e.key == Key.Q && !command && selected.isNotEmpty() -> { actions.queue(chosen()); true }
                    e.key == Key.Menu && selected.isNotEmpty() -> { menuAt = selected.last() to DpOffset(120.dp, 0.dp); true }
                    else -> false
                }
            },
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 48.dp),
    ) {
        header()
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
            val isSelected = i in selected
            val playing = player.current?.id == t.id && player.jam == null
            val liked = liking[t.id] ?: t.liked
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (showArt) 64.dp else 52.dp)
                        .then(if (isSelected) Modifier.accentFill(PhaseShape.Square) else Modifier)
                        .onPointerEvent(PointerEventType.Press) { e ->
                            runCatching { focus.requestFocus() }
                            val m = e.keyboardModifiers
                            val at = e.changes.first().position
                            if (e.buttons.isSecondaryPressed) {
                                if (i !in selected) { selected.clear(); selected.add(i); anchor = i }
                                menuAt = i to with(density) { DpOffset(at.x.toDp(), at.y.toDp() - 64.dp) }
                                return@onPointerEvent
                            }
                            val now = System.currentTimeMillis()
                            if (lastClick.second == i && now - lastClick.first < DOUBLE_CLICK_MS) {
                                playFrom(i)
                                selected.clear()
                                lastClick = 0L to -1
                                return@onPointerEvent
                            }
                            lastClick = now to i
                            when {
                                m.isShiftPressed -> {
                                    val range = if (anchor <= i) anchor..i else i..anchor
                                    selected.clear(); selected.addAll(range)
                                }
                                m.isCtrlPressed || m.isMetaPressed -> if (i in selected) selected.remove(i) else { selected.add(i); anchor = i }
                                else -> { selected.clear(); selected.add(i); anchor = i }
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterEnd) {
                        when {
                            isSelected -> PhaseIcon(PhaseIcons.Check, null, size = 18.dp, tone = IconTone.OnSine)
                            playing -> PlayingBars()
                            else -> Text(
                                ((if (numbered) t.trackNumber else null) ?: (i + 1)).toString(),
                                color = PhaseColor.Text3, style = PhaseType.Data,
                            )
                        }
                    }
                    if (showArt) Cover(session, t.coverArt, 44.dp, px = 150)
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title, color = rowInk(isSelected, false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = PhaseType.Name.copy(fontWeight = if (playing || isSelected) FontWeight.SemiBold else FontWeight.Medium),
                        )
                        if (showArt) {
                            Text(
                                listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                                color = rowInk(isSelected, false, secondary = true), style = PhaseType.Small,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (liked) PhaseIcon(PhaseIcons.Liked, "Liked", size = 16.dp, tone = if (isSelected) IconTone.OnSine else IconTone.Sine)
                    if (t.id in downloaded) PhaseIcon(PhaseIcons.OnPhone, "On this computer", size = 16.dp, tone = if (isSelected) IconTone.OnSine else IconTone.Faint)
                    Text(clock(t.durationSec * 1000L), color = rowInk(isSelected, false, secondary = true), style = PhaseType.Data)
                }
                val menu = menuAt
                if (menu != null && menu.first == i) {
                    SongMenu(
                        chosen(), menu.second,
                        onDismiss = { menuAt = null },
                        onPlay = { actions.play(it); selected.clear() },
                        onQueue = { actions.queue(it); selected.clear() },
                        onDownload = { actions.download(it); selected.clear() },
                        onRemoveDownload = { actions.removeDownload(it); selected.clear() },
                        onAddToPlaylist = if (session.canEditPlaylists) ({ choosingPlaylist = it }) else null,
                        onRemoveFromPlaylist = extras.removeFromPlaylist?.let { f -> { _: List<Track> -> f(selected.sorted()); selected.clear() } },
                        onRemoveFromLibrary = if (session.cosine != null) ({ removing = it }) else null,
                        onAlbum = t.albumId?.let { id -> { nav.go(Dest.Album(id, t.album)) } },
                        onArtist = t.artistId?.let { id -> { nav.go(Dest.Artist(id, t.artist)) } },
                        downloaded = downloaded.keys,
                    )
                }
            }
        }
    }

    choosingPlaylist?.let { songs ->
        PlaylistChooser(session, songs, onDone = { choosingPlaylist = null; selected.clear() })
    }
    removing?.let { songs ->
        Confirm(
            question = Voice.removeQuestion(songs.size),
            explained = Voice.removeExplained(songs.size),
            action = Voice.removeAction(songs.size),
            onConfirm = {
                removing = null
                selected.clear()
                scope.launch {
                    runCatching { session.remove(songs.map { it.id }) }
                        .onSuccess { say(Voice.removed(it)) }
                        .onFailure { say(it.message ?: "Cosine refused that.", Signal.Server) }
                }
            },
            onDismiss = { removing = null },
        )
    }
}

private const val DOUBLE_CLICK_MS = 400L

/** What can be done with songs, the same on every page. */
class Actions(
    private val graph: DesktopGraph,
    private val session: AccountSession,
    private val scope: CoroutineScope,
    private val say: Say,
    private val nav: Nav,
) {
    fun play(tracks: List<Track>) = graph.player.play(session, tracks, 0)

    /** Adds to the queue; in a jam, the queue is the jam's (§4C). */
    fun queue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val jam = graph.jam.jam.value
        val cosine = session.cosine
        if (jam != null && cosine != null) {
            scope.launch {
                try {
                    cosine.addToJam(jam.id, tracks.map { it.id })
                    graph.jam.refresh()
                    say(if (tracks.size == 1) "Added to the jam's queue." else "${tracks.size} songs added to the jam's queue.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("The jam didn't take it.", Signal.Server)
                }
            }
            return
        }
        graph.player.addToQueue(session, tracks)
        say(if (tracks.size == 1) "Added to the queue." else "${tracks.size} songs added to the queue.")
    }

    fun download(tracks: List<Track>) {
        graph.downloads.download(session, tracks)
        say(if (tracks.size == 1) "Downloading ${tracks[0].title}." else "Downloading ${tracks.size} songs.", Signal.Server)
    }

    fun removeDownload(tracks: List<Track>) {
        graph.downloads.remove(session, tracks.map { it.id })
        say(if (tracks.size == 1) "Removed from this computer." else "${tracks.size} songs removed from this computer.")
    }

    fun open(dest: Dest) = nav.go(dest)
}

@Composable
private fun SongMenu(
    songs: List<Track>,
    offset: DpOffset,
    onDismiss: () -> Unit,
    onPlay: (List<Track>) -> Unit,
    onQueue: (List<Track>) -> Unit,
    onDownload: (List<Track>) -> Unit,
    onRemoveDownload: (List<Track>) -> Unit,
    onAddToPlaylist: ((List<Track>) -> Unit)?,
    onRemoveFromPlaylist: ((List<Track>) -> Unit)?,
    onRemoveFromLibrary: ((List<Track>) -> Unit)?,
    onAlbum: (() -> Unit)?,
    onArtist: (() -> Unit)?,
    downloaded: Set<String>,
) {
    DropdownMenu(
        expanded = true, onDismissRequest = onDismiss, offset = offset,
        containerColor = PhaseColor.Float, shape = PhaseShape.Floating,
        modifier = Modifier.border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating).width(280.dp),
    ) {
        PhaseLabel(if (songs.size == 1) "1 selected" else "${songs.size} selected", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        fun go(f: (List<Track>) -> Unit): () -> Unit = { onDismiss(); f(songs) }
        MenuItem("Play", PhaseIcons.Play, IconTone.Sine, "Enter", go(onPlay))
        MenuItem("Add to queue", PhaseIcons.QueueAdd, IconTone.SineFar, "Q", go(onQueue))
        if (songs.any { it.id !in downloaded }) MenuItem("Download", PhaseIcons.OnPhone, IconTone.Sine, null, go(onDownload))
        if (songs.any { it.id in downloaded }) MenuItem("Remove download", PhaseIcons.OffPhone, IconTone.SineFar, null, go(onRemoveDownload))
        onAddToPlaylist?.let { MenuItem("Add to playlist", PhaseIcons.PlaylistAdd, IconTone.SineFar, null, go(it)) }
        if (songs.size == 1) {
            onAlbum?.let { MenuItem("Go to album", PhaseIcons.Album, IconTone.Dim, null, onClick = { onDismiss(); it() }) }
            onArtist?.let { MenuItem("Go to artist", PhaseIcons.Artist, IconTone.Dim, null, onClick = { onDismiss(); it() }) }
        }
        if (onRemoveFromPlaylist != null || onRemoveFromLibrary != null) {
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = PhaseColor.LineFloat)
            onRemoveFromPlaylist?.let { MenuItem("Remove from playlist", PhaseIcons.PlaylistRemove, IconTone.Fault, null, go(it), fault = true) }
            onRemoveFromLibrary?.let { MenuItem("Remove from library", PhaseIcons.Trash, IconTone.Fault, null, go(it), fault = true) }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tone: IconTone, key: String?, onClick: () -> Unit, fault: Boolean = false) {
    DropdownMenuItem(
        text = { Text(text, color = if (fault) PhaseColor.Fault else PhaseColor.Text1, style = PhaseType.Name) },
        onClick = onClick,
        leadingIcon = { PhaseIcon(icon, null, size = 20.dp, tone = tone) },
        trailingIcon = key?.let { { Text(it, color = PhaseColor.Text3, style = PhaseType.Data) } },
    )
}

/** Adds songs to a playlist: one of yours, or a new one. */
@Composable
fun PlaylistChooser(session: AccountSession, songs: List<Track>, onDone: () -> Unit) {
    val say = LocalSay.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val lists = rememberLoad(session) { session.playlists().value.filter { it.mine && !it.readonly && !it.isLiked && !it.isCosineSmart } }
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.width(420.dp).clip(PhaseShape.Floating).background(PhaseColor.Float).border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add to playlist", color = PhaseColor.Text1, style = PhaseType.Title)
            Text(Voice.songs(songs.size), color = PhaseColor.Text2, style = PhaseType.Small)
            Loaded(lists) { all ->
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(all, key = { _, p -> p.id }) { _, p: Playlist ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                onDone()
                                scope.launch {
                                    runCatching { session.addToPlaylist(p.id, songs.map { it.id }) }
                                        .onSuccess { say(Voice.addedToPlaylist(songs.size, p.name)) }
                                        .onFailure { say(it.message ?: "That didn't work.", Signal.Fault) }
                                }
                            }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PhaseIcon(PhaseIcons.Playlist, null, size = 20.dp, tone = IconTone.Dim)
                            Text(p.name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            PhaseField(name, { name = it }, Modifier.fillMaxWidth(), label = "New playlist")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    "Make it", {
                        val n = name.trim()
                        onDone()
                        scope.launch {
                            runCatching { session.createPlaylist(n, songs.map { it.id }) }
                                .onSuccess { say(Voice.addedToPlaylist(songs.size, n)) }
                                .onFailure { say(it.message ?: "That didn't work.", Signal.Fault) }
                        }
                    },
                    kind = PillKind.Primary, enabled = name.isNotBlank(), height = 44.dp,
                )
                Pill("Cancel", onDone, kind = PillKind.Quiet, height = 44.dp)
            }
        }
    }
}

/** Asks before something that can't be taken back. */
@Composable
fun Confirm(question: String, explained: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).clip(PhaseShape.Floating).background(PhaseColor.Float).border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(question, color = PhaseColor.Text1, style = PhaseType.Title)
            Text(explained, color = PhaseColor.Text2, style = PhaseType.Body)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(action, onConfirm, kind = PillKind.Fault, height = 44.dp)
                Pill("Cancel", onDismiss, kind = PillKind.Quiet, height = 44.dp)
            }
        }
    }
}
