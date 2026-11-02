package app.sine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.DeviceNow
import app.sine.core.cosine.PlaySession
import app.sine.core.cosine.RemoteCommand
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.Ambience
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.LevelBar
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.WaveScrubber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Your devices (§4B): each one with Sine or a Subsonic app, what it's
 * playing, and whether it's there to be controlled now. This phone first.
 * Another one, tapped, is controlled from here, through Cosine.
 */
@Composable
fun DevicesScreen(session: AccountSession, nav: Pages) {
    val context = LocalContext.current
    val me = context.graph.device.id
    val player = context.graph.player
    val state by player.state.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf<List<DeviceNow>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    devices = session.cosine?.devices().orEmpty().filter { it.device.id != me }
                    problem = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem = Voice.serverUnreachable(isCosine = true)
                }
                delay(3_000)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                TopSpace()
                Text("Devices", color = PhaseColor.Text1, style = PhaseType.display(52.sp))
            }
        }
        item { SectionLabel("This phone") }
        item {
            val item = state.current
            DeviceRow(
                context.graph.device.name, PhaseIcons.Device, here = true,
                line = item?.let { listOfNotNull(it.mediaMetadata.title?.toString(), it.mediaMetadata.artist?.toString()).joinToString(" · ") } ?: "Nothing playing",
                listening = true,
            ) {}
        }
        item { SectionLabel("Your other devices", end = "Through Cosine") }
        val others = devices
        when {
            problem != null && others == null -> item { Text(problem!!, Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Body) }
            others == null -> item { Text("Looking…", Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Body) }
            others.isEmpty() -> item {
                Text("Open Sine on another phone or tablet with this account, and it shows here.", Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Body)
            }
            else -> items(others, key = { it.device.id }) { d ->
                DeviceRow(
                    d.device.name, if (d.device.kind == "desktop") PhaseIcons.Computer else PhaseIcons.Device, here = false,
                    line = d.title?.let { listOfNotNull(it, d.artist).joinToString(" · ") } ?: if (d.listening) "Ready" else "Away",
                    listening = d.listening,
                ) { nav.navigate(Routes.remote(d.device.id)) }
            }
        }
    }
}

@Composable
private fun DeviceRow(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, here: Boolean, line: String, listening: Boolean, onOpen: () -> Unit) {
    ItemRow(
        title = name, subtitle = line, art = null, showArt = false,
        leading = { PhaseIcon(icon, null, size = 28.dp, tone = if (here) IconTone.Sine else if (listening) IconTone.SineFar else IconTone.Dim) },
        end = if (here) null else ({
            // Listening now: the server's signal, a Cosine dot.
            if (listening) Box(Modifier.size(8.dp).background(PhaseColor.Cosine, CircleShape))
            PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint)
        }),
        dimmed = !here && !listening,
        onClick = onOpen,
    )
}

/**
 * Another device, controlled from here (§4B): what it plays and where it
 * is, its transport, its wave to seek, what's next to skip to; and the two
 * moves between it and this phone, playing its music here, or sending this
 * phone's there.
 */
