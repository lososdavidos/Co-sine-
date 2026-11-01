package app.sine.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.Jam
import app.sine.core.cosine.at
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.Ambience
import app.sine.ui.phase.Chip
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.People
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.RowNumber
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.Slab
import app.sine.ui.phase.WaveScrubber
import app.sine.ui.phase.numberColumn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Jam (§4C), the party mode: listening with other accounts, anywhere.
 * Out of one: the jams you're asked to, and starting your own with who you
 * choose. In one: who's here, what plays and who added it, the queue
 * everyone adds to, and the host's transport. A song you don't have plays
 * anyway; one tap keeps it.
 */
@Composable
fun JamScreen(session: AccountSession, nav: Pages) {
    val graph = LocalContext.current.graph
    val jam by graph.jam.jam.collectAsStateWithLifecycle()
    val invitations by graph.jam.invitations.collectAsStateWithLifecycle()
    val j = jam
    if (j == null) NoJam(session, invitations.map { it.id to "${it.host}'s jam" + if (it.members > 1) " · ${it.members} listening" else "" })
    else InJam(session, j)
}

/** Says what happened, or why not. */
private suspend fun attempt(work: suspend () -> String?) {
    try {
        work()?.let { say(it) }
    } catch (e: CosineException) {
        say(e.message ?: "Cosine refused that.", Signal.Server)
    } catch (e: IOException) {
        say(Voice.serverUnreachable(isCosine = true), Signal.Server)
    }
}

@Composable
private fun NoJam(session: AccountSession, invitations: List<Pair<Long, String>>) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<String>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        accounts = runCatching { session.accounts() }.getOrNull()
        unreachable = accounts == null
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TopSpace()
                Text("Jam", color = PhaseColor.Text1, style = PhaseType.display(52.sp))
                Text("Listen together with other accounts, wherever they are. Everyone adds to one queue; the host plays and skips.", color = PhaseColor.Text2, style = PhaseType.Body)
            }
        }
        if (invitations.isNotEmpty()) {
            item { SectionLabel("Asked to") }
            invitations.forEach { (id, line) ->
                item(key = "inv-$id") {
                    Slab(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), padding = 14.dp) {
                        Text(line, color = PhaseColor.Text1, style = PhaseType.Name)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Join", {
                                busy = true
                                scope.launch {
                                    attempt { session.cosine?.joinJam(id); graph.jam.refresh(); "In the jam." }
                                    busy = false
                                }
                            }, kind = PillKind.Primary, height = 44.dp, enabled = !busy)
                            Pill("Decline", {
                                scope.launch { attempt { session.cosine?.leaveJam(id); graph.jam.refresh(); "Declined." } }
                            }, kind = PillKind.Quiet, height = 44.dp)
                        }
                    }
                }
            }
        }
        item { SectionLabel("Start one") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val all = accounts) {
                    null -> Text(if (unreachable) "Cosine can't be reached to say who else is on it." else "Loading who's on this server…", color = PhaseColor.Text2, style = PhaseType.Small)
                    else -> if (all.isEmpty()) Text("There are no other accounts on this server. A jam on your own still works.", color = PhaseColor.Text2, style = PhaseType.Small)
                    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in all) Chip(name, name in chosen, { chosen = if (name in chosen) chosen - name else chosen + name })
                    }
                }
                Pill(if (chosen.isEmpty()) "Start a jam" else "Start, and ask ${chosen.size}", {
                    busy = true
                    scope.launch {
                        attempt { session.cosine?.startJam(chosen.toList()); graph.jam.refresh(); "Jam started. Its queue is your queue now: swipe a song left, or hold it and Add to queue." }
                        busy = false
                    }
                }, kind = PillKind.Primary, icon = PhaseIcons.Jam, height = 48.dp, enabled = !busy)
            }
        }
    }
}

