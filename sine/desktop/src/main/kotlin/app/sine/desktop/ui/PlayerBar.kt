package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.desktop.player.Repeat
import app.sine.ui.phase.AmbientSource
import app.sine.ui.phase.Chip
import app.sine.ui.phase.LevelBar
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.WaveScrubber
import kotlinx.coroutines.launch

/** The player, docked along the bottom: what's playing, the controls and the wave, the device and the volume. */
@Composable
fun PlayerBar(session: AccountSession, showQueue: Boolean, toggleQueue: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val nav = LocalNav.current
    val say = LocalSay.current
    val player = graph.player
    val state by player.state.collectAsState()
    val jam by graph.jam.jam.collectAsState()
    val liking by session.liking.collectAsState()
    val scope = rememberCoroutineScope()
    val track = state.current
    var scrub by remember { mutableStateOf<Float?>(null) }

    // In a jam the controls are the jam's (§4C): the host's press moves everyone.
    fun jamDo(type: String) {
        val j = jam ?: return
        scope.launch {
            runCatching { session.cosine?.jamTransport(j.id, type); graph.jam.refresh() }
                .onFailure { say(it.message ?: "The jam didn't take it.", Signal.Server) }
        }
    }
    val previous = { if (jam != null) jamDo("previous") else player.previous() }
    val toggle = { if (jam != null) jamDo("toggle") else player.togglePlay() }
    val next = { if (jam != null) jamDo("next") else player.next() }

    Column(modifier.background(PhaseColor.Float)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PhaseColor.LineFloat))
        state.problem?.let { StatusLine(it, Modifier.padding(start = 20.dp, top = 10.dp), signal = Signal.Fault) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // What's playing.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (track != null) {
                    AmbientSource(session.artwork(track.coverArt, 300), weak = true)
                    Cover(session, track.coverArt, 56.dp)
                    Column(
                        Modifier.weight(1f, fill = false).clickable(enabled = track.albumId != null) {
                            track.albumId?.let { nav.go(Dest.Album(it, track.album)) }
                        },
                    ) {
                        Text(track.title, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (jam == null) {
                        val liked = liking[track.id] ?: session.isLiked(track.id)
                        Chip(
                            null, liked,
                            {
                                scope.launch {
                                    runCatching { session.like(track.id, !liked) }
                                        .onSuccess { say(Voice.liked(track.title, !liked)) }
                                        .onFailure { say("The server didn't take that.", Signal.Fault) }
                                }
                            },
                            icon = PhaseIcons.Liked, contentDescription = if (liked) "Liked" else "Like",
                        )
                    }
                } else {
                    Text("Nothing playing", color = PhaseColor.Text3, style = PhaseType.Small)
                }
            }

            // The controls, and where the song is: the wave.
            Column(Modifier.weight(1.6f).widthIn(max = 680.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (jam == null) Chip(null, state.queue.shuffle, player::toggleShuffle, icon = PhaseIcons.Shuffle, contentDescription = "Shuffle")
                    Pill(null, previous, icon = PhaseIcons.Previous, height = 48.dp, width = 72.dp, contentDescription = "Previous", enabled = track != null)
                    Pill(
                        null, toggle, kind = PillKind.Primary,
                        icon = if (state.isPlaying) PhaseIcons.Pause else PhaseIcons.Play,
                        height = 52.dp, width = 104.dp,
                        contentDescription = if (state.isPlaying) "Pause" else "Play", enabled = track != null,
                    )
                    Pill(null, next, icon = PhaseIcons.Next, height = 48.dp, width = 72.dp, contentDescription = "Next", enabled = track != null)
                    if (jam == null) {
                        Chip(
                            null, state.queue.repeat != Repeat.OFF, player::cycleRepeat,
                            icon = if (state.queue.repeat == Repeat.ONE) PhaseIcons.RepeatOne else PhaseIcons.Repeat,
                            contentDescription = when (state.queue.repeat) {
                                Repeat.ALL -> "Repeat: the queue"
                                Repeat.ONE -> "Repeat: this song"
                                Repeat.OFF -> "Repeat: off"
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val duration = state.durationMs.coerceAtLeast(1)
                    val fraction = scrub ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    Text(clock(scrub?.let { (it * duration).toLong() } ?: state.positionMs), color = PhaseColor.Text2, style = PhaseType.Data)
                    WaveScrubber(
                        fraction,
                        onScrub = { scrub = it },
                        onScrubEnd = {
                            scrub?.let { player.seekTo((it * duration).toLong()) }
                            scrub = null
                        },
                        modifier = Modifier.weight(1f),
                        enabled = track != null && jam == null,
                    )
                    Text(clock(state.durationMs), color = PhaseColor.Text2, style = PhaseType.Data)
                }
            }

            // Where it plays, the queue, the volume.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                if (jam != null) {
                    Pill("Jam · ${jam?.members?.count { it.present } ?: 1}", { nav.go(Dest.Jam) }, kind = PillKind.Server, icon = PhaseIcons.Jam, height = 40.dp)
                } else {
                    Pill(graph.device.name, { nav.go(Dest.Devices) }, kind = PillKind.Outline, icon = PhaseIcons.Computer, height = 40.dp)
                }
                Chip(null, showQueue, toggleQueue, icon = PhaseIcons.QueueAdd, contentDescription = "Queue")
                LevelBar(
                    state.volume, onChange = player::setVolume, onChangeEnd = {},
                    modifier = Modifier.width(110.dp), description = "Volume",
                )
            }
        }
    }
}
