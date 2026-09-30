package app.sine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun AddScreen(session: AccountSession, initialText: String) {
    val account by session.accountFlow.collectAsStateWithLifecycle()
    val cosine = session.cosine
    when {
        cosine == null -> Message("Adding music needs a Cosine server.")
        Capability.INGEST !in account.kind.capabilities ->
            Message("This Cosine can't add music yet: yt-dlp is not installed on the server.")
        else -> AddContent(session, cosine, initialText)
    }
}

@Composable
private fun AddContent(session: AccountSession, cosine: CosineClient, initialText: String) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf(initialText) }
    var lookup by remember { mutableStateOf<Lookup?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var jobs by remember { mutableStateOf<List<IngestJob>>(emptyList()) }
    var refreshJobs by remember { mutableStateOf(0) }

    /** Offline, a link is kept and submitted on reconnect, and the screen says so. */
    fun keepForLater(urls: List<String>) {
        val now = System.currentTimeMillis()
        urls.forEach { graph.pendingShares.add(PendingShare(session.account.id, it, now)) }
        message = "Saved. Submitting when Cosine is reachable."
    }

    suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CosineException) {
        message = e.message
        null
    } catch (e: IOException) {
        message = Voice.serverUnreachable(isCosine = true)
        null
    }

    fun look() {
        val text = input.trim()
        if (text.isEmpty()) return
        scope.launch {
            busy = true
            message = null
            val result = attempt { cosine.lookup(text) }
            if (result != null) {
                lookup = result
                // A collection is queued whole unless you untick things; search results start unticked.
                selected = if (result.isSearch) emptySet() else result.items.filterNot { it.inLibrary }.map { it.url }.toSet()
                if (result.items.isEmpty()) message = "No results."
            }
            busy = false
        }
    }

    fun add(urls: List<String>, force: Boolean = false) {
        if (urls.isEmpty()) return
        if (!session.isOnline) {
            keepForLater(urls)
            return
        }
        scope.launch {
            busy = true
            val queued = attempt { cosine.submit(urls, force) }
            if (queued == null && message == Voice.serverUnreachable(true)) keepForLater(urls)
            if (queued != null) {
                message = if (queued.size == 1) "Queued." else "Queued ${queued.size} links."
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
            if (session.isOnline) runCatching { jobs = cosine.jobs(30) }
            delay(if (jobs.any { it.isActive }) 1_500 else 6_000)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Paste a link or search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { look() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { look() }, enabled = input.isNotBlank() && !busy) {
                    Text(if (busy) "Working…" else "Look up")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        lookup?.let { l ->
            item {
                val heading = when {
                    l.isCollection -> "${l.title ?: "Collection"}: ${l.items.size} tracks"
                    l.isSearch -> if (l.backend == "api") "From the source APIs" else "From yt-dlp search"
                    else -> null
                }
                heading?.let { SectionHeader(it) }
            }
            items(l.items, key = { "r:" + it.url }) { item ->
                ResultRow(item, checked = item.url in selected) {
                    selected = if (item.url in selected) selected - item.url else selected + item.url
                }
            }
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { add(selected.toList()) }, enabled = selected.isNotEmpty() && !busy) {
                        Text(if (selected.size == 1) "Add" else "Add ${selected.size}")
                    }
                    // Q12: something you already have is refused unless you insist.
                    val owned = l.items.filter { it.inLibrary }
                    if (!l.isSearch && owned.isNotEmpty() && selected.isEmpty()) {
                        OutlinedButton(onClick = { add(owned.map { it.url }, force = true) }, enabled = !busy) {
                            Text("Add anyway")
                        }
                    }
                }
                HorizontalDivider()
            }
        }

        if (jobs.isNotEmpty()) {
            item { SectionHeader("Fetching") }
            items(jobs, key = { "j:" + it.id }) { job -> JobRow(job, onRetry = { retry(job, false) }, onForce = { retry(job, true) }) }
        }
    }
}

@Composable
private fun ResultRow(item: AddItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Art(item.thumbnail, 48.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                item.uploader.ifBlank { null },
                item.durationSec.takeIf { it > 0 }?.let { Voice.formatDuration(it) },
                item.plays.takeIf { it > 0 }?.let { "$it plays" },
                item.source.ifBlank { null },
                if (item.inLibrary) "In your library" else null,
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun JobRow(job: IngestJob, onRetry: () -> Unit, onForce: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(jobStatus(job), style = MaterialTheme.typography.bodySmall)
        }
        when {
            job.isFailed -> TextButton(onClick = onRetry) { Text("Retry") }
            job.isKnown -> TextButton(onClick = onForce) { Text("Add anyway") }
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
