@file:OptIn(ExperimentalMaterial3Api::class)

package app.sine.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableLongStateOf
import app.sine.playback.StreamCache
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.Ambient
import app.sine.core.color.Accents
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.core.account.Account
import app.sine.core.server.Capability
import app.sine.core.server.ProbeResult
import app.sine.core.server.ServerKind
import app.sine.core.subsonic.SubsonicClient
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.core.subsonic.SubsonicException
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.download.Downloads
import app.sine.graph
import app.sine.ui.phase.Chip
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.Initial
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Signal
import app.sine.ui.phase.Slab
import app.sine.ui.phase.StatusLine
import app.sine.ui.phase.Tag
import app.sine.ui.phase.rowSurface
import kotlinx.coroutines.launch
import java.io.IOException

private const val GB = 1024L * 1024 * 1024
private val budgets: List<Pair<String, Long?>> = listOf(
    "None" to null, "2 GB" to 2 * GB, "8 GB" to 8 * GB, "32 GB" to 32 * GB, "128 GB" to 128 * GB,
)

/** How much of what's streamed is kept. */
private val crossfades = listOf("Off" to 0, "3 s" to 3, "6 s" to 6, "12 s" to 12)

/** A row that turns something on or off, the whole row the target. */
@Composable
private fun SettingSwitch(title: String, line: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .rowSurface(false)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = PhaseColor.Text1, style = PhaseType.Name)
            Text(line, color = PhaseColor.Text2, style = PhaseType.Small)
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PhaseColor.Ink, checkedTrackColor = PhaseColor.Sine, checkedBorderColor = PhaseColor.Sine,
                uncheckedThumbColor = PhaseColor.Text3, uncheckedTrackColor = PhaseColor.Raised, uncheckedBorderColor = PhaseColor.LineStrong,
            ),
        )
    }
}

private val streamCaches = listOf("Off" to 0L, "1 GB" to (1L shl 30), "2 GB" to (2L shl 30), "8 GB" to (8L shl 30))

