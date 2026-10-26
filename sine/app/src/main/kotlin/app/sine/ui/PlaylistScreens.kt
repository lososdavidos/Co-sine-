package app.sine.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.SmartPlaylist
import app.sine.core.home.TileKind
import app.sine.core.model.DownloadNode
import app.sine.core.model.Playlist
import app.sine.core.model.PlaylistEdits
import app.sine.core.model.SmartRules
import app.sine.core.model.Track
import app.sine.core.subsonic.SubsonicException
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.NotAvailableOffline
import app.sine.data.PlaylistChanged
import app.sine.data.PlaylistGone
import app.sine.ui.phase.Chip
import app.sine.ui.phase.Initial
import app.sine.ui.phase.People
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.Slab
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.Tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

// Playlists (§2.4): your own, in the order you give them, and smart ones,
// whose songs a rule over your own listening picks.

/**
 * Makes a change on the server and says what happened, or why it didn't, in
 * the shelf. [block] returns what to say on success, or null to say nothing;
 * for a playlist [edit] made with no connection, that it's kept here until
 * Cosine has it (§5.2).
 */
@Suppress("UNUSED_PARAMETER")
internal suspend fun attemptChange(
    context: Context,
    session: AccountSession,
    edit: Boolean = false,
    block: suspend () -> String?,
): Boolean {
    var signal = Signal.Fault
    val (ok, said) = try {
        true to (block() ?: Voice.PLAYLIST_SAVED_OFFLINE.takeIf { edit && session.editsPlaylistsOffline && !session.isOnline })
    } catch (e: CancellationException) {
        throw e
    } catch (e: SubsonicException) {
        false to (e.message ?: "The server refused that.")
    } catch (e: CosineException) {
        false to (e.message ?: "Cosine refused that.")
    } catch (e: NotAvailableOffline) {
        signal = Signal.Server
        false to "That needs the server. Reconnect to do it."
    } catch (e: PlaylistGone) {
        false to e.message
    } catch (e: PlaylistChanged) {
        false to e.message
    } catch (e: IOException) {
        signal = Signal.Server
        false to Voice.serverUnreachable(session.account.isCosine)
    }
    // Kept for Cosine is the server's side of things; anything else that worked is plain.
    if (ok) signal = if (said == Voice.PLAYLIST_SAVED_OFFLINE) Signal.Server else Signal.None
    if (said != null) say(said, signal)
    return ok
}

@Composable
internal fun PlaylistList(session: AccountSession, nav: Pages) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val changed by session.playlistsChanged.collectAsStateWithLifecycle()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    // From the mirror when Cosine keeps playlists in it; otherwise (compat
    // mode) they need the server (G2 degrades, §2.1).
    val load = rememberLoad(session, refreshOn = changed to mirrored) { session.playlists() }
    var answering by remember { mutableStateOf<String?>(null) } // an invitation being answered
    LoadContent(load) { playlists, _ ->
        val invites = session.invites()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(top = 4.dp)) {
            if (session.canEditPlaylists) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("New playlist", {
                        nav.form {
                            NameForm("New playlist", "", "Create") { name ->
                                attemptChange(context, session, edit = true) { session.createPlaylist(name); null }
                            }
                        }
                    }, kind = PillKind.Outline, icon = PhaseIcons.Plus, height = 40.dp)
                    if (session.canEditSmart) {
                        Pill("New smart playlist", { nav.form { SmartPlaylistForm(session, SmartRules.starting("added")) } }, kind = PillKind.Outline, icon = PhaseIcons.Plus, height = 40.dp)
                    }
                }
            }
            if (invites.isNotEmpty()) item { SectionLabel("Shared with you") }
            items(invites, key = { "invite-" + it.id }) { inv ->
                // An invitation is a raised slab above your own playlists. Deciding is your next action, so Accept is Sine.
                Slab(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(inv.name, color = PhaseColor.Text1, style = PhaseType.Name.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                            Text(Voice.invitation(inv.owner, inv.songs), color = PhaseColor.Text2, style = PhaseType.Small)
                        }
                        Initial(inv.owner, size = 32.dp)
                    }
                    Text(Voice.ACCEPT_EXPLAINED, color = PhaseColor.Text2, style = PhaseType.Small)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Accept", {
                            answering = inv.id
                            scope.launch {
                                attemptChange(context, session) { Voice.accepted(session.accept(inv.id)) }
                                answering = null
                            }
                        }, kind = PillKind.Primary, height = 44.dp, enabled = answering == null)
                        Pill("Decline", {
                            answering = inv.id
                            scope.launch {
                                attemptChange(context, session) { session.decline(inv.id); "Declined." }
                                answering = null
                            }
                        }, kind = PillKind.Quiet, height = 44.dp, enabled = answering == null)
                    }
                }
            }
            if (playlists.isEmpty() && invites.isEmpty()) item { Message("No playlists.") }
            if (invites.isNotEmpty() && playlists.isNotEmpty()) item { SectionLabel("Your playlists") }
            items(playlists, key = { it.id }) { p ->
                val members = members(session, p)
                ItemRow(
                    p.name, p.subtitle(), session.artwork(p.coverArt, 150),
                    showArt = !(p.readonly && p.coverArt == null),
                    leading = if (p.readonly && p.coverArt == null) ({ SmartMark() }) else null,
                    end = if (members.size > 1) ({ People(members) }) else null,
                ) { nav.navigate(Routes.playlist(p.id)) }
            }
        }
    }
}

