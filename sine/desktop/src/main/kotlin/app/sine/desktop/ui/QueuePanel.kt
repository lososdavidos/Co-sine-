package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sine.core.model.Track
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.ui.phase.Initial
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.PlayingBars
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.SectionLabel

/**
 * The queue, beside the page (§6.15): the song playing, the songs added
 * (Queued), then the rest (Up next), as on the phone (#37). In a jam it's
 * the jam's queue, with who added each song (§4C).
 */
@Composable
fun QueuePanel(session: AccountSession, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    val player = graph.player
    val state by player.state.collectAsState()
    val jam by graph.jam.jam.collectAsState()
    Row(modifier) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(PhaseColor.LineFloat))
        Column(Modifier.weight(1f).fillMaxHeight().background(PhaseColor.Float)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (jam != null) "The jam's queue" else "Queue", Modifier.weight(1f), color = PhaseColor.Text1, style = PhaseType.Title)
                if (jam == null && state.queue.ahead.isNotEmpty()) Pill("Clear", player::clearAhead, kind = PillKind.Quiet, height = 36.dp)
            }
            val j = jam
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (j != null) {
                    val playingAt = state.queue.pos
                    item { SectionLabel("Now playing") }
                    j.queue.getOrNull(playingAt)?.let { s ->
                        item { QueueRow(session, Track(s.track, s.title, s.artist, album = s.album, durationSec = s.durationSec, coverArt = s.coverArt), playing = true, by = s.addedBy, theirs = !s.inLibrary) }
                    }
                    val next = j.queue.drop(playingAt + 1)
                    item { SectionLabel("Up next", end = if (next.isEmpty()) "Nothing yet" else Voice.songs(next.size)) }
                    items(next) { s ->
                        QueueRow(session, Track(s.track, s.title, s.artist, album = s.album, durationSec = s.durationSec, coverArt = s.coverArt), by = s.addedBy, theirs = !s.inLibrary)
                    }
                } else {
                    val current = state.current
                    if (current == null) {
                        item { Text("Nothing playing. Double-click a song to play from it, or press Q on it to queue it.", Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Small) }
                    } else {
                        item { SectionLabel("Now playing") }
                        item { QueueRow(session, current, playing = true) }
                        val queued = state.queue.queuedAhead
                        if (queued.isNotEmpty()) {
                            item { SectionLabel("Queued", end = Voice.songs(queued.size)) }
                            items(queued, key = { it.second.key }) { (at, e) ->
                                QueueRow(session, e.track, onPlay = { player.skipTo(at) }, onRemove = { player.remove(at) })
                            }
                        }
                        val upNext = state.queue.upNext
                        item { SectionLabel("Up next", end = if (upNext.isEmpty()) "Nothing yet" else Voice.songs(upNext.size)) }
                        items(upNext.take(UP_NEXT_SHOWN), key = { it.second.key }) { (at, e) ->
                            QueueRow(session, e.track, onPlay = { player.skipTo(at) }, onRemove = { player.remove(at) })
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(PhaseColor.LineFloat).heightIn(min = 1.dp, max = 1.dp))
            Text(
                if (j != null) "${j.host} plays, pauses and skips. Anyone adds: the queue is the jam's, so press Q on a song, or right-click it."
                else "Press Q on a song, or right-click it, to add it here.",
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = PhaseColor.Text3, style = PhaseType.Small,
            )
        }
    }
}

private const val UP_NEXT_SHOWN = 200

@Composable
private fun QueueRow(
    session: AccountSession,
    track: Track,
    playing: Boolean = false,
    by: String? = null,
    theirs: Boolean = false,
    onPlay: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(if (hovered && onPlay != null) PhaseColor.Raised else PhaseColor.Float)
            .hoverable(hover)
            .then(if (onPlay != null) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(session, track.coverArt, 44.dp, px = 150)
        Column(Modifier.weight(1f)) {
            Text(track.title, color = PhaseColor.Text1, style = PhaseType.Name.copy(fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (theirs) PhaseLabel("Theirs")
        if (by != null) Initial(by, size = 24.dp)
        if (playing) PlayingBars(Modifier.padding(end = 12.dp))
        else if (onRemove != null && hovered) Pill(null, onRemove, icon = PhaseIcons.QueueRemove, height = 40.dp, kind = PillKind.SecondaryFault, contentDescription = "Out of the queue")
        else if (by == null) Text(clock(track.durationSec * 1000L), Modifier.padding(end = 12.dp), color = PhaseColor.Text2, style = PhaseType.Data)
    }
}
