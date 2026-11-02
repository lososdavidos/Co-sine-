package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.cosine.at
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.ui.phase.AmbientSource
import app.sine.ui.phase.Chip
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.Initial
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.WaveScrubber
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The jam (§4C): what's playing for everyone, beside its queue in the
 * panel. On the desktop the two are on screen together, which the phone
 * can't do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JamPage(session: AccountSession) {
    val graph = LocalGraph.current
    val say = LocalSay.current
    val scope = rememberCoroutineScope()
    val jam by graph.jam.jam.collectAsState()
    val invitations by graph.jam.invitations.collectAsState()
    val player by graph.player.state.collectAsState()
    val cosine = session.cosine
    var inviting by remember { mutableStateOf(false) }

    fun attempt(work: suspend () -> String?) = scope.launch {
        try {
            work()?.let { say(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(e.message ?: Voice.serverUnreachable(isCosine = true), Signal.Server)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val j = jam
        if (j == null || cosine == null) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageTitle("Jam")
                Text("Listen together with other accounts, wherever they are. Everyone adds to one queue; the host plays and skips.", color = PhaseColor.Text2, style = PhaseType.Body)
                if (invitations.isNotEmpty()) {
                    SectionLabel("Asked to")
                    for (inv in invitations) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Initial(inv.host, size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text("${inv.host}'s jam", color = PhaseColor.Text1, style = PhaseType.Name)
                                Text(Voice.people(inv.members), color = PhaseColor.Text2, style = PhaseType.Small)
                            }
                            Pill("Join", { attempt { cosine?.joinJam(inv.id); graph.jam.refresh(); "In the jam." } }, kind = PillKind.Primary, height = 44.dp)
                            Pill("Decline", { attempt { cosine?.leaveJam(inv.id); graph.jam.refresh(); "Declined." } }, kind = PillKind.Quiet, height = 44.dp)
                        }
                    }
                }
                SectionLabel("Start one")
                val accounts = rememberLoad(session) { cosine?.accounts().orEmpty().filter { it != session.account.username } }
                val chosen = remember { mutableStateListOf<String>() }
                Loaded(accounts) { all ->
                    if (all.isEmpty()) Text("There are no other accounts on this server. A jam on your own still works.", color = PhaseColor.Text2, style = PhaseType.Small)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in all) Chip(name, name in chosen, { if (name in chosen) chosen.remove(name) else chosen.add(name) })
                    }
                }
                Pill(
                    if (chosen.isEmpty()) "Start a jam" else "Start, and ask ${chosen.size}",
                    { attempt { cosine?.startJam(chosen.toList()); graph.jam.refresh(); "Jam started. Its queue is your queue now: press Q on a song, or right-click it." } },
                    kind = PillKind.Primary, icon = PhaseIcons.Jam, enabled = cosine != null,
                )
            }
            return@Column
        }

        val host = j.host == session.account.username
        val song = j.queue.getOrNull(player.queue.pos.takeIf { player.jam == j.id } ?: j.at(System.currentTimeMillis()).first)
        AmbientSource(session.artwork(song?.coverArt, 300))
        Box(Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f).clip(PhaseShape.Square).background(PhaseColor.Raised)) {
            val model = remember(song?.coverArt) { session.artwork(song?.coverArt, 600) }
            if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(PhaseColor.Scrim).padding(horizontal = 16.dp, vertical = 14.dp)) {
                PhaseLabel(if (host) "Your jam" else "${j.host}'s jam", color = PhaseColor.OnScrim)
                Text("Jam", color = PhaseColor.Text1, style = PhaseType.display(44.sp))
            }
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(song?.title ?: "Nothing yet", color = PhaseColor.Text1, style = PhaseType.Title.copy(fontSize = 28.sp, lineHeight = 32.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            song?.let {
                Text(it.artist, color = PhaseColor.Text2, style = PhaseType.Body)
                PhaseLabel("Added by ${it.addedBy}", color = PhaseColor.Text2)
            }
            val duration = player.durationMs.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clock(player.positionMs), color = PhaseColor.Text2, style = PhaseType.Data)
                var scrub by remember { mutableStateOf<Float?>(null) }
                WaveScrubber(
                    scrub ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onScrub = { scrub = it },
                    onScrubEnd = {
                        val to = ((scrub ?: 0f) * duration).toLong()
                        scrub = null
                        attempt { cosine.jamTransport(j.id, "seek", to, player.queue.pos); graph.jam.refresh(); null }
                    },
                    modifier = Modifier.weight(1f), enabled = host, description = "Position in the jam",
                )
                Text(clock(player.durationMs), color = PhaseColor.Text2, style = PhaseType.Data)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (song != null && !song.inLibrary) {
                    Pill("Keep it", { attempt { cosine.addToLibrary(listOf(song.track)); "In your library." } }, kind = PillKind.Secondary, icon = PhaseIcons.Plus, height = 44.dp)
                }
                Pill("Invite", { inviting = !inviting }, kind = PillKind.Outline, icon = PhaseIcons.Share, height = 44.dp)
                Pill(
                    if (host) "End jam" else "Leave",
                    { attempt { if (host) cosine.endJam(j.id) else cosine.leaveJam(j.id); graph.jam.refresh(); if (host) "Jam ended." else "Left the jam." } },
                    kind = PillKind.QuietFault, icon = PhaseIcons.Leave, height = 44.dp,
                )
            }
            SectionLabel("In the jam", Modifier.padding(top = 16.dp), end = Voice.people(j.members.count { it.present }))
            for (m in j.members.filter { it.present || it.joined }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Initial(m.name, size = 32.dp)
                    Text(if (m.name == session.account.username) "You" else m.name, Modifier.weight(1f), color = PhaseColor.Text1, style = PhaseType.Name)
                    if (m.host) PhaseLabel("Host")
                }
            }
            if (inviting) InviteList(session, j.id, j.members.map { it.name })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InviteList(session: AccountSession, jamId: Long, members: List<String>) {
    val say = LocalSay.current
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val accounts = rememberLoad(session) { session.cosine?.accounts().orEmpty() }
    SectionLabel("Ask")
    Loaded(accounts) { all ->
        val others = all.filter { it !in members && it != session.account.username }
        if (others.isEmpty()) Text("Everyone on this server is asked already.", color = PhaseColor.Text2, style = PhaseType.Small)
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in others) Pill(name, {
                scope.launch {
                    runCatching { session.cosine?.inviteToJam(jamId, listOf(name)); graph.jam.refresh() }
                        .onSuccess { say("Asked $name.") }.onFailure { say(it.message ?: "Cosine refused that.", Signal.Server) }
                }
            }, kind = PillKind.Outline, height = 40.dp)
        }
    }
}

