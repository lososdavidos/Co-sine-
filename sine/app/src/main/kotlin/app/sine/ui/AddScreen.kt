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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.sine.data.AppGraph
import app.sine.graph
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcon
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Add (§6.10): one field for a link or a search; Cosine works out which.
 * Results appear beneath as you type, each with its own add button; an
 * album or playlist can be added whole from the top. The queue's state is
 * shown here too, because getting music in has visible state (G4, Q13).
 * Search's + reaches the same sources in place ([SourceFinder]).
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

/** Whether this account can add music from the sources at all. */
fun canAdd(session: AccountSession) = session.cosine != null && Capability.INGEST in session.account.kind.capabilities

@Composable
private fun AddContent(session: AccountSession, cosine: CosineClient, initialText: String, onCorrect: ((String) -> Unit)?) {
    var input by rememberSaveable { mutableStateOf(initialText) }
    val finder = rememberSourceFinder(session, cosine)
    // Arriving from the share sheet: look the link up at once, or keep it if offline.
    LaunchedEffect(Unit) {
        if (initialText.isBlank()) return@LaunchedEffect
        if (session.isOnline) finder.look(initialText.trim())
        else ShareText.extractUrl(initialText)?.let {
            finder.looked = initialText.trim() // kept, not looked up
            finder.keepForLater(listOf(it))
        }
    }
    LookAsYouType(finder, input)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TopSpace()
                Text("Add", color = PhaseColor.Text1, style = PhaseType.display(40.sp))
                PhaseField(
                    value = input,
                    onValueChange = { input = it },
                    label = "Paste a link or search",
                    leading = PhaseIcons.Search,
                    onClear = { input = "" },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { finder.look(input.trim()) }),
                )
                FinderStatus(finder)
            }
        }
        finderResults(finder, onCorrect)
    }
}

/**
 * Looking music up in the sources and adding it (§6.10), for the Add page
 * and for Search's + alike: what was found, what's been queued from it,
 * and Cosine's queue of adds, kept fresh while it's on screen.
 */
