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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.sine.core.account.Account
import app.sine.core.server.ProbeResult
import app.sine.core.subsonic.SubsonicClient
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.core.subsonic.SubsonicException
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.download.Downloads
import app.sine.graph
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
    val accounts by graph.accounts.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val downloads by session.downloads.tracks.collectAsStateWithLifecycle()
    val accountSettings = settings.forAccount(session.account.id)
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    // The visible download folder, chosen once through SAF with a persisted grant (§2.5).
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            graph.settings.setDownloadTree(uri.toString())
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Accounts") }
        items(accounts.accounts, key = { it.id }) { a ->
            val active = a.id == session.account.id
            ItemRow(
                title = a.displayName, subtitle = a.kind.label, art = null, showArt = false,
                trailing = if (active) "Active" else null, selected = active,
            ) { if (!active) graph.accounts.setActive(a.id) }
        }
        item {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { nav.navigate(Routes.ADD_ACCOUNT) }) { Text("Add account") }
                TextButton(onClick = { confirmLogout = true }) { Text("Log out") }
            }
        }

        item { SectionHeader("Downloads") }
        item {
            val folder = settings.downloadTreeUri?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') }
            ItemRow("Download folder", folder ?: "Not chosen", null, showArt = false) { pickFolder.launch(null) }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download on mobile data")
                    Text("Metered connections, including hotspots.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = accountSettings.downloadOnMetered,
                    onCheckedChange = { on -> graph.settings.updateAccount(session.account.id) { it.copy(downloadOnMetered = on) } },
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Download budget")
                Text("A warning, never a limit.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    budgets.forEach { (label, bytes) ->
                        FilterChip(
                            selected = accountSettings.pinnedBudgetBytes == bytes,
                            onClick = { graph.settings.updateAccount(session.account.id) { it.copy(pinnedBudgetBytes = bytes) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        item {
            val total = downloads.values.sumOf { it.sizeBytes }
            Text(
                "${downloads.size} tracks, ${Voice.formatBytes(total)}.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (downloads.isNotEmpty()) item {
            TextButton(onClick = { confirmRemoveAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Remove ${downloads.size} downloads")
            }
        }
    }

    if (confirmLogout) {
        // §6.20: ask, defaulting to keep. Kept files stay in the folder.
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of ${session.account.displayName}") },
            text = { Text("Downloaded files can stay in the folder or be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    graph.removeAccount(session.account)
                }) { Text("Keep downloads") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        Downloads.removeAll(context, session)
                        graph.removeAccount(session.account)
                    }
                }) { Text("Delete ${downloads.size} downloads") }
            },
        )
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove ${downloads.size} downloads") },
            text = { Text("The files are deleted from the download folder.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveAll = false
                    scope.launch { Downloads.removeAll(context, session) }
                }) { Text("Remove ${downloads.size} downloads") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        Text("Connect to a server", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; probe = null; error = null },
            label = { Text("Server address") },
            placeholder = { Text("p450:4533") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = address.isNotBlank() && !busy,
            onClick = {
                busy = true
                scope.launch {
                    probe = graph.probe.probe(address)
                    busy = false
                }
            },
        ) { Text(if (busy && probe == null) "Checking…" else "Check") }

        when (val p = probe) {
            is ProbeResult.Found -> Text("${p.kind.label}\n${p.baseUrl}", style = MaterialTheme.typography.titleMedium)
            is ProbeResult.NotAServer -> Text(p.reason)
            is ProbeResult.Unreachable -> Text("Unreachable. ${p.reason}")
            null -> Unit
        }

        val found = probe as? ProbeResult.Found
        if (found != null) {
            OutlinedTextField(
                value = username, onValueChange = { username = it; error = null },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it; error = null },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = username.isNotBlank() && password.isNotEmpty() && !busy,
                onClick = {
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
            ) { Text("Log in") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
