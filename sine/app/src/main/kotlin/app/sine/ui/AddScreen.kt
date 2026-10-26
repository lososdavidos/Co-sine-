package app.sine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.core.cosine.AddItem
import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.IngestJob
import app.sine.core.cosine.Lookup
import app.sine.core.cosine.PendingShare
import app.sine.core.cosine.ShareText
import app.sine.core.server.Capability
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.Tag
import app.sine.ui.phase.Tick
import app.sine.ui.phase.rowSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Add (§6.10): one field for a link or a search; Cosine works out which.
 * Results appear beneath; picking queues the ingest. The queue's state is
 * shown here too, because getting music in has visible state (G4, Q13).
 */
@Composable
fun AddScreen(session: AccountSession, initialText: String, onCorrect: (trackId: String) -> Unit) {
    val account by session.accountFlow.collectAsStateWithLifecycle()
    val cosine = session.cosine
    when {
        cosine == null -> Message("Adding music needs a Cosine server.")
        Capability.INGEST !in account.kind.capabilities ->
            Message("This Cosine can't add music yet: yt-dlp is not installed on the server.")
        else -> AddContent(session, cosine, initialText, onCorrect.takeIf { Capability.REVIEW in account.kind.capabilities })
    }
}

@Composable
private fun AddContent(session: AccountSession, cosine: CosineClient, initialText: String, onCorrect: ((String) -> Unit)?) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf(initialText) }
    var lookup by remember { mutableStateOf<Lookup?>(null) }
    // The search an album or playlist was opened from, to go back to.
    var searched by remember { mutableStateOf<Lookup?>(null) }
    // A playlist's songs also become a playlist in the library unless unticked.
    var keepPlaylist by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageSignal by remember { mutableStateOf(Signal.None) }
    var jobs by remember { mutableStateOf<List<IngestJob>>(emptyList()) }
    var refreshJobs by remember { mutableStateOf(0) }

    /** Offline, a link is kept and submitted on reconnect, and the screen says so. */
    fun keepForLater(urls: List<String>) {
        val now = System.currentTimeMillis()
        urls.forEach { graph.pendingShares.add(PendingShare(session.account.id, it, now)) }
        message = "Saved. Submitting when Cosine is reachable."
        messageSignal = Signal.Server
    }

    suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CosineException) {
        message = e.message
        messageSignal = Signal.Fault
        null
    } catch (e: IOException) {
        message = Voice.serverUnreachable(isCosine = true)
        messageSignal = Signal.Server
        null
    }

    fun look(text: String = input.trim(), from: Lookup? = null) {
        if (text.isEmpty()) return
        scope.launch {
            busy = true
            message = null
            val result = attempt { cosine.lookup(text) }
            if (result != null) {
                lookup = result
                searched = from
                keepPlaylist = true
                // A collection is queued whole unless you untick things; search results start unticked.
                selected = if (result.isSearch) emptySet() else result.items.filterNot { it.inLibrary }.map { it.url }.toSet()
                if (result.items.isEmpty() && result.collections.isEmpty()) {
                    message = "No results."
                    messageSignal = Signal.None
                }
            }
            busy = false
        }
    }

    fun add(urls: List<String>, force: Boolean = false) {
        // Songs picked from an album or playlist say where from: an album
        // lends them its name, a playlist becomes one in the library — even
        // with nothing to fetch, from the songs already here.
        val from = lookup?.takeIf { it.isCollection }?.url
        val playlistOnly = urls.isEmpty() && from != null && lookup?.isPlaylist == true && keepPlaylist
        if (urls.isEmpty() && !playlistOnly) return
        if (!session.isOnline) {
            keepForLater(urls)
            return
        }
        scope.launch {
            busy = true
            val queued = attempt { cosine.submit(urls, force, from, keepPlaylist) }
            if (queued == null && message == Voice.serverUnreachable(true)) keepForLater(urls)
            if (queued != null) {
                messageSignal = Signal.None
                message = when {
                    playlistOnly -> "Playlist made from the songs already in your library."
                    queued.size == 1 -> "Queued."
                    else -> "Queued ${queued.size} links."
                }
                selected = emptySet()
                refreshJobs++
            }
            busy = false
        }
    }

    fun retry(job: IngestJob, force: Boolean) {
        scope.launch {
            attempt { cosine.retry(job.id, force) }
            refreshJobs++
        }
    }

    // Arriving from the share sheet: look the link up at once, or keep it if offline.
    LaunchedEffect(Unit) {
        if (initialText.isBlank()) return@LaunchedEffect
        if (session.isOnline) look()
        else ShareText.extractUrl(initialText)?.let { keepForLater(listOf(it)) }
    }
    // Poll the queue while this screen is open: quickly while something is moving.
    LaunchedEffect(refreshJobs) {
        while (true) {
            if (session.isOnline) runCatching {
                val before = jobs.filter { it.isActive }.map { it.id }.toSet()
                jobs = cosine.jobs(30)
                // Something just landed: bring it into the library now.
                if (jobs.any { it.id in before && !it.isActive }) session.syncSoon()
            }
            delay(if (jobs.any { it.isActive }) 1_500 else 6_000)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TopSpace()
                Text("Add", color = PhaseColor.Text1, style = PhaseType.display(40.sp))
                PhaseField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Paste a link or search",
                    onClear = { input = "" },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { look() }),
                )
                Pill(if (busy) "Working…" else "Look up", { look() }, enabled = input.isNotBlank() && !busy, icon = PhaseIcons.Search)
                message?.let { StatusLine(it, signal = messageSignal) }
            }
        }

        lookup?.let { l ->
            searched?.let { back ->
                item {
                    Pill(
                        "Back to search results", { lookup = back; searched = null; selected = emptySet() },
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp), kind = PillKind.Quiet, icon = PhaseIcons.Back, height = 40.dp,
                    )
                }
            }
            if (l.isCollection) item { CollectionHeading(l, keepPlaylist) { keepPlaylist = it } }
            // Albums and playlists come first when one is what was asked for.
            val collectionsFirst = l.collections.firstOrNull()?.asked == true
            if (collectionsFirst) collectionResults(l) { look(it.url, from = l) }
            if (l.isSearch && l.items.isNotEmpty()) item {
                SectionHeader(if (l.backend == "api") "Songs, from the source APIs" else "Songs")
            }
            // Keyed by position: a result list may repeat a link, and a
            // repeated LazyColumn key crashes.
            itemsIndexed(l.items, key = { i, it -> "r:$i:${it.url}" }) { _, item ->
                ResultRow(item, checked = item.url in selected) {
                    selected = if (item.url in selected) selected - item.url else selected + item.url
                }
            }
            if (l.items.isNotEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill(
                        if (selected.size <= 1) "Add" else "Add ${selected.size}",
                        { add(l.items.map { it.url }.filter { it in selected }) },
                        kind = PillKind.Primary, enabled = selected.isNotEmpty() && !busy,
                    )
                    // Q12: something you already have is refused unless you insist.
                    val owned = l.items.filter { it.inLibrary }
                    if (!l.isSearch && owned.isNotEmpty() && selected.isEmpty()) {
                        if (l.isPlaylist && keepPlaylist) Pill("Make the playlist", { add(emptyList()) }, enabled = !busy)
                        Pill("Add anyway", { add(owned.map { it.url }, force = true) }, enabled = !busy)
                    }
                }
            }
            if (!collectionsFirst) collectionResults(l) { look(it.url, from = l) }
        }

        if (jobs.isNotEmpty()) {
            item {
                val fetching = jobs.count { it.isActive }
                SectionLabel("Recent adds", end = if (fetching > 0) "$fetching fetching" else null)
            }
            items(jobs, key = { "j:" + it.id }) { job ->
                // Anything added can be corrected where it was filed, not
                // only what lands in the review queue (§6.12).
                val trackId = job.trackId
                JobRow(
                    job,
                    onRetry = { retry(job, false) },
                    onForce = { retry(job, true) },
                    onCorrect = if (trackId != null && onCorrect != null) ({ onCorrect(trackId) }) else null,
                )
            }
        }
    }
}

