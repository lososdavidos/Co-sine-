package app.sine.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.SmartPlaylist
import app.sine.core.model.DownloadNode
import app.sine.core.model.Playlist
import app.sine.core.model.PlaylistEdits
import app.sine.core.model.SmartRules
import app.sine.core.model.Track
import app.sine.core.subsonic.SubsonicException
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.NotAvailableOffline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

// Playlists (§2.4): your own, in the order you give them, and smart ones,
// whose songs a rule over your own listening picks.

/**
 * Makes a change on the server and says what happened, or why it didn't.
 * [block] returns what to say on success, or null to say nothing.
 */
internal suspend fun attemptChange(context: Context, session: AccountSession, block: suspend () -> String?): Boolean {
    val (ok, said) = try {
        true to block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SubsonicException) {
        false to (e.message ?: "The server refused that.")
    } catch (e: CosineException) {
        false to (e.message ?: "Cosine refused that.")
    } catch (e: NotAvailableOffline) {
        false to "Playlists need the server. Reconnect to change them."
    } catch (e: IOException) {
        false to Voice.serverUnreachable(session.account.isCosine)
    }
    if (said != null) withContext(Dispatchers.Main) { Toast.makeText(context, said, Toast.LENGTH_SHORT).show() }
    return ok
}

@Composable
internal fun PlaylistList(session: AccountSession, nav: NavController) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val changed by session.playlistsChanged.collectAsStateWithLifecycle()
    // Compat mode has no local mirror, so playlists need the server (G2 degrades, §2.1).
    val load = rememberLoad(session, refreshOn = changed) { session.playlists() }
    var naming by remember { mutableStateOf(false) }
    var smart by remember { mutableStateOf<SmartPlaylist?>(null) }
    LoadContent(load) { playlists, _ ->
        LazyColumn(Modifier.fillMaxSize()) {
            if (session.canEditPlaylists) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { naming = true }) { Text("New playlist") }
                    if (session.canEditSmart) {
                        OutlinedButton(onClick = { smart = SmartRules.starting("added") }) { Text("New smart playlist") }
                    }
                }
            }
            if (playlists.isEmpty()) item { Message("No playlists.") }
            items(playlists, key = { it.id }) { p ->
                ItemRow(p.name, p.subtitle(), session.artwork(p.coverArt, 150)) { nav.navigate(Routes.playlist(p.id)) }
            }
        }
    }
    if (naming) NameDialog("New playlist", "", "Create", onDismiss = { naming = false }) { name ->
        scope.launch { if (attemptChange(context, session) { session.createPlaylist(name); null }) naming = false }
    }
    smart?.let { SmartPlaylistDialog(session, it, onDismiss = { smart = null }) }
}

@Composable
fun PlaylistScreen(session: AccountSession, id: String, nav: NavController) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val changed by session.playlistsChanged.collectAsStateWithLifecycle()
    val load = rememberLoad(session, id, refreshOn = changed) { session.playlist(id) }
    var renaming by remember { mutableStateOf(false) }
    var savingAs by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var rule by remember { mutableStateOf<SmartPlaylist?>(null) }
    // While putting the playlist in order: the order so far, saved in one go.
    var order by remember(id) { mutableStateOf<List<Track>?>(null) }

    LoadContent(load) { detail, _ ->
        val p = detail.playlist
        val editable = !p.readonly && session.canEditPlaylists
        val tracks = order ?: detail.tracks
        TrackListScreen(
            session = session,
            title = p.name,
            subtitle = p.subtitle(),
            coverArt = p.coverArt,
            tracks = tracks,
            node = DownloadNode.OfPlaylist(p.id),
            showTrackArtist = true,
            nav = nav,
            empty = Voice.EMPTY_PLAYLIST,
            onRemoveFromPlaylist = if (editable) { positions ->
                scope.launch {
                    attemptChange(context, session) {
                        session.removeFromPlaylist(p.id, positions)
                        Voice.removedFromPlaylist(positions.size)
                    }
                }
            } else null,
            onMove = order?.let { current -> { from, to -> order = PlaylistEdits.moved(current, from, to) } },
        ) {
            when {
                order != null -> {
                    OutlinedButton(onClick = {
                        val ids = tracks.map { it.id }
                        scope.launch { if (attemptChange(context, session) { session.reorderPlaylist(p.id, ids); null }) order = null }
                    }) { Text("Save order") }
                    TextButton(onClick = { order = null }) { Text("Cancel") }
                }
                editable -> {
                    if (tracks.size > 1) OutlinedButton(onClick = { order = detail.tracks }) { Text("Reorder") }
                    OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { deleting = true }) { Text("Delete") }
                }
                p.readonly -> {
                    if (p.isCosineSmart && session.canEditSmart) OutlinedButton(onClick = {
                        scope.launch {
                            attemptChange(context, session) {
                                rule = session.smartPlaylists().firstOrNull { it.id == p.id }
                                null
                            }
                        }
                    }) { Text("Edit rule") }
                    // A smart playlist is never shared or hand-edited; this
                    // is the honest step to one that can be (§2.4).
                    if (session.canEditPlaylists && tracks.isNotEmpty()) {
                        OutlinedButton(onClick = { savingAs = true }) { Text("Save as playlist") }
                    }
                    if (p.isCosineSmart && session.canEditPlaylists) TextButton(onClick = { deleting = true }) { Text("Delete") }
                }
            }
        }

        if (renaming) NameDialog("Rename playlist", p.name, "Rename", onDismiss = { renaming = false }) { name ->
            scope.launch { if (attemptChange(context, session) { session.renamePlaylist(p.id, name); null }) renaming = false }
        }
        if (savingAs) NameDialog("Save as playlist", p.name, "Save", onDismiss = { savingAs = false }) { name ->
            val ids = tracks.map { it.id }
            scope.launch {
                if (attemptChange(context, session) { session.createPlaylist(name, ids); "Saved as a playlist." }) savingAs = false
            }
        }
        if (deleting) AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(Voice.deletePlaylistQuestion(p.name)) },
            text = { Text(Voice.DELETE_PLAYLIST_EXPLAINED) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch {
                        if (attemptChange(context, session) { session.deletePlaylist(p.id); "Playlist deleted." }) {
                            withContext(Dispatchers.Main) { nav.popBackStack() }
                        }
                    }
                }) { Text("Delete playlist") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } },
        )
        rule?.let { SmartPlaylistDialog(session, it, onDismiss = { rule = null }) }
    }
}