@Composable
fun SettingsScreen(session: AccountSession, nav: Pages) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val shelf = LocalShelf.current
    val accounts by graph.accounts.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val accountSettings = settings.forAccount(session.account.id)

    // The visible download folder, chosen once through SAF with a persisted grant (§2.5).
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            graph.settings.setDownloadTree(uri.toString())
        }
    }

    fun askLogout() {
        // §6.20: ask, defaulting to keep. Kept files stay in the folder.
        shelf.ask(
            ShelfQuestion(
                "Log out of ${session.account.displayName}?",
                "Downloaded files can stay in the folder or be deleted.",
                listOf(
                    ShelfAction("Keep downloads") { graph.removeAccount(session.account) },
                    ShelfAction("Delete ${downloads.size} downloads", PillKind.Fault) {
                        scope.launch {
                            Downloads.removeAll(context, session)
                            graph.removeAccount(session.account)
                        }
                    },
                ),
                cancel = true,
            ),
        )
    }

    fun askRemoveAll() {
        shelf.ask(
            ShelfQuestion(
                "Remove ${downloads.size} downloads?",
                "The files are deleted from the download folder. The songs stay in your library.",
                listOf(
                    ShelfAction("Keep") {},
                    ShelfAction("Remove ${downloads.size} downloads", PillKind.Fault) {
                        scope.launch { Downloads.removeAll(context, session) }
                    },
                ),
            ),
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                TopSpace()
                Text("Settings", color = PhaseColor.Text1, style = PhaseType.display(44.sp))
            }
        }
        // Jam (§4C): listening with other accounts.
        if (session.can(Capability.JAM)) item {
            ItemRow(
                "Jam", "Listen together with other accounts", null, showArt = false,
                end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
            ) { nav.navigate(Routes.JAM) }
        }
        // Your devices (§4B): this phone and the others, to control them from here.
        if (session.can(Capability.SESSIONS)) item {
            ItemRow(
                "Devices", "Your phones and apps, and controlling them", null, showArt = false,
                end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
            ) { nav.navigate(Routes.DEVICES) }
        }
        // Listening (§6.13): reached from the account control, as yours alone.
        if (session.can(Capability.STATS)) item {
            ItemRow(
                "Listening", "What you've played, and when", null, showArt = false,
                end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
            ) { nav.navigate(Routes.STATS) }
        }
        item { SectionHeader("Accounts") }
        items(accounts.accounts, key = { it.id }) { a ->
            val active = a.id == session.account.id
            ItemRow(
                title = a.username,
                subtitle = a.serverUrl.substringAfter("://") + " · " + a.kind.label,
                art = null, showArt = false,
                leading = { Initial(a.username) },
                // Which account is in use is where you are, not a selection: a tag, not Sine.
                end = if (active) ({ Tag("In use") }) else null,
            ) { if (!active) graph.accounts.setActive(a.id) }
        }
        // The password of the account in use (§2.1): changed here, or asked for again when the server stopped taking it.
        item {
            val refused by session.refused.collectAsStateWithLifecycle()
            ItemRow(
                if (refused) "Sign in again" else "Change password",
                if (refused) "The server no longer takes the password Sine kept" else "For ${session.account.username}",
                null, showArt = false,
                end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
            ) { nav.form { PasswordForm(session, again = refused) } }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Add account", { nav.navigate(Routes.ADD_ACCOUNT) }, icon = PhaseIcons.Plus, height = 44.dp)
                Pill("Log out", ::askLogout, kind = PillKind.QuietFault, height = 44.dp)
            }
        }

        item { SectionHeader("Appearance") }
        item { ColourSetting(settings.fixedColour) { graph.settings.setFixedColour(it) } }

        item { SectionHeader("Downloads") }
        item {
            val folder = settings.downloadTreeUri?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') }
            ItemRow(
                "Download folder", folder ?: "Not chosen", null, showArt = false,
                end = { PhaseIcon(PhaseIcons.Forward, null, size = 20.dp, tone = IconTone.Faint) },
            ) { pickFolder.launch(null) }
        }
        item {
            Row(
                Modifier
                    .rowSurface(false)
                    .toggleable(
                        value = accountSettings.downloadOnMetered, role = Role.Switch,
                        onValueChange = { on -> graph.settings.updateAccount(session.account.id) { it.copy(downloadOnMetered = on) } },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Download on mobile data", color = PhaseColor.Text1, style = PhaseType.Name)
                    Text("Metered connections, including hotspots.", color = PhaseColor.Text2, style = PhaseType.Small)
                }
                Switch(
                    checked = accountSettings.downloadOnMetered,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PhaseColor.Ink, checkedTrackColor = PhaseColor.Sine, checkedBorderColor = PhaseColor.Sine,
                        uncheckedThumbColor = PhaseColor.Text3, uncheckedTrackColor = PhaseColor.Raised, uncheckedBorderColor = PhaseColor.LineStrong,
                    ),
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Download budget", color = PhaseColor.Text1, style = PhaseType.Name)
                    Text("A warning, never a limit.", color = PhaseColor.Text2, style = PhaseType.Small)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    budgets.forEach { (label, bytes) ->
                        Chip(label, accountSettings.pinnedBudgetBytes == bytes, {
                            graph.settings.updateAccount(session.account.id) { it.copy(pinnedBudgetBytes = bytes) }
                        })
                    }
                }
            }
        }
        item {
            val total = downloads.values.sumOf { it.sizeBytes }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("On this phone: ${Voice.songs(downloads.size)}, ${Voice.formatBytes(total)}.", Modifier.weight(1f), color = PhaseColor.Text2, style = PhaseType.Body)
                if (downloads.isNotEmpty()) Pill("Remove all", ::askRemoveAll, kind = PillKind.QuietFault, height = 40.dp)
            }
        }

        // Keeping what you stream (§2.5): played songs stay, to play offline and not download twice.
        item {
            var kept by remember { mutableLongStateOf(StreamCache.size()) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Keep what you stream", color = PhaseColor.Text1, style = PhaseType.Name)
                    Text(
                        "Songs you play stay on this phone up to this much, the least recently played going first. Kept whole, they play offline.",
                        color = PhaseColor.Text2, style = PhaseType.Small,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    streamCaches.forEach { (label, bytes) ->
                        Chip(label, settings.streamCacheBytes == bytes, {
                            graph.settings.setStreamCache(bytes)
                            kept = StreamCache.size()
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kept now: ${Voice.formatBytes(kept)}.", Modifier.weight(1f), color = PhaseColor.Text2, style = PhaseType.Body)
                    if (kept > 0) Pill("Clear", { StreamCache.clear(); kept = 0 }, kind = PillKind.Quiet, height = 40.dp)
                }
            }
        }
        // The prefetch (§2.5): what's coming up is kept ahead, on Wi-Fi.
        if (settings.streamCacheBytes > 0) item {
            SettingSwitch(
                "Keep what's coming up",
                "On Wi-Fi, the next songs are kept before they play, so they carry on without signal.",
                settings.prefetch,
            ) { graph.settings.setPrefetch(it) }
        }

        // Crossfade (Q69): off is gapless.
        item { SectionHeader("Playback") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Crossfade", color = PhaseColor.Text1, style = PhaseType.Name)
                    Text(
                        "Each song fades into the next. Songs from one album still run into each other, as they were made. Off, songs play gapless.",
                        color = PhaseColor.Text2, style = PhaseType.Small,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    crossfades.forEach { (label, seconds) ->
                        Chip(label, settings.crossfadeSec == seconds, { graph.settings.setCrossfade(seconds) })
                    }
                }
            }
        }

        if (session.can(Capability.SCROBBLING)) {
            item { SectionHeader("Scrobbling") }
            item { ScrobblingSettings(session) }
        }

        // The fonts' licence travels with them (SIL OFL 1.1).
        item {
            Text(
                "Phase type: Instrument Sans and Martian Mono, under the SIL Open Font License.",
                Modifier.padding(horizontal = 20.dp, vertical = 24.dp), color = PhaseColor.Text3, style = PhaseType.Small,
            )
        }
    }
}