/** What a looked-up album or playlist is, and what adding it does (§3.1a). */
@Composable
private fun CollectionHeading(l: Lookup, keepPlaylist: Boolean, onKeepPlaylist: (Boolean) -> Unit) {
    val count = Voice.songs(l.items.size)
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tag(if (l.isAlbum) "Album" else if (l.isPlaylist) "Playlist" else "Collection")
        Text(l.title ?: "Untitled", color = PhaseColor.Text1, style = PhaseType.display(32.sp))
        if (l.isAlbum) l.artist?.let { Text(it, color = PhaseColor.Text2, style = PhaseType.Body) }
        Text(
            if (l.isPlaylist) "$count. ${Voice.PLAYLIST_FILED}" else "$count. Untick what you don't want.",
            color = PhaseColor.Text2, style = PhaseType.Small,
        )
        if (l.isPlaylist) {
            Row(
                Modifier
                    .clip(PhaseShape.Square)
                    .toggleable(value = keepPlaylist, role = Role.Checkbox, onValueChange = onKeepPlaylist)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Tick(keepPlaylist)
                Text("Keep it as a playlist in my library, with the songs I already have", color = PhaseColor.Text1, style = PhaseType.Small.copy(fontSize = 14.sp))
            }
        }
    }
}

/** Albums and playlists a search found; picking one shows its songs. */
private fun LazyListScope.collectionResults(l: Lookup, onOpen: (AddItem) -> Unit) {
    if (l.collections.isEmpty()) return
    item { SectionHeader("Albums and playlists") }
    itemsIndexed(l.collections, key = { i, it -> "c:$i:${it.url}" }) { _, item ->
        val details = listOfNotNull(
            if (item.kind == "album") "Album" else "Playlist",
            item.uploader.ifBlank { null },
            item.tracks.takeIf { it > 0 }?.let { Voice.songs(it) },
            item.source.ifBlank { null },
        ).joinToString(" · ")
        ItemRow(item.title, details, item.thumbnail, end = { Icon(PhaseIcons.Forward, null, tint = PhaseColor.Text3) }) { onOpen(item) }
    }
}