/** Who's on a playlist, the owner first (§2.3). */
private fun members(session: AccountSession, p: Playlist): List<String> {
    val me = session.account.username
    return if (p.mine) listOf(me) + p.sharedWith else listOfNotNull(p.owner, me)
}

/** A smart playlist's art, when it has none: worked out, not chosen. */
@Composable
private fun SmartMark() {
    Box(
        Modifier.size(44.dp).clip(PhaseShape.Square).background(PhaseColor.Raised),
        contentAlignment = Alignment.Center,
    ) { Icon(PhaseIcons.Wave, null, Modifier.size(28.dp), tint = PhaseColor.Text2) }
}

@Composable
fun PlaylistScreen(session: AccountSession, id: String, nav: Pages) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val shelf = LocalShelf.current
    val changed by session.playlistsChanged.collectAsStateWithLifecycle()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val load = rememberLoad(session, id, refreshOn = changed to mirrored) { session.playlist(id) }
    // While putting the playlist in order: the order so far, as positions
    // in the playlist as listed, saved in one go.
    var order by remember(id) { mutableStateOf<List<Int>?>(null) }

    LoadContent(load) { detail, _ ->
        val p = detail.playlist
        val editable = !p.readonly && session.canEditPlaylists
        val tracks = order?.mapNotNull { detail.tracks.getOrNull(it) } ?: detail.tracks
        val members = members(session, p)
        val unsent = session.unsentEdits(p.id)

        fun askDelete() {
            shelf.ask(
                ShelfQuestion(
                    if (p.mine) Voice.deletePlaylistQuestion(p.name) else Voice.leavePlaylistQuestion(p.name),
                    if (p.mine) Voice.DELETE_PLAYLIST_EXPLAINED else Voice.leavePlaylistExplained(p.owner),
                    listOf(
                        ShelfAction("Keep") {},
                        ShelfAction(if (p.mine) "Delete playlist" else "Leave playlist", PillKind.Fault) {
                            scope.launch {
                                val said = if (p.mine) "Playlist deleted." else "Left the playlist."
                                if (attemptChange(context, session) { session.deletePlaylist(p.id); said }) nav.popBackStack()
                            }
                        },
                    ),
                ),
            )
        }

        TrackListScreen(
            session = session,
            title = p.name,
            kind = if (p.readonly) "Smart playlist" else "Playlist",
            coverArt = p.coverArt,
            tracks = tracks,
            node = DownloadNode.OfPlaylist(p.id),
            showTrackArtist = true,
            nav = nav,
            meta = listOfNotNull(
                p.comment?.takeIf { p.readonly && it.isNotBlank() },
                if (p.readonly) null else if (p.mine) "Yours" else p.owner?.let { "$it's" },
            ),
            people = if (members.size > 1) members else emptyList(),
            peopleLine = when {
                !p.mine && p.owner != null -> Voice.sharedBy(p.owner!!)
                p.sharedWith.isNotEmpty() -> Voice.sharedWith(p.sharedWith)
                else -> null
            },
            status = unsent.takeIf { it > 0 }?.let { if (it == 1) "1 change not sent yet." else "$it changes not sent yet." },
            empty = Voice.EMPTY_PLAYLIST,
            onRemoveFromPlaylist = if (editable && order == null) { positions ->
                scope.launch {
                    attemptChange(context, session, edit = true) {
                        session.removeFromPlaylist(p.id, positions, detail.tracks)
                        Voice.removedFromPlaylist(positions.size)
                    }
                }
            } else null,
            onMove = order?.let { current -> { from, to -> order = PlaylistEdits.moved(current, from, to) } },
            quietActions = {
                if (order == null) PinToHome(session, TileKind.PLAYLIST, p.id, p.name)
                when {
                    order != null -> Unit
                    editable -> {
                        if (tracks.size > 1) Pill("Reorder", { order = detail.tracks.indices.toList() }, kind = PillKind.Quiet, height = 40.dp)
                        Pill("Rename", {
                            nav.form {
                                NameForm("Rename playlist", p.name, "Rename") { name ->
                                    attemptChange(context, session, edit = true) { session.renamePlaylist(p.id, name); null }
                                }
                            }
                        }, kind = PillKind.Quiet, height = 40.dp)
                        Pill(if (p.mine) "Delete" else "Leave", ::askDelete, kind = PillKind.QuietFault, height = 40.dp)
                    }
                    p.readonly && p.isCosineSmart && session.canEditPlaylists ->
                        Pill("Delete", ::askDelete, kind = PillKind.QuietFault, height = 40.dp)
                }
            },
        ) {
            when {
                order != null -> {
                    val wanted = order.orEmpty()
                    Pill("Save order", {
                        scope.launch {
                            if (attemptChange(context, session, edit = true) { session.reorderPlaylist(p.id, detail.tracks, wanted); null }) order = null
                        }
                    }, kind = PillKind.Primary)
                    Pill("Cancel", { order = null }, kind = PillKind.Quiet)
                }
                editable -> {
                    if (p.mine && session.canShare) Pill("Share", { nav.form { ShareForm(session, p.id) } })
                }
                p.readonly -> {
                    if (p.isCosineSmart && session.canEditSmart) Pill("Edit rule", {
                        scope.launch {
                            attemptChange(context, session) {
                                session.smartRule(p.id)?.let { rule -> nav.form { SmartPlaylistForm(session, rule) } }
                                null
                            }
                        }
                    })
                    // A smart playlist is never shared or hand-edited; this
                    // is the honest step to one that can be (§2.4).
                    if (session.canEditPlaylists && tracks.isNotEmpty()) Pill("Save as playlist", {
                        val ids = tracks.map { it.id }
                        nav.form {
                            NameForm("Save as playlist", p.name, "Save") { name ->
                                attemptChange(context, session) { session.createPlaylist(name, ids); "Saved as a playlist." }
                            }
                        }
                    })
                }
            }
        }

    }
}