@Stable
class SourceFinder internal constructor(
    private val session: AccountSession,
    private val cosine: CosineClient,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    var lookup by mutableStateOf<Lookup?>(null)
        private set
    /** The search an album or playlist was opened from, to go back to. */
    var searched by mutableStateOf<Lookup?>(null)
        private set
    /** A playlist's songs also become a playlist in the library unless unticked. */
    var keepPlaylist by mutableStateOf(true)
    /** Adding: the add buttons wait. */
    var busy by mutableStateOf(false)
        private set
    /** Looking up: results may still be coming. */
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageSignal by mutableStateOf(Signal.None)
        private set
    var jobs by mutableStateOf<List<IngestJob>>(emptyList())
        internal set
    /** Links queued from these results, so their rows say so. */
    var queued by mutableStateOf(setOf<String>())
        private set
    internal var refreshJobs by mutableIntStateOf(0)
    /** The text last looked up, so typing back to it doesn't ask again. */
    internal var looked: String? = null
    private var looking: Job? = null

    /** Offline, a link is kept and submitted on reconnect, and the screen says so. */
    fun keepForLater(urls: List<String>) {
        val now = System.currentTimeMillis()
        urls.forEach { graph.pendingShares.add(PendingShare(session.account.id, it, now)) }
        message = "Saved. Submitting when Cosine is reachable."
        messageSignal = Signal.Server
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
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

    fun look(text: String, from: Lookup? = null) {
        if (text.isEmpty()) return
        looked = text
        // Opening an album from results still coming in stops that search.
        looking?.cancel()
        looking = scope.launch {
            searching = true
            message = null
            // A search shows as it goes where Cosine can say (§6.10): the first
            // site's songs in a few seconds, not when the slowest is done.
            val updates = if (session.can(Capability.LOOKUP_STREAM)) cosine.lookupUpdates(text) else flow { emit(cosine.lookup(text)) }
            var first = true
            attempt {
                updates.collect { result ->
                    lookup = result
                    if (first) {
                        first = false
                        searched = from?.copy(partial = false) // stopped, so as far as it got
                        keepPlaylist = true
                    }
                    if (!result.partial && result.items.isEmpty() && result.collections.isEmpty()) {
                        message = "No results."
                        messageSignal = Signal.None
                    }
                }
            }
            // Cut off part way (the connection lost): what came is all there is.
            lookup = lookup?.takeIf { it.partial }?.copy(partial = false) ?: lookup
            searching = false
        }
    }

    /** Nothing to look up: what was found goes. */
    fun reset() {
        looking?.cancel()
        looking = null
        looked = null
        lookup = null
        searched = null
        message = null
        searching = false
    }

    /** Back from an album or playlist to the search it was opened from. */
    fun back() {
        lookup = searched
        searched = null
    }

    fun add(urls: List<String>, force: Boolean = false) {
        // Songs added from an album or playlist say where from: an album
        // lends them its name, a playlist becomes one in the library — even
        // with nothing to fetch, from the songs already here.
        val from = lookup?.takeIf { it.isCollection }?.url
        val playlistOnly = urls.isEmpty() && from != null && lookup?.isPlaylist == true && keepPlaylist
        if (urls.isEmpty() && !playlistOnly) return
        if (!session.isOnline) {
            keepForLater(urls)
            queued = queued + urls
            return
        }
        scope.launch {
            busy = true
            val done = attempt { cosine.submit(urls, force, from, keepPlaylist) }
            if (done == null && message == Voice.serverUnreachable(true)) {
                keepForLater(urls)
                queued = queued + urls
            }
            if (done != null) {
                messageSignal = Signal.None
                message = when {
                    playlistOnly -> "Playlist made from the songs already in your library."
                    done.size == 1 -> "Queued."
                    else -> "Queued ${done.size} links."
                }
                queued = queued + urls
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
}

@Composable
fun rememberSourceFinder(session: AccountSession, cosine: CosineClient): SourceFinder {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val finder = remember(session, cosine) { SourceFinder(session, cosine, graph, scope) }
    // Cosine's queue of adds while this is on screen: quickly while something is moving.
    LaunchedEffect(finder, finder.refreshJobs) {
        while (true) {
            if (session.isOnline) runCatching {
                val before = finder.jobs.filter { it.isActive }.map { it.id }.toSet()
                finder.jobs = cosine.jobs(30)
                // Something just landed: bring it into the library now.
                if (finder.jobs.any { it.id in before && !it.isActive }) session.syncSoon()
            }
            delay(if (finder.jobs.any { it.isActive }) 1_500 else 6_000)
        }
    }
    return finder
}

/**
 * Looks [text] up by itself (§6.10): a link at once, a search once it's
 * three characters long and the typing pauses. Return still looks up
 * straight away.
 */
@Composable
fun LookAsYouType(finder: SourceFinder, text: String) {
    LaunchedEffect(finder, text) {
        val t = text.trim()
        if (t.isEmpty()) {
            finder.reset()
            return@LaunchedEffect
        }
        if (t == finder.looked) return@LaunchedEffect
        val link = ShareText.extractUrl(t) != null
        if (!link && t.length < MIN_SEARCH) return@LaunchedEffect
        if (!link) delay(LOOK_PAUSE_MS)
        finder.look(t)
    }
}

/** Searching the sources costs Cosine a fetch per site: three letters is the least that narrows it. */
const val MIN_SEARCH = 3
private const val LOOK_PAUSE_MS = 500L

/** What the finder is doing, under its field. */
@Composable
fun FinderStatus(finder: SourceFinder, modifier: Modifier = Modifier) {
    val l = finder.lookup
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        finder.message?.let { StatusLine(it, signal = finder.messageSignal) }
        if (finder.searching && (l == null || l.partial)) {
            val link = finder.looked?.let { ShareText.extractUrl(it) } != null
            StatusLine(if (link) "Looking it up…" else "Searching the sources…", signal = Signal.Server)
        }
    }
}

/** The finder's results, then Cosine's recent adds. */
fun LazyListScope.finderResults(finder: SourceFinder, onCorrect: ((String) -> Unit)?) {
    finder.lookup?.let { l ->
        finder.searched?.let {
            item {
                Pill(
                    "Back to search results", finder::back,
                    Modifier.padding(horizontal = 8.dp, vertical = 8.dp), kind = PillKind.Quiet, icon = PhaseIcons.Back, height = 40.dp,
                )
            }
        }
        if (l.isCollection) item { CollectionHeading(l, finder) }
        // Albums and playlists come first when one is what was asked for.
        val collectionsFirst = l.collections.firstOrNull()?.asked == true
        if (collectionsFirst) collectionResults(l) { finder.look(it.url, from = l) }
        if (l.isSearch && l.items.isNotEmpty()) item {
            SectionHeader(if (l.backend == "api") "Songs, from the source APIs" else "Songs")
        }
        // Keyed by position: a result list may repeat a link, and a
        // repeated LazyColumn key crashes.
        itemsIndexed(l.items, key = { i, it -> "r:$i:${it.url}" }) { _, item ->
            ResultRow(item, queued = item.url in finder.queued, enabled = !finder.busy) { finder.add(listOf(item.url)) }
        }
        if (!collectionsFirst) collectionResults(l) { finder.look(it.url, from = l) }
    }

    val jobs = finder.jobs
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
                onRetry = { finder.retry(job, false) },
                onForce = { finder.retry(job, true) },
                onCorrect = if (trackId != null && onCorrect != null) ({ onCorrect(trackId) }) else null,
            )
        }
    }
}