@Composable
fun RemoteScreen(session: AccountSession, deviceId: String, nav: Pages) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf<DeviceNow?>(null) }
    var queue by remember { mutableStateOf<PlaySession?>(null) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Where it's at, carried on between looks while it plays.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    // The volume being set here, until the device says it has it.
    var setting by remember { mutableStateOf<Float?>(null) }
    var sentVolume by remember { mutableStateOf<Float?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(deviceId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                while (true) {
                    val c = session.cosine ?: break
                    runCatching {
                        now = c.devices().firstOrNull { it.device.id == deviceId }
                        queue = runCatching { c.session(deviceId) }.getOrNull()
                    }
                    now?.let { positionMs = it.positionMs }
                    // Back from the device as sent: what it says is what shows again.
                    val said = now?.volume?.toFloat()
                    if (said != null && sentVolume != null && kotlin.math.abs(said - sentVolume!!) < 0.08f) sentVolume = null
                    val ahead = queue?.let { q -> q.queue.drop(q.current).take(UP_NEXT + 1) }.orEmpty()
                    ahead.filter { it !in titles }.forEach { id ->
                        runCatching { session.track(id).value }.getOrNull()?.let { t ->
                            titles = titles + (id to "${t.title} · ${t.artist}")
                            if (id == ahead.first()) durationMs = t.durationSec * 1000L
                        }
                    }
                    ahead.firstOrNull()?.let { id -> runCatching { session.track(id).value }.getOrNull()?.let { durationMs = it.durationSec * 1000L } }
                    delay(2_000)
                }
            }
            launch {
                while (true) {
                    delay(500)
                    if (now?.playing == true) positionMs += 500
                }
            }
        }
    }

    fun send(command: RemoteCommand, said: String? = null) = scope.launch {
        val c = session.cosine ?: return@launch
        try {
            c.command(deviceId, command.copy(from = graph.device.name))
            said?.let { say(it) }
        } catch (e: CosineException) {
            say(e.message ?: "That device didn't take it.", Signal.Server)
        } catch (e: IOException) {
            say(Voice.serverUnreachable(isCosine = true), Signal.Server)
        }
    }

    val d = now
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Ambience(session.artwork(d?.coverArt), Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TopSpace()
                    PhaseLabel(if (d?.listening == true) "Controlling" else "Away", color = PhaseColor.OnScrim)
                    Text(d?.device?.name ?: "…", color = PhaseColor.Text1, style = PhaseType.display(44.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Art(session.artwork(d?.coverArt), 96.dp)
                        Column(Modifier.weight(1f)) {
                            Text(d?.title ?: "Nothing playing", color = PhaseColor.Text1, style = PhaseType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            d?.artist?.let { Text(it, color = PhaseColor.OnScrim, style = PhaseType.Body) }
                        }
                    }
                }
            }
        }
        val listening = d?.listening == true
        if (d != null && !listening) item {
            Text("${d.device.name} isn't listening now. Open Sine on it to control it from here.", Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Body)
        }
        if (d?.title != null) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                WaveScrubber(
                    fraction = scrub ?: fraction,
                    onScrub = { scrub = it },
                    onScrubEnd = {
                        scrub?.let { f -> positionMs = (f * durationMs).toLong(); send(RemoteCommand("seek", positionMs = positionMs)) }
                        scrub = null
                    },
                    enabled = listening && durationMs > 0,
                    description = "Position on ${d.device.name}",
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Voice.formatDuration((positionMs / 1000).toInt()), color = PhaseColor.Text2, style = PhaseType.Data)
                    if (durationMs > 0) Text(Voice.formatDuration((durationMs / 1000).toInt()), color = PhaseColor.Text2, style = PhaseType.Data)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Pill(null, { send(RemoteCommand("previous")) }, icon = PhaseIcons.Previous, height = 56.dp, width = 72.dp, enabled = listening, contentDescription = "Previous on ${d?.device?.name}")
                Pill(
                    null, { send(RemoteCommand("toggle")); now = d?.copy(playing = !d.playing) },
                    kind = PillKind.Primary, icon = if (d?.playing == true) PhaseIcons.Pause else PhaseIcons.Play,
                    height = 56.dp, width = 104.dp, enabled = listening, contentDescription = if (d?.playing == true) "Pause" else "Play",
                )
                Pill(null, { send(RemoteCommand("next")) }, icon = PhaseIcons.Next, height = 56.dp, width = 72.dp, enabled = listening, contentDescription = "Next on ${d?.device?.name}")
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Its music here, from where it is.
                if (d?.title != null) Pill("Play here", {
                    scope.launch {
                        send(RemoteCommand("pause"))
                        resumeFrom(context, session, d)
                    }
                }, icon = PhaseIcons.Device, height = 44.dp)
                // This phone's music there, from where it is, and quiet here.
                val mine = graph.player.state.value
                if (listening && mine.current != null) Pill("Play there", {
                    val ids = mine.queue.map { it.item.mediaId }
                    send(RemoteCommand("queue", queue = ids, index = mine.index.coerceAtLeast(0), positionMs = graph.player.positionMs()), "Playing on ${d.device.name}.")
                    graph.player.pause()
                }, icon = PhaseIcons.Forward, height = 44.dp)
            }
        }
        // Its volume (§4B): the one its own buttons change. Shown once it says where it is.
        val level = setting ?: sentVolume ?: d?.volume?.toFloat()
        if (listening && level != null) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PhaseLabel("Volume")
                    Text("${Math.round(level * 100)}%", color = PhaseColor.Text2, style = PhaseType.Data)
                }
                LevelBar(
                    fraction = level,
                    onChange = { setting = it },
                    onChangeEnd = {
                        setting?.let { v ->
                            sentVolume = v
                            send(RemoteCommand("volume", volume = v.toDouble()))
                        }
                        setting = null
                    },
                    description = "Volume on ${d.device.name}",
                )
            }
        }
        val q = queue
        if (q != null && listening) {
            val ahead = q.queue.drop(q.current + 1).take(UP_NEXT)
            if (ahead.isNotEmpty()) item { SectionLabel("Up next there") }
            ahead.forEachIndexed { i, id ->
                item(key = "next-$i-$id") {
                    ItemRow(titles[id]?.substringBefore(" · ") ?: "…", titles[id]?.substringAfter(" · ", ""), null, showArt = false) {
                        send(RemoteCommand("skip", index = q.current + 1 + i))
                    }
                }
            }
        }
    }
}

/** How much of another device's queue shows: what's next, not all of it. */
private const val UP_NEXT = 10