@Composable
private fun InJam(session: AccountSession, j: Jam) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val host = j.you.host
    // Where it is, ticking on between changes.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(j.version) {
        val skew = j.serverTime - System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis() + skew
            delay(500)
        }
    }
    val (index, position) = j.at(now)
    val song = j.queue.getOrNull(index)
    var scrub by remember { mutableStateOf<Float?>(null) }
    var inviting by remember { mutableStateOf(false) }

    fun transport(type: String, positionMs: Long = 0, at: Int = 0) = scope.launch {
        attempt { session.cosine?.jamTransport(j.id, type, positionMs, at); graph.jam.refresh(); null }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Ambience(session.artwork(song?.coverArt), Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TopSpace()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            PhaseLabel(if (host) "Your jam" else "${j.host}'s jam", color = PhaseColor.OnScrim)
                            Text("Jam", color = PhaseColor.Text1, style = PhaseType.display(44.sp))
                        }
                        People(j.members.filter { it.present }.map { it.name }, size = 32.dp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Art(session.artwork(song?.coverArt), 112.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(song?.title ?: "Nothing yet", color = PhaseColor.Text1, style = PhaseType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            song?.let { Text(it.artist, color = PhaseColor.OnScrim, style = PhaseType.Body) }
                            song?.let { PhaseLabel("Added by ${it.addedBy}", color = PhaseColor.OnScrim) }
                        }
                    }
                }
            }
        }
        if (song != null) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                val length = song.durationSec * 1000L
                WaveScrubber(
                    fraction = scrub ?: if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f,
                    onScrub = { scrub = it },
                    onScrubEnd = {
                        scrub?.let { transport("seek", positionMs = (it * length).toLong()) }
                        scrub = null
                    },
                    enabled = host && length > 0,
                    description = "Position in the jam",
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Voice.formatDuration((position / 1000).toInt()), color = PhaseColor.Text2, style = PhaseType.Data)
                    Text(Voice.formatDuration(song.durationSec), color = PhaseColor.Text2, style = PhaseType.Data)
                }
            }
        }
        // The host's: everyone else hears it.
        if (host) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Pill(null, { transport("previous") }, icon = PhaseIcons.Previous, height = 56.dp, width = 72.dp, contentDescription = "Previous")
                Pill(null, { transport("toggle") }, kind = PillKind.Primary, icon = if (j.playing) PhaseIcons.Pause else PhaseIcons.Play, height = 56.dp, width = 104.dp, contentDescription = if (j.playing) "Pause" else "Play")
                Pill(null, { transport("next") }, icon = PhaseIcons.Next, height = 56.dp, width = 72.dp, contentDescription = "Next")
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Heard here though not yours: keep it, in one tap.
                if (song != null && !song.inLibrary) Pill("Keep it", {
                    scope.launch { attempt { session.cosine?.addToLibrary(listOf(song.track)); session.syncSoon(); graph.jam.refresh(); "${song.title} is in your library." } }
                }, icon = PhaseIcons.Plus, height = 44.dp)
                Pill("Invite", { inviting = !inviting }, kind = PillKind.Outline, icon = PhaseIcons.Share, height = 44.dp)
                Pill(if (host) "End jam" else "Leave", {
                    scope.launch {
                        attempt {
                            if (host) session.cosine?.endJam(j.id) else session.cosine?.leaveJam(j.id)
                            graph.jam.refresh()
                            if (host) "Jam ended." else "Left the jam."
                        }
                    }
                }, kind = PillKind.QuietFault, height = 44.dp)
            }
        }
        if (inviting) item { Invite(session, j) { inviting = false } }
        if (!host) item {
            Text("${j.host} plays, pauses and skips. Anyone adds: the queue is the jam's, so swipe a song left, or hold it and Add to queue.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = PhaseColor.Text3, style = PhaseType.Small)
        }
        val next = j.queue.drop(index + 1)
        item { SectionLabel("Up next", end = if (next.isEmpty()) "Nothing yet" else Voice.songs(next.size)) }
        itemsIndexed(next, key = { i, s -> "q-${index + 1 + i}-${s.track}" }) { i, s ->
            ItemRow(
                s.title, "${s.artist} · added by ${s.addedBy}", null, showArt = false,
                leading = { RowNumber(i + 1, numberColumn(next.size)) },
                label = if (s.inLibrary) null else "Theirs",
            ) { if (host) transport("skip", at = index + 1 + i) }
        }
    }
}

/** Asking more people in: the accounts not in it yet. */
@Composable
private fun Invite(session: AccountSession, j: Jam, onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { accounts = runCatching { session.accounts() }.getOrNull() }
    val here = j.members.map { it.name }.toSet()
    val others = accounts.orEmpty().filter { it !in here }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accounts != null && others.isEmpty()) Text("Everyone on this server is asked already.", color = PhaseColor.Text2, style = PhaseType.Small)
        for (name in others) Pill(name, {
            scope.launch { attempt { session.cosine?.inviteToJam(j.id, listOf(name)); graph.jam.refresh(); "Asked $name." } }
            onDone()
        }, kind = PillKind.Secondary, height = 40.dp)
    }
}
