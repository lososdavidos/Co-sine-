package app.sine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.cosine.Candidate
import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.ManualIdentity
import app.sine.core.cosine.ReviewItem
import app.sine.core.cosine.ReviewQuery
import app.sine.core.cosine.TrackIdentity
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.Slab
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.Tag
import app.sine.ui.phase.TagKind
import app.sine.ui.phase.rowInk
import app.sine.ui.phase.rowSurface
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
    // Loads each time the screen is shown, so a correction made on the next
    // screen has left the list when you come back.
    val load = rememberLoad(session) { Sourced(cosine.reviewQueue(), false) }
    val scope = rememberCoroutineScope()

    LoadContent(load) { queue, _ ->
        // Most recently added first; a backlog pages in behind. A refresh
        // starts again from the top.
        var older by remember(queue) { mutableStateOf(emptyList<ReviewItem>()) }
        var fetching by remember(queue) { mutableStateOf(false) }
        var error by remember(queue) { mutableStateOf<String?>(null) }
        // The queue can shift between pages; a repeated key would crash the list.
        val shown = (queue.items + older).distinctBy { it.trackId }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopSpace()
                    Text("Review", color = PhaseColor.Text1, style = PhaseType.display(40.sp))
                    Text(
                        if (shown.isEmpty()) Voice.NOTHING_TO_REVIEW else "${Voice.songs(queue.total)} to review, most recently added first.",
                        color = PhaseColor.Text2, style = PhaseType.Body,
                    )
                }
            }
            items(shown, key = { it.trackId }) { item ->
                ItemRow(
                    title = item.identity.title,
                    subtitle = listOf(item.identity.artist, item.why).joinToString(" · "),
                    art = null,
                    showArt = false,
                    end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
                ) { onOpen(item.trackId) }
            }
            if (shown.size < queue.total) item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Show older (${queue.total - shown.size} more)", {
                        scope.launch {
                            fetching = true
                            error = null
                            cosineCall({ error = it }) { cosine.reviewQueue(offset = shown.size) }?.let { older = older + it.items }
                            fetching = false
                        }
                    }, enabled = !fetching)
                    error?.let { StatusLine(it, signal = Signal.Fault) }
                }
            }
        }
    }
}

/**
 * Correcting one Track: all candidates at once, ranked and labelled by
 * origin, with typing it yourself one tap away rather than buried (§6.12).
 * Picking a candidate selects it; the shelf then offers to use it.
 */
@Composable
fun CorrectionScreen(session: AccountSession, trackId: String, onDone: () -> Unit) {
    val cosine = session.cosine ?: return Message("Review needs a Cosine server.")
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
        session.syncSoon() // the correction moved it: the library should show where to
        say("$what: ${result.identity.line()}.")
        onDone()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TopSpace()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhaseLabel("Correcting")
                    Text(current.identity.title, color = PhaseColor.Text1, style = PhaseType.display(44.sp))
                }
                Slab(padding = 16.dp) {
                    PhaseLabel("As filed now")
                    Text(current.identity.line(), color = PhaseColor.Text1, style = PhaseType.Name)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!current.identity.reviewed) Tag("Unsure", kind = TagKind.Server)
                        Text(current.why, Modifier.weight(1f), color = PhaseColor.Text2, style = PhaseType.Small)
                    }
                    current.files.forEach { f ->
                        Text(
                            listOfNotNull(f.path, Voice.formatDuration(f.durationSec).takeIf { f.durationSec > 0 }, f.sourceUrl)
                                .joinToString(" · "),
                            color = PhaseColor.Text3, style = PhaseType.Data,
                        )
                    }
                }
                message?.let { StatusLine(it, signal = Signal.Fault) }
                if (!current.identity.reviewed) {
                    Pill("It's right as it is", {
                        scope.launch {
                            busy = true
                            finish(cosineCall({ message = it }) { cosine.confirm(trackId) }, "Confirmed")
                            busy = false
                        }
                    }, enabled = !busy)
                }
            }
        }
        item {
            Candidates(cosine, current, busy = busy) { query, candidate ->
                scope.launch {
                    busy = true
                    finish(cosineCall({ message = it }) { cosine.choose(trackId, query, candidate.key) }, "Corrected")
                    busy = false
                }
            }
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
    var picked by remember { mutableStateOf<Candidate?>(null) }

    LaunchedEffect(searches) {
        error = null
        results = null
        picked = null
        results = cosineCall({ error = it }) { cosine.candidates(item.trackId, query) } ?: emptyList()
    }

    // A picked match is a selection: the shelf offers to use it.
    ShowSelection(
        picked?.let { c ->
            ShelfSelection(
                "1 match selected",
                listOf(ShelfAction("Use this match", PillKind.Primary) { if (!busy) onChoose(query, c) }),
                onClear = { picked = null },
                note = "Moves the file, for every account.",
            )
        },
    )

    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Matches")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PhaseField(artist, { artist = it }, label = "Artist")
            PhaseField(title, { title = it }, label = "Title")
            PhaseField(album, { album = it }, label = "Album")
            Pill("Search the catalogues", {
                query = ReviewQuery(artist.trim(), title.trim(), album.trim())
                searches++
            }, icon = PhaseIcons.Search)
        }

        val list = results
        when {
            error != null -> StatusLine(error!!, Modifier.padding(horizontal = 20.dp), signal = Signal.Fault)
            list == null -> Text("Searching…", Modifier.padding(horizontal = 20.dp), color = PhaseColor.Text2, style = PhaseType.Body)
            list.isEmpty() -> Text("No matches. For unreleased material, type the details below.", Modifier.padding(horizontal = 20.dp), color = PhaseColor.Text2, style = PhaseType.Body)
            else -> Column {
                SectionLabel("Candidates", end = "Ranked together")
                list.forEach { c ->
                    val on = picked == c
                    Row(
                        Modifier
                            .rowSurface(on)
                            .selectable(selected = on, onClick = { picked = if (on) null else c })
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.title, color = rowInk(on, false), style = PhaseType.Name.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
                            Text(
                                listOfNotNull(
                                    c.trackArtist ?: c.artist,
                                    c.release.takeIf { it != c.title },
                                    c.year.takeIf { it > 0 }?.toString(),
                                ).joinToString(" · "),
                                color = rowInk(on, false, secondary = true), style = PhaseType.Small,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${(c.confidence * 100).toInt()}%", color = rowInk(on, false, secondary = true), style = PhaseType.Data)
                            PhaseLabel(Voice.sourceName(c.source), color = rowInk(on, false, secondary = true))
                        }
                    }
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

    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // For unreleased material this isn't a fallback; it's the only honest answer (§6.12).
        SectionHeader("Type it yourself")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PhaseField(artist, { artist = it }, label = "Artist")
            PhaseField(title, { title = it }, label = "Title")
            PhaseField(release, { release = it }, label = "Release", placeholder = "Empty for a single")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhaseField(trackNo, { trackNo = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "Track", keyboardOptions = number)
                PhaseField(year, { year = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "Year", keyboardOptions = number)
            }
            Pill("Save", {
                onSave(ManualIdentity(artist.trim(), title.trim(), release.trim(), trackNo.toIntOrNull() ?: 0, year.toIntOrNull() ?: 0))
            }, enabled = !busy && artist.isNotBlank() && title.isNotBlank())
            Text("Corrections apply for every account.", color = PhaseColor.Text2, style = PhaseType.Small)
        }
    }
}
