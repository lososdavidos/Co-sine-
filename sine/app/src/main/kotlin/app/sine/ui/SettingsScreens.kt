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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
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
import app.sine.ui.phase.Initial
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
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

@Composable
fun SettingsScreen(session: AccountSession, nav: NavController) {
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
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Add account", { nav.navigate(Routes.ADD_ACCOUNT) }, icon = PhaseIcons.Plus, height = 44.dp)
                Pill("Log out", ::askLogout, kind = PillKind.QuietFault, height = 44.dp)
            }
        }

        item { SectionHeader("Downloads") }
        item {
            val folder = settings.downloadTreeUri?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') }
            ItemRow(
                "Download folder", folder ?: "Not chosen", null, showArt = false,
                end = { Icon(PhaseIcons.Forward, null, tint = PhaseColor.Text3) },
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
