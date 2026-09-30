package app.sine.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sine.core.cosine.Candidate
import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.ManualIdentity
import app.sine.core.cosine.ReviewItem
import app.sine.core.cosine.ReviewQuery
import app.sine.core.cosine.TrackIdentity
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** Runs a Cosine call, turning failures into the message to show. */
private suspend fun <T> cosineCall(onError: (String) -> Unit, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: CosineException) {
    onError(e.message ?: "Cosine refused that.")
    null
} catch (e: IOException) {
    onError(Voice.serverUnreachable(isCosine = true))
    null
}

fun TrackIdentity.line(): String =
    listOf(artist, release.takeIf { it != title }, title).filterNotNull().joinToString(" — ")

/**
 * The review queue (§6.12): a list, tapped to correct. It scales to the
 * backlog a bulk ingest leaves, which one-at-a-time wouldn't.
 */
@Composable
fun ReviewScreen(session: AccountSession, onOpen: (String) -> Unit) {
    val cosine = session.cosine ?: return Message("Review needs a Cosine server.")
    var items by remember { mutableStateOf<List<ReviewItem>?>(null) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    // Runs each time the screen is shown, so a correction made on the next
    // screen has left the list when you come back.
    LaunchedEffect(Unit) {
        cosineCall({ error = it }) { cosine.reviewQueue() }?.let {
            items = it.items
            total = it.total
        }
    }

    val list = items
    when {
        error != null && list == null -> Message(error!!)
        list == null -> Message("Loading…")
        list.isEmpty() -> Message(Voice.NOTHING_TO_REVIEW)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    if (total == 1) "1 track to review." else "$total tracks to review.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(list, key = { it.trackId }) { item ->
                ItemRow(
                    title = item.identity.title,
                    subtitle = listOf(item.identity.artist, item.why).joinToString(" · "),
                    art = null,
                    showArt = false,
                ) { onOpen(item.trackId) }
            }
        }
    }
}

/**
 * Correcting one Track: all candidates at once, ranked and labelled by
 * origin, with typing it yourself one tap away rather than buried (§6.12).
 */
@Composable
fun CorrectionScreen(session: AccountSession, trackId: String, onDone: () -> Unit) {
    val cosine = session.cosine ?: return Message("Review needs a Cosine server.")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<ReviewItem?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(trackId) {
        cosineCall({ message = it }) { cosine.reviewItem(trackId) }?.let { item = it }
    }
    val current = item ?: return Message(message ?: "Loading…")

    fun finish(result: ReviewItem?, what: String) {
        if (result == null) return
        Toast.makeText(context, "$what: ${result.identity.line()}.", Toast.LENGTH_SHORT).show()
        onDone()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(current.identity.title, style = MaterialTheme.typography.headlineSmall)
                Text(current.identity.artist)
                if (current.identity.release != current.identity.title) Text(current.identity.release)
                Text(current.why, style = MaterialTheme.typography.bodySmall)
                current.files.forEach { f ->
                    Text(
                        listOfNotNull(f.path, Voice.formatDuration(f.durationSec).takeIf { f.durationSec > 0 }, f.sourceUrl)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!current.identity.reviewed) {
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            finish(cosineCall({ message = it }) { cosine.confirm(trackId) }, "Confirmed")
                            busy = false
                        }
                    }) { Text("It's right as it is") }
                }
            }
            HorizontalDivider()
        }
        item {
            Candidates(cosine, current, busy = busy) { query, candidate ->
                scope.launch {
                    busy = true
                    finish(cosineCall({ message = it }) { cosine.choose(trackId, query, candidate.key) }, "Corrected")
                    busy = false
                }
            }
            HorizontalDivider()
        }
        item {
            ManualEntry(current.identity, busy = busy) { typed ->
                scope.launch {
                    busy = true
                    finish(cosineCall({ message = it }) { cosine.correct(trackId, typed) }, "Saved")
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun Candidates(
    cosine: CosineClient,
    item: ReviewItem,
    busy: Boolean,
    onChoose: (ReviewQuery, Candidate) -> Unit,
) {
    val id = item.identity
    var artist by rememberSaveable { mutableStateOf(id.trackArtist ?: id.artist) }
    var title by rememberSaveable { mutableStateOf(id.title) }
    var album by rememberSaveable { mutableStateOf(if (id.release != id.title) id.release else "") }
    var results by remember { mutableStateOf<List<Candidate>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searches by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf(ReviewQuery()) }

    LaunchedEffect(searches) {
        error = null
        results = null
        results = cosineCall({ error = it }) { cosine.candidates(item.trackId, query) } ?: emptyList()
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Matches")
        OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            query = ReviewQuery(artist.trim(), title.trim(), album.trim())
            searches++
        }) { Text("Search MusicBrainz") }

        val list = results
        when {
            error != null -> Text(error!!)
            list == null -> Text("Searching…")
            list.isEmpty() -> Text("No matches. For unreleased material, type the details below.")
            else -> list.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(c.artist, c.release.takeIf { it != c.title }, c.title).joinToString(" — "))
                        Text(
                            listOfNotNull(
                                c.trackArtist,
                                c.year.takeIf { it > 0 }?.toString(),
                                "${(c.confidence * 100).toInt()}% match",
                                if (c.source == "musicbrainz") "MusicBrainz" else c.source,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(enabled = !busy, onClick = { onChoose(query, c) }) { Text("Use this") }
                }
            }
        }
    }
}

@Composable
private fun ManualEntry(id: TrackIdentity, busy: Boolean, onSave: (ManualIdentity) -> Unit) {
    var artist by rememberSaveable { mutableStateOf(id.artist) }
    var title by rememberSaveable { mutableStateOf(id.title) }
    var release by rememberSaveable { mutableStateOf(if (id.release != id.title) id.release else "") }
    var trackNo by rememberSaveable { mutableStateOf(if (id.trackNo > 0) id.trackNo.toString() else "") }
    var year by rememberSaveable { mutableStateOf(if (id.year > 0) id.year.toString() else "") }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Type it yourself")
        OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            release, { release = it }, label = { Text("Release") }, placeholder = { Text("Empty for a single") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(trackNo, { trackNo = it.filter(Char::isDigit) }, label = { Text("Track") },
                singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            OutlinedTextField(year, { year = it.filter(Char::isDigit) }, label = { Text("Year") },
                singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
        }
        Button(
            enabled = !busy && artist.isNotBlank() && title.isNotBlank(),
            onClick = {
                onSave(ManualIdentity(artist.trim(), title.trim(), release.trim(), trackNo.toIntOrNull() ?: 0, year.toIntOrNull() ?: 0))
            },
        ) { Text("Save") }
        Text("Corrections apply for every account.", style = MaterialTheme.typography.bodySmall)
    }
}