/**
 * Sine's colour (§6.1): the artwork's, as it changes, or one kept fixed.
 * Each fixed one is shown as it fills: its gradient, square, since it's read.
 */
@Composable
private fun ColourSetting(fixed: String?, onChoose: (String?) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Colour", color = PhaseColor.Text1, style = PhaseType.Name)
            Text(
                if (fixed == null) "Taken from the artwork on screen." else "$fixed, whatever is on screen. Red and the server's blue never change.",
                color = PhaseColor.Text2, style = PhaseType.Small,
            )
        }
        Chip("From the artwork", fixed == null, { onChoose(null) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((name, _) in Accents.fixed) {
                val colors = Ambient.fixed(name) ?: continue
                val on = fixed == name
                Column(
                    Modifier
                        .clip(PhaseShape.Square)
                        .selectable(selected = on, role = Role.RadioButton, onClick = { onChoose(name) })
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(PhaseShape.Square)
                            .background(Brush.linearGradient(listOf(colors.fill, colors.far)))
                            .then(if (on) Modifier.border(2.dp, PhaseColor.Text1, PhaseShape.Square) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (on) PhaseIcon(PhaseIcons.Check, null, size = 22.dp, tone = IconTone.OnSine) }
                    Text(name, color = if (on) PhaseColor.Text1 else PhaseColor.Text2, style = PhaseType.Small)
                }
            }
        }
    }
}

/**
 * First run and adding accounts (§6.20). Typing the address is the primary
 * path; the mode is shown explicitly before login, never inferred silently.
 */
@Composable
fun AddAccountScreen(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onCancel != null) Pill("Cancel", onCancel, kind = PillKind.Floating, icon = PhaseIcons.Back, height = 40.dp)
        Text("Connect to a server", color = PhaseColor.Text1, style = PhaseType.display(44.sp), modifier = Modifier.padding(top = 24.dp))
        Text("Cosine, or any Subsonic server. Sine says which it found before you log in.", color = PhaseColor.Text2, style = PhaseType.Body)
        PhaseField(
            value = address,
            onValueChange = { address = it; probe = null; error = null },
            label = "Server address",
            placeholder = "p450:4534",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        val found = probe as? ProbeResult.Found
        Pill(
            if (busy && probe == null) "Checking…" else "Check",
            {
                busy = true
                scope.launch {
                    probe = graph.probe.probe(address)
                    busy = false
                }
            },
            kind = if (found == null) PillKind.Primary else PillKind.Secondary,
            enabled = address.isNotBlank() && !busy,
        )

        when (val p = probe) {
            is ProbeResult.Found -> Slab {
                // Cosine is the server at work; a plain Subsonic server is only a server.
                StatusLine(p.kind.label, signal = if (p.kind is ServerKind.Cosine) Signal.Server else Signal.None)
                Text(p.baseUrl, color = PhaseColor.Text2, style = PhaseType.Data)
            }
            is ProbeResult.NotAServer -> StatusLine(p.reason, signal = Signal.Fault)
            is ProbeResult.Unreachable -> StatusLine("Unreachable. ${p.reason}", signal = Signal.Fault)
            null -> Unit
        }

        if (found != null) {
            PhaseField(value = username, onValueChange = { username = it; error = null }, label = "Username")
            PhaseField(
                value = password, onValueChange = { password = it; error = null },
                label = "Password",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Pill(
                "Log in",
                {
                    busy = true
                    error = null
                    scope.launch {
                        // Only the token and salt are kept; the password is never stored.
                        val credentials = SubsonicCredentials.fromPassword(username.trim(), password)
                        try {
                            SubsonicClient(found.baseUrl, credentials, graph.http).ping()
                            graph.accounts.add(
                                Account.create(found.baseUrl, credentials, found.kind, System.currentTimeMillis())
                            )
                            onDone()
                        } catch (e: SubsonicException) {
                            error = if (e.isAuthFailure) "Wrong username or password." else e.message
                        } catch (e: IOException) {
                            error = "Server unreachable."
                        } finally {
                            busy = false
                        }
                    }
                },
                kind = PillKind.Primary,
                enabled = username.isNotBlank() && password.isNotEmpty() && !busy,
            )
        }
        error?.let { StatusLine(it, signal = Signal.Fault) }
    }
}