/** A name for a playlist: a new one, a renamed one, a smart one saved as a playlist. */
@Composable
internal fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Adds songs to one of your playlists, or to a new one named here. Stays
 * open while it works, so the work isn't lost when it closes.
 */
@Composable
internal fun AddToPlaylistDialog(session: AccountSession, tracks: List<Track>, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ids = tracks.map { it.id }
    LaunchedEffect(Unit) {
        attemptChange(context, session) {
            val found = session.playlists()
            if (found.fromDownloads) throw NotAvailableOffline()
            lists = found.value.filterNot { it.readonly }
            null
        }.also { if (!it) problem = "Playlists need the server." }
    }
    fun finish(work: suspend () -> String) {
        busy = true
        scope.launch {
            if (attemptChange(context, session) { work() }) {
                onAdded()
                onDismiss()
            }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tracks.size == 1) "Add 1 song to" else "Add ${tracks.size} songs to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val l = lists) {
                    null -> Text(problem ?: "Loading…")
                    else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(l, key = { it.id }) { p ->
                            Text(
                                p.name,
                                Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    finish { session.addToPlaylist(p.id, ids); Voice.addedToPlaylist(ids.size, p.name) }
                                }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            HorizontalDivider()
                        }
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("New playlist") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                finish { session.createPlaylist(n, ids); Voice.addedToPlaylist(ids.size, n) }
            }, enabled = name.isNotBlank() && !busy) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A smart playlist's name and rule (§2.4): a rule, and its one setting. */
@Composable
internal fun SmartPlaylistDialog(session: AccountSession, initial: SmartPlaylist, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(initial) }
    // A new one is named after its rule until you name it yourself.
    var named by remember { mutableStateOf(initial.id.isNotEmpty()) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "New smart playlist" else "Edit smart playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = p.name, onValueChange = { p = p.copy(name = it); named = true }, singleLine = true,
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                )
                Text("Songs", style = MaterialTheme.typography.labelLarge)
                Chips(SmartRules.all.map { it.key to it.label }, p.rule) { key ->
                    p = SmartRules.withSetting(p.copy(rule = key, name = if (named) p.name else SmartRules.label(key)))
                }
                when (p.rule) {
                    "most-played" -> Chips(SmartRules.spans, p.span) { p = p.copy(span = it) }
                    "forgotten" -> {
                        Text("Not played in", style = MaterialTheme.typography.labelLarge)
                        Chips(SmartRules.months.map { it to SmartRules.monthsLabel(it) }, p.months) { p = p.copy(months = it) }
                    }
                    "added" -> {
                        Text("Added in the last", style = MaterialTheme.typography.labelLarge)
                        Chips(SmartRules.days.map { it to SmartRules.daysLabel(it) }, p.days) { p = p.copy(days = it) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    if (attemptChange(context, session) { session.saveSmart(p); null }) onDismiss()
                    busy = false
                }
            }, enabled = p.name.isNotBlank() && !busy) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}