/** What a looked-up album or playlist is, and adding it whole, at the top (§3.1a). */
@Composable
private fun CollectionHeading(l: Lookup, finder: SourceFinder) {
    val count = Voice.songs(l.items.size)
    val keepPlaylist = finder.keepPlaylist
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tag(
            if (l.isAlbum) "Album" else if (l.isPlaylist) "Playlist" else "Collection",
            icon = if (l.isAlbum) PhaseIcons.Album else if (l.isPlaylist) PhaseIcons.Playlist else PhaseIcons.Library,
        )
        Text(l.title ?: "Untitled", color = PhaseColor.Text1, style = PhaseType.display(32.sp))
        if (l.isAlbum) l.artist?.let { Text(it, color = PhaseColor.Text2, style = PhaseType.Body) }
        Text(
            if (l.isPlaylist) "$count. ${Voice.PLAYLIST_FILED}" else "$count. Add them all, or one at a time.",
            color = PhaseColor.Text2, style = PhaseType.Small,
        )
        if (l.isPlaylist) {
            Row(
                Modifier
                    .clip(PhaseShape.Square)
                    .toggleable(value = keepPlaylist, role = Role.Checkbox, onValueChange = { finder.keepPlaylist = it })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Tick(keepPlaylist)
                Text("Keep it as a playlist in my library, with the songs I already have", color = PhaseColor.Text1, style = PhaseType.Small.copy(fontSize = 14.sp))
            }
        }
        val wanted = l.items.filterNot { it.inLibrary || it.url in finder.queued }.map { it.url }
        // Q12: something you already have is refused unless you insist.
        val owned = l.items.filter { it.inLibrary && it.url !in finder.queued }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                wanted.isNotEmpty() -> Pill(
                    if (wanted.size == l.items.size) "Add all ${wanted.size}" else "Add the other ${wanted.size}",
                    { finder.add(wanted) }, kind = PillKind.Primary, icon = PhaseIcons.Plus, enabled = !finder.busy,
                )
                l.isPlaylist && keepPlaylist && owned.isNotEmpty() ->
                    Pill("Make the playlist", { finder.add(emptyList()) }, kind = PillKind.Primary, enabled = !finder.busy)
            }
            if (owned.isNotEmpty()) Pill("Add anyway", { finder.add(owned.map { it.url }, force = true) }, enabled = !finder.busy)
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
        ItemRow(item.title, details, item.thumbnail, end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) }) { onOpen(item) }
    }
}

/**
 * A result with its own add button: one tap queues it. What's queued
 * says so; what's already in the library is marked instead. Whether it's the
 * song as released is Cosine's finding, so it carries Cosine's dot.
 */
@Composable
private fun ResultRow(item: AddItem, queued: Boolean, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier
            .rowSurface(false)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        }
        Box(Modifier.size(width = 56.dp, height = 44.dp), contentAlignment = Alignment.Center) {
            when {
                queued -> PhaseIcon(PhaseIcons.Check, "Queued", size = 20.dp, tone = IconTone.Sine)
                item.inLibrary -> PhaseIcon(PhaseIcons.Library, "In your library", size = 20.dp, tone = IconTone.Faint)
                else -> Pill(null, onAdd, icon = PhaseIcons.Plus, height = 40.dp, enabled = enabled, contentDescription = "Add ${item.title}")
            }
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
                job.isFailed -> Pill(null, onRetry, height = 40.dp, icon = PhaseIcons.Retry, contentDescription = "Retry")
                job.isKnown -> Pill("Add anyway", onForce, height = 40.dp)
            }
            if (onCorrect != null) Pill(null, onCorrect, height = 40.dp, icon = PhaseIcons.Edit, contentDescription = "Correct")
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