/**
 * Your devices (§4A, §4B): what each is playing, to pick it up here or to
 * play this computer's music there.
 */
@Composable
fun DevicesPage(session: AccountSession) {
    val graph = LocalGraph.current
    val say = LocalSay.current
    val scope = rememberCoroutineScope()
    val cosine = session.cosine
    var tick by remember { mutableStateOf(0) }
    val load = rememberLoad(session, tick) { cosine?.devices().orEmpty().filter { it.device.id != graph.device.id } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        PageTitle("Devices", label = "This is ${graph.device.name}") {
            Pill(null, { tick++ }, icon = PhaseIcons.Retry, height = 44.dp, contentDescription = "Look again")
        }
        Loaded(load) { devices ->
            if (devices.isEmpty()) Text("No other device of yours has played yet.", color = PhaseColor.Text2, style = PhaseType.Body)
            for (d in devices) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PhaseIcon(if (d.device.kind == "desktop") PhaseIcons.Computer else PhaseIcons.Device, null, size = 28.dp, tone = IconTone.Dim)
                    Cover(session, d.coverArt, 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(d.device.name, color = PhaseColor.Text1, style = PhaseType.Name)
                        Text(
                            d.title?.let { "$it · ${d.artist.orEmpty()}" } ?: "Nothing playing",
                            color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (d.track != null) {
                        Pill("Play here", {
                            scope.launch {
                                runCatching {
                                    val s = cosine!!.session(d.device.id)
                                    val tracks = s.queue.mapNotNull { id -> runCatching { session.track(id).value }.getOrNull() }
                                    val index = tracks.indexOfFirst { it.id == s.queue.getOrNull(s.current) }.coerceAtLeast(0)
                                    graph.player.play(session, tracks, index, s.positionMs)
                                    cosine.command(d.device.id, app.sine.core.cosine.RemoteCommand("pause", from = graph.device.id))
                                }.onSuccess { say("Picked up from ${d.device.name}.") }
                                    .onFailure { say(it.message ?: "That didn't work.", Signal.Server) }
                            }
                        }, kind = PillKind.Primary, icon = PhaseIcons.Play, height = 44.dp)
                    }
                    val mine = graph.player.state.value
                    if (mine.current != null) {
                        Pill("Play there", {
                            scope.launch {
                                runCatching {
                                    cosine!!.command(
                                        d.device.id,
                                        app.sine.core.cosine.RemoteCommand(
                                            "queue", positionMs = graph.player.positionMs(), index = mine.queue.pos,
                                            queue = mine.queue.tracks.map { it.id }, from = graph.device.id,
                                        ),
                                    )
                                    graph.player.pause()
                                }.onSuccess { say("Playing on ${d.device.name}.") }
                                    .onFailure { say(it.message ?: "That didn't work.", Signal.Server) }
                            }
                        }, kind = PillKind.Outline, icon = PhaseIcons.Device, height = 44.dp)
                    }
                }
            }
        }
        if (cosine == null) StatusLine("Devices need a Cosine server.", signal = Signal.Server)
    }
}
