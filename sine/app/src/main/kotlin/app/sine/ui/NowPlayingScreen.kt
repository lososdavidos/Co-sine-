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
 * Now playing (§6.6): a floating sheet with the cover's ambience at its top
 * and the queue beneath on the same surface — one surface at two heights,
 * never an overlay opening another (§6.5). The wave is the position.
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

    LaunchedEffect(state.current, state.isPlaying) {
        while (true) {
            positionMs = player.positionMs()
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(PhaseColor.Ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(PhaseColor.Float),
        ) {
            val item = state.current
            if (item == null) {
                Pill(null, onClose, Modifier.padding(16.dp), kind = PillKind.Quiet, icon = PhaseIcons.Down, height = 40.dp, contentDescription = "Close")
                Message("Nothing playing.")
                return@Column
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

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Ambience(meta.artworkUri, Modifier.fillMaxWidth(), fadeTo = PhaseColor.Float) {
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
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            meta.title?.toString().orEmpty(), color = PhaseColor.Text1, style = PhaseType.display(40.sp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(meta.artist?.toString().orEmpty(), color = PhaseColor.Text2, style = PhaseType.Body.copy(fontSize = 17.sp))
                            if (track?.liked == true) Tag(null, icon = PhaseIcons.Liked, contentDescription = "Liked")
                        }
                        val facts = listOfNotNull(
                            meta.trackNumber?.let { "Track $it" },
                            meta.releaseYear?.toString(),
                        )
                        if (facts.isNotEmpty()) PhaseLabel(facts.joinToString(" · "), Modifier.padding(top = 2.dp))
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
                            Text(Voice.formatDuration((shownMs / 1000).toInt()), color = PhaseColor.Text2, style = PhaseType.Data)
                            Text("−" + Voice.formatDuration(((duration - shownMs).coerceAtLeast(0) / 1000).toInt()), color = PhaseColor.Text2, style = PhaseType.Data)
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        Chip(null, state.shuffle, player::toggleShuffle, icon = PhaseIcons.Shuffle, contentDescription = "Shuffle")
                        Chip(
                            null, state.repeat != Player.REPEAT_MODE_OFF, player::toggleRepeat,
                            icon = if (state.repeat == Player.REPEAT_MODE_ONE) PhaseIcons.RepeatOne else PhaseIcons.Repeat,
                            contentDescription = when (state.repeat) {
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                Player.REPEAT_MODE_ONE -> "Repeat this song"
                                else -> "Repeat"
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
                            kind = PillKind.Quiet, icon = PhaseIcons.PlaylistAdd, height = 40.dp, width = 52.dp,
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
                    val numbers = numberColumn(state.queue.size)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(PhaseColor.Raised)) {
                        ItemRow(
                            title = q.mediaMetadata.title?.toString().orEmpty(),
                            subtitle = q.mediaMetadata.artist?.toString(),
                            art = null,
                            showArt = false,
                            playing = now,
                            leading = {
                                if (now) PlayingBars(Modifier.width(numbers))
                                else RowNumber(i + 1, numbers)
                            },
                            modifier = Modifier.padding(horizontal = 0.dp),
                        ) { player.skipTo(queued.at) }
                    }
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .background(PhaseColor.Raised)
                            .navigationBarsPadding()
                            .height(24.dp),
                    )
                }
            }
        }
    }
}