/** A result to tick. Whether it's the song as released is Cosine's finding, so it carries Cosine's dot. */
@Composable
private fun ResultRow(item: AddItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .rowSurface(false)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tick(checked)
        Art(item.thumbnail, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                item.uploader.ifBlank { null },
                item.source.ifBlank { null },
                item.durationSec.takeIf { it > 0 }?.let { Voice.formatDuration(it) },
                item.plays.takeIf { it > 0 }?.let { "$it plays" },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val note = Voice.lengthNote(item.durationSec, item.releaseSec)
            when {
                item.preview -> StatusLine("30-second preview only", signal = Signal.Fault, color = PhaseColor.Text2)
                note != null && note.startsWith("Same") -> StatusLine(note, signal = Signal.Server)
                note != null -> Text(note, color = PhaseColor.Text3, style = PhaseType.Small)
            }
            if (item.inLibrary) Tag("In your library", Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun JobRow(job: IngestJob, onRetry: () -> Unit, onForce: () -> Unit, onCorrect: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(job.title, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    job.status == "running" -> Unit
                    job.isFailed -> StatusLine(jobStatus(job), signal = Signal.Fault, color = PhaseColor.Text2)
                    job.status == "queued" -> StatusLine(jobStatus(job), signal = Signal.Server, color = PhaseColor.Text2)
                    else -> Text(jobStatus(job), color = PhaseColor.Text2, style = PhaseType.Small)
                }
            }
            if (job.status == "running") Text("${(job.progress * 100).toInt()}%", color = PhaseColor.Text2, style = PhaseType.Data)
            when {
                job.isFailed -> Pill("Retry", onRetry, height = 40.dp)
                job.isKnown -> Pill("Add anyway", onForce, height = 40.dp)
            }
            if (onCorrect != null) Pill("Correct", onCorrect, height = 40.dp)
        }
        // Fetching is the server at work: a Cosine bar.
        if (job.status == "running") {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(PhaseShape.Square).background(PhaseColor.Line)) {
                Box(Modifier.fillMaxWidth(job.progress.toFloat().coerceIn(0f, 1f)).height(6.dp).clip(PhaseShape.Square).background(PhaseColor.Cosine))
            }
        }
    }
}

/** Plain and factual (§6.17). */
fun jobStatus(job: IngestJob): String = when (job.status) {
    "queued" -> "Queued."
    "running" -> "Fetching, ${(job.progress * 100).toInt()}%."
    "done" -> "Added."
    "duplicate" -> "Added. It was already in the Store."
    "expanded" -> "Collection queued."
    "known" -> job.error ?: "Already in your library."
    "failed" -> "Couldn't fetch — ${job.error ?: "unknown error"}."
    else -> job.status
}
