package app.sine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.sine.core.model.Track
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.Ambience
import app.sine.ui.phase.Chip
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.PlayingBars
import app.sine.ui.phase.RowNumber
import app.sine.ui.phase.Tag
import app.sine.ui.phase.WaveScrubber
import app.sine.ui.phase.numberColumn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now playing (§6.6): a floating sheet in the cover's ambience, top to
 * bottom, with the queue beneath on the same surface — one surface at two
 * heights, never an overlay opening another (§6.5). The wave is the
 * position. Songs in the queue are held to select them, as anywhere; with
 * no shelf here, their actions float at the foot of the sheet.
 */
@Composable
fun NowPlayingScreen(session: AccountSession, nav: Pages, onClose: () -> Unit) {
    val context = LocalContext.current
    val player = context.graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    // Songs held in the queue, by their place in the player's list
    // ([app.sine.playback.Queued.at]); a change to the queue lets go of them.
    var selected by remember(state.queue) { mutableStateOf(setOf<Int>()) }

    LaunchedEffect(state.current, state.isPlaying) {
        while (true) {
            positionMs = player.positionMs()
            delay(500)
        }
    }

    /** The queue's songs as the library knows them, for what needs more than what's playing. */
    fun withTracks(ids: List<String>, then: (List<Track>) -> Unit) {
        scope.launch {
            val tracks = ids.mapNotNull { id -> runCatching { session.track(id).value }.getOrNull() }
            if (tracks.isEmpty()) say(Voice.TRACK_UNAVAILABLE) else then(tracks)
        }
    }

    Box(Modifier.fillMaxSize().background(PhaseColor.Ground)) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(PhaseColor.Float),
        ) {
            val item = state.current
            if (item == null) {
                Column {
                    Pill(null, onClose, Modifier.padding(16.dp), kind = PillKind.Quiet, icon = PhaseIcons.Down, height = 40.dp, contentDescription = "Close")
                    Message("Nothing playing.")
                }
                return@Box
            }
            val meta = item.mediaMetadata
            val duration = state.durationMs
            // The song as the library has it: to add to a playlist, and whether it's liked.
            var track by remember(item.mediaId) { mutableStateOf<Track?>(null) }
            LaunchedEffect(item.mediaId, mirrored) {
                track = runCatching { session.track(item.mediaId).value }.getOrNull()
            }
            val fraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val shown = scrub ?: fraction
            val shownMs = (shown * duration).toLong()

            // The cover's ambience behind the whole sheet, not just its top
            // (§6.7): the same scrim wherever there's text, so it reads over any cover.
            Ambience(meta.artworkUri, Modifier.matchParentSize(), fadeTo = PhaseColor.Float, fadeFrom = null)

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            Modifier
                                .padding(top = 10.dp)
                                .align(Alignment.CenterHorizontally)
                                .width(36.dp)
                                .height(4.dp)
                                .background(PhaseColor.Text1.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Pill(null, onClose, kind = PillKind.Floating, icon = PhaseIcons.Down, height = 40.dp, width = 48.dp, contentDescription = "Close")
                            Column(Modifier.weight(1f)) {
                                PhaseLabel("Playing from", color = PhaseColor.OnScrim)
                                Text(
                                    meta.albumTitle?.toString().orEmpty(), color = PhaseColor.Text1,
                                    style = PhaseType.Name.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // The Device marker (§6.6): where this is playing.
                            Tag(null, icon = PhaseIcons.Device, contentDescription = "Playing on this phone")
                        }
                        Art(meta.artworkUri, 350.dp, Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            meta.title?.toString().orEmpty(), color = PhaseColor.Text1, style = PhaseType.display(40.sp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(meta.artist?.toString().orEmpty(), color = PhaseColor.OnScrim, style = PhaseType.Body.copy(fontSize = 17.sp))
                            if (track?.liked == true) Tag(null, icon = PhaseIcons.Liked, contentDescription = "Liked")
                        }
                        val facts = listOfNotNull(
                            meta.trackNumber?.let { "Track $it" },
                            meta.releaseYear?.toString(),
                        )
                        if (facts.isNotEmpty()) PhaseLabel(facts.joinToString(" · "), Modifier.padding(top = 2.dp), color = PhaseColor.OnScrim)
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WaveScrubber(
                            fraction = shown,
                            onScrub = { scrub = it },
                            onScrubEnd = {
                                scrub?.let { player.seekTo((it * duration).toLong()) }
                                scrub = null
                            },
                            enabled = duration > 0,
                            description = "Position, ${Voice.formatDuration((shownMs / 1000).toInt())} of ${Voice.formatDuration((duration / 1000).toInt())}",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(Voice.formatDuration((shownMs / 1000).toInt()), color = PhaseColor.OnScrim, style = PhaseType.Data)
                            Text("−" + Voice.formatDuration(((duration - shownMs).coerceAtLeast(0) / 1000).toInt()), color = PhaseColor.OnScrim, style = PhaseType.Data)
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(null, player::previous, icon = PhaseIcons.Previous, height = 56.dp, width = 72.dp, contentDescription = "Previous")
                        Pill(
                            null, player::togglePlay, kind = PillKind.Primary,
                            icon = if (state.isPlaying) PhaseIcons.Pause else PhaseIcons.Play,
                            height = 56.dp, width = 104.dp, contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                        Pill(null, player::next, icon = PhaseIcons.Next, height = 56.dp, width = 72.dp, contentDescription = "Next")
                    }
                    // How it plays on, and keeping it: shuffle, repeat, add to a playlist.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        Chip(null, state.shuffle, player::toggleShuffle, icon = PhaseIcons.Shuffle, contentDescription = "Shuffle")
                        Chip(
                            null, state.repeat != Player.REPEAT_MODE_OFF, player::toggleRepeat,
                            icon = if (state.repeat == Player.REPEAT_MODE_ONE) PhaseIcons.RepeatOne else PhaseIcons.Repeat,
                            contentDescription = when (state.repeat) {
                                Player.REPEAT_MODE_ALL -> "Repeat: the queue"
                                Player.REPEAT_MODE_ONE -> "Repeat: this song"
                                else -> "Repeat: off"
                            },
                        )
                        // A tap adds the song to a playlist; two like it, or stop liking it (#34).
                        val song = track
                        val like = {
                            if (song != null) scope.launch {
                                val liked = !song.liked
                                attemptChange(context.applicationContext, session) {
                                    session.like(song.id, liked)
                                    track = song.copy(liked = liked)
                                    Voice.liked(song.title, liked)
                                }
                            }
                        }
                        Pill(
                            null,
                            { if (song != null) nav.form { AddToPlaylistForm(session, listOf(song)) } },
                            // Two taps can't be made with a screen reader: liking is an action of its own there.
                            Modifier.semantics {
                                customActions = listOf(CustomAccessibilityAction(if (song?.liked == true) "Stop liking" else "Like") { like(); true })
                            },
                            enabled = song != null && session.canEditPlaylists,
                            kind = PillKind.Outline, icon = PhaseIcons.PlaylistAdd, height = 40.dp, width = 52.dp,
                            contentDescription = "Add to playlist. Tap twice to like",
                            onDoubleClick = { like() },
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(PhaseColor.Raised)
                            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Up next", Modifier.weight(1f), color = PhaseColor.Text1, style = PhaseType.Name.copy(fontWeight = FontWeight.SemiBold))
                            val after = (state.queue.size - state.index - 1).coerceAtLeast(0)
                            PhaseLabel(if (after == 1) "1 more" else "$after more")
                        }
                    }
                }
                itemsIndexed(state.queue) { i, queued ->
                    val q = queued.item
                    val now = i == state.index
                    val on = queued.at in selected
                    val numbers = numberColumn(state.queue.size)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(PhaseColor.Raised)) {
                        ItemRow(
                            title = q.mediaMetadata.title?.toString().orEmpty(),
                            subtitle = q.mediaMetadata.artist?.toString(),
                            art = null,
                            showArt = false,
                            playing = now,
                            selected = on,
                            leading = {
                                if (now) PlayingBars(Modifier.width(numbers), color = if (on) PhaseColor.Ink else PhaseColor.Sine)
                                else RowNumber(i + 1, numbers, color = if (on) PhaseColor.Ink else PhaseColor.Text3)
                            },
                            modifier = Modifier.padding(horizontal = 0.dp),
                            onLongClick = { selected = selected.toggle(queued.at) },
                        ) {
                            if (selected.isNotEmpty()) selected = selected.toggle(queued.at) else player.skipTo(queued.at)
                        }
                    }
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .background(PhaseColor.Raised)
                            .navigationBarsPadding()
                            .height(if (selected.isEmpty()) 24.dp else 140.dp),
                    )
                }
            }

            if (selected.isNotEmpty()) {
                // In the order they play.
                val chosen = state.queue.filter { it.at in selected }
                val ids = chosen.map { it.item.mediaId }
                val actions = buildList {
                    add(ShelfAction("Play", icon = PhaseIcons.Play) { chosen.firstOrNull()?.let { player.skipTo(it.at) }; selected = emptySet() })
                    add(ShelfAction("Take out of the queue", icon = PhaseIcons.QueueRemove) {
                        player.removeFromQueue(chosen.map { it.at })
                        say(if (chosen.size == 1) "Taken out of the queue." else "${chosen.size} songs taken out of the queue.")
                        selected = emptySet()
                    })
                    if (session.canEditPlaylists) add(ShelfAction("Add to playlist", icon = PhaseIcons.PlaylistAdd) {
                        withTracks(ids) { t -> nav.form { AddToPlaylistForm(session, t) } }
                        selected = emptySet()
                    })
                }
                SelectionBar(
                    ShelfSelection(if (chosen.size == 1) "1 selected" else "${chosen.size} selected", actions, onClear = { selected = emptySet() }),
                    Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private fun Set<Int>.toggle(i: Int) = if (i in this) this - i else this + i