/**
 * A small form, as a page (§6.5): it slides in from the right like any
 * other, says what it's for in its title, and ends in the one thing it
 * does, in Sine. Back cancels.
 */
@Composable
internal fun FormPage(
    title: String,
    action: String? = null,
    enabled: Boolean = true,
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // A form has the screen to itself, shelf and all, so what Sine says goes here instead.
    val message by Messages.current.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopSpace()
            Text(title, color = PhaseColor.Text1, style = PhaseType.display(40.sp))
            content()
            Spacer(Modifier.height(8.dp))
        }
        message?.let { m -> StatusLine(m.text, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), signal = m.signal) }
        if (action != null) {
            Pill(action, onAction, Modifier.fillMaxWidth().padding(16.dp), kind = PillKind.Primary, enabled = enabled, height = 52.dp)
        }
    }
}

/**
 * Who a playlist is shared with (§2.3), and sharing it with someone else.
 * People are circles. The other accounts come from the server, so this
 * needs a connection. It follows the playlist as shares change.
 */
@Composable
internal fun ShareForm(session: AccountSession, id: String) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val changed by session.playlistsChanged.collectAsStateWithLifecycle()
    val mirrored by session.mirrored.collectAsStateWithLifecycle()
    val load = rememberLoad(session, id, refreshOn = changed to mirrored) { session.playlist(id) }
    var accounts by remember { mutableStateOf<List<String>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        attemptChange(context, session) { accounts = session.accounts(); null }.also { if (!it) problem = "Sharing needs the server." }
    }
    fun act(work: suspend () -> String) {
        busy = true
        scope.launch {
            attemptChange(context, session) { work() }
            busy = false
        }
    }
    FormPage("Share") {
        val playlist = (load.load as? Load.Ready)?.value?.playlist ?: run {
            Text((load.load as? Load.Failed)?.message ?: "Loading…", color = PhaseColor.Text2, style = PhaseType.Small)
            return@FormPage
        }
        val on = playlist.sharedWith + playlist.invited
        Text(playlist.name, color = PhaseColor.Text1, style = PhaseType.Name.copy(fontWeight = FontWeight.SemiBold))
        Text("Whoever accepts gets its songs in their library, and can change it too.", color = PhaseColor.Text2, style = PhaseType.Small)
        if (on.isNotEmpty()) PhaseLabel("Shared with", Modifier.padding(top = 8.dp))
        for (name in on) PersonRow(name, asked = name in playlist.invited) {
            Pill("Stop sharing", { act { session.unshare(playlist.id, name); "Stopped sharing with $name." } }, kind = PillKind.Quiet, height = 40.dp, enabled = !busy)
        }
        when (val all = accounts) {
            null -> Text(problem ?: "Loading…", color = PhaseColor.Text2, style = PhaseType.Small)
            else -> {
                val others = all.filterNot { it in on }
                if (others.isEmpty() && on.isEmpty()) {
                    Text("There are no other accounts on this server.", color = PhaseColor.Text2, style = PhaseType.Small)
                }
                if (others.isNotEmpty()) PhaseLabel("Other accounts on this server", Modifier.padding(top = 8.dp))
                for (name in others) PersonRow(name, asked = false) {
                    Pill("Share", { act { session.share(playlist.id, name); Voice.invited(name) } }, height = 40.dp, enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun PersonRow(name: String, asked: Boolean, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Initial(name)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (asked) Tag("Asked")
        }
        action()
    }
}

/**
 * A name for a playlist: a new one, a renamed one, a smart one saved as a
 * playlist. [onConfirm] does it and says whether it worked; the form
 * stays open until it has.
 */
@Composable
internal fun NameForm(title: String, initial: String, action: String, onConfirm: suspend (String) -> Boolean) {
    val pages = LocalPages.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    FormPage(title, action, enabled = name.isNotBlank() && !busy, onAction = {
        busy = true
        scope.launch {
            if (onConfirm(name.trim())) pages.popBackStack()
            busy = false
        }
    }) {
        PhaseField(name, { name = it }, label = "Name")
    }
}

/**
 * Adds songs to one of your playlists, or to a new one named here. Stays
 * open while it works, so the work isn't lost when it closes.
 */
@Composable
internal fun AddToPlaylistForm(session: AccountSession, tracks: List<Track>) {
    val context = LocalContext.current.applicationContext
    val pages = LocalPages.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
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
            if (attemptChange(context, session, edit = true) { work() }) pages.popBackStack()
            busy = false
        }
    }
    FormPage(
        if (tracks.size == 1) "Add 1 song to" else "Add ${tracks.size} songs to",
        "Create",
        enabled = name.isNotBlank() && !busy,
        onAction = {
            val n = name.trim()
            finish { session.createPlaylist(n, ids); Voice.addedToPlaylist(ids.size, n) }
        },
    ) {
        when (val l = lists) {
            null -> Text(problem ?: "Loading…", color = PhaseColor.Text2, style = PhaseType.Small)
            else -> Column {
                for (p in l) {
                    Text(
                        p.name,
                        Modifier
                            .clip(PhaseShape.Square)
                            .clickable(enabled = !busy) { finish { session.addToPlaylist(p.id, ids); Voice.addedToPlaylist(ids.size, p.name) } }
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        PhaseField(name, { name = it }, label = "New playlist")
    }
}

/** A smart playlist's name and rule (§2.4): a rule, and its one setting. */
@Composable
internal fun SmartPlaylistForm(session: AccountSession, initial: SmartPlaylist) {
    val context = LocalContext.current.applicationContext
    val pages = LocalPages.current
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(initial) }
    // A new one is named after its rule until you name it yourself.
    var named by remember { mutableStateOf(initial.id.isNotEmpty()) }
    var busy by remember { mutableStateOf(false) }
    FormPage(
        if (initial.id.isEmpty()) "New smart playlist" else "Edit smart playlist",
        "Save",
        enabled = p.name.isNotBlank() && !busy,
        onAction = {
            busy = true
            scope.launch {
                if (attemptChange(context, session) { session.saveSmart(p); null }) pages.popBackStack()
                busy = false
            }
        },
    ) {
        PhaseField(p.name, { p = p.copy(name = it); named = true }, label = "Name")
        PhaseLabel("Songs")
        Chips(SmartRules.all.map { it.key to it.label }, p.rule) { key ->
            p = SmartRules.withSetting(p.copy(rule = key, name = if (named) p.name else SmartRules.label(key)))
        }
        when (p.rule) {
            "most-played" -> Chips(SmartRules.spans, p.span) { p = p.copy(span = it) }
            "forgotten" -> {
                PhaseLabel("Not played in")
                Chips(SmartRules.months.map { it to SmartRules.monthsLabel(it) }, p.months) { p = p.copy(months = it) }
            }
            "added" -> {
                PhaseLabel("Added in the last")
                Chips(SmartRules.days.map { it to SmartRules.daysLabel(it) }, p.days) { p = p.copy(days = it) }
            }
        }
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) Chip(label, value == selected, { onPick(value) })
    }
}
