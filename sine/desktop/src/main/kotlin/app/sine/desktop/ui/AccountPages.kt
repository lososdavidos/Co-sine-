package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.account.Account
import app.sine.core.color.Accents
import app.sine.core.server.ProbeResult
import app.sine.core.session.AccountSession
import app.sine.core.subsonic.SubsonicClient
import app.sine.core.subsonic.SubsonicCredentials
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
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import kotlinx.coroutines.launch

/** The first thing Sine shows on a new computer: your server, then your account on it. */
@Composable
fun SignIn(modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhaseIcon(PhaseIcons.Wave, null, size = 40.dp, tone = IconTone.Sine)
                Text("Sine", color = PhaseColor.Text1, style = PhaseType.display(56.sp))
            }
            Text("Music from your own server: Cosine, or any Subsonic server.", color = PhaseColor.Text2, style = PhaseType.Body)
            AddAccount(onDone)
        }
    }
}

/** A server's address, then an account on it. Only a token and salt are kept; the password never is. */
@Composable
fun AddAccount(onDone: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = probe as? ProbeResult.Found

    fun look() {
        busy = true
        probe = null
        scope.launch {
            probe = graph.probe.probe(address)
            busy = false
        }
    }

    fun logIn(f: ProbeResult.Found) {
        busy = true
        error = null
        scope.launch {
            val credentials = SubsonicCredentials.fromPassword(username.trim(), password)
            try {
                SubsonicClient(f.baseUrl, credentials, graph.http).ping()
                val account = Account.create(f.baseUrl, credentials, f.kind, System.currentTimeMillis())
                graph.accounts.add(account)
                graph.accounts.setActive(account.id)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "That didn't work."
            } finally {
                busy = false
            }
        }
    }

    PhaseField(
        address, { address = it; probe = null }, Modifier.fillMaxWidth(), label = "Server",
        placeholder = "music.example.com",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) look() }),
    )
    if (found == null) Pill("Find it", ::look, kind = PillKind.Primary, enabled = address.isNotBlank() && !busy)
    when (val p = probe) {
        is ProbeResult.Found -> StatusLine("Found ${p.kind.label} at ${p.baseUrl}.", signal = if (p.kind is app.sine.core.server.ServerKind.Cosine) Signal.Server else Signal.None)
        is ProbeResult.NotAServer -> StatusLine(p.reason, signal = Signal.Fault)
        is ProbeResult.Unreachable -> StatusLine("Unreachable. ${p.reason}", signal = Signal.Fault)
        null -> if (busy) StatusLine("Looking…", signal = Signal.Server, color = PhaseColor.Text2)
    }
    if (found != null) {
        PhaseField(username, { username = it; error = null }, Modifier.fillMaxWidth(), label = "Username")
        PhaseField(
            password, { password = it; error = null }, Modifier.fillMaxWidth(), label = "Password",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (username.isNotBlank()) logIn(found) }),
        )
        Pill("Log in", { logIn(found) }, kind = PillKind.Primary, enabled = username.isNotBlank() && password.isNotEmpty() && !busy)
        error?.let { StatusLine(it, signal = Signal.Fault) }
    }
}

/** Accounts, colour, and where things are kept. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPage(session: AccountSession) {
    val graph = LocalGraph.current
    val state by graph.accounts.state.collectAsState()
    val settings by graph.settings.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Account?>(null) }
    var password by remember { mutableStateOf(false) }
    val refused by session.refused.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PageTitle("Settings")
            SectionLabel("Accounts")
            for (a in state.accounts) {
                val active = a.id == session.account.id
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Initial(a.username, size = 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(a.displayName, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.kind.label, color = PhaseColor.Text2, style = PhaseType.Small)
                    }
                    if (active) Text("In use", color = PhaseColor.Text3, style = PhaseType.Small)
                    else Pill("Use", { graph.accounts.setActive(a.id) }, kind = PillKind.Secondary, height = 40.dp)
                    Pill("Sign out", { forgetting = a }, kind = PillKind.QuietFault, height = 40.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(
                    if (refused) "Sign in again" else "Change password", { password = true },
                    kind = if (refused) PillKind.Primary else PillKind.Outline, height = 44.dp,
                )
                if (refused) StatusLine("The server no longer takes the password Sine kept.", signal = Signal.Fault)
            }
            if (adding) {
                Column(Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { AddAccount { adding = false } }
            } else {
                Pill("Add an account", { adding = true }, kind = PillKind.Outline, icon = PhaseIcons.Plus, height = 44.dp)
            }

            SectionLabel("Colour")
            Text("Sine takes its colour from the artwork on screen, or keeps one you choose.", color = PhaseColor.Text2, style = PhaseType.Small)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("From the artwork", settings.fixedColour == null, { graph.settings.setFixedColour(null) })
                for ((name, _) in Accents.fixed) Chip(name, settings.fixedColour == name, { graph.settings.setFixedColour(name) })
            }

            SectionLabel("On this computer")
            Text("Downloads: ${graph.music.path}", color = PhaseColor.Text2, style = PhaseType.Small)
            Text("Sine's own files: ${graph.dir.path}", color = PhaseColor.Text2, style = PhaseType.Small)
            Text("This computer is \"${graph.device.name}\" to your other devices.", color = PhaseColor.Text2, style = PhaseType.Small)

            SectionLabel("Keys")
            for ((keys, what) in Shortcuts) {
                Row(Modifier.fillMaxWidth()) {
                    Text(keys, Modifier.width(200.dp), color = PhaseColor.Text1, style = PhaseType.Data)
                    Text(what, color = PhaseColor.Text2, style = PhaseType.Small)
                }
            }
        }
    }
    if (password) PasswordDialog(session, again = refused, onDone = { password = false })
    forgetting?.let { a ->
        Confirm(
            question = "Sign out of ${a.displayName}?",
            explained = "Its downloads stay in your music folder; Sine forgets the rest on this computer.",
            action = "Sign out",
            onConfirm = { forgetting = null; graph.removeAccount(a) },
            onDismiss = { forgetting = null },
        )
    }
}

/** The keys Sine answers to on the desktop. */
val Shortcuts = listOf(
    "Space" to "Play or pause",
    "Ctrl+→ / Ctrl+←" to "Next song, previous song",
    "Ctrl+↑ / Ctrl+↓" to "Louder, quieter",
    "Ctrl+K" to "Search",
    "Alt+←" to "Back",
    "Enter" to "Play the songs selected",
    "Q" to "Add the songs selected to the queue",
    "Ctrl+A / Esc" to "Select all, let go",
    "Double-click" to "Play from that song",
    "Right-click" to "What you can do with the songs selected",
)

/**
 * An account's password (§2.1). [again]: the server stopped taking the one
 * kept here, so this asks for it; otherwise it changes it on the server.
 * The account stays signed in with its downloads either way.
 */
@Composable
fun PasswordDialog(session: AccountSession, again: Boolean, onDone: () -> Unit) {
    val graph = LocalGraph.current
    val say = LocalSay.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val account = session.account
    val ready = password.isNotEmpty() && (again || password == repeat) && !busy

    fun go() {
        if (!ready) return
        busy = true
        problem = null
        scope.launch {
            try {
                val updated = if (again) session.signInAgain(password) else session.changePassword(password)
                graph.accounts.update(updated)
                say(if (again) "Signed in again." else "Password changed.")
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: app.sine.core.subsonic.SubsonicException) {
                problem = when {
                    e.isAuthFailure && again -> "That isn't it. The server still says no."
                    e.code == 50 -> "This server lets only an admin change passwords."
                    e.code == 0 && !again -> "This server can't change passwords from an app. Change it on the server."
                    else -> e.message
                }
            } catch (e: java.io.IOException) {
                problem = "Can't reach ${account.serverUrl.substringAfter("://")}."
            } finally {
                busy = false
            }
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.width(440.dp)
                .clip(app.sine.ui.phase.PhaseShape.Floating)
                .background(PhaseColor.Float)
                .border(1.dp, PhaseColor.LineFloat, app.sine.ui.phase.PhaseShape.Floating)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (again) "Sign in again" else "Change password", color = PhaseColor.Text1, style = PhaseType.Title)
            Text(
                if (again) "${account.displayName} no longer takes the password Sine kept: it was changed somewhere else. Its downloads stay."
                else "For ${account.displayName}. Your other apps and devices will ask for the new one.",
                color = PhaseColor.Text2, style = PhaseType.Body,
            )
            PhaseField(
                password, { password = it; problem = null }, Modifier.fillMaxWidth(),
                label = if (again) "Password" else "New password",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (again) ImeAction.Go else ImeAction.Next),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
            if (!again) {
                PhaseField(
                    repeat, { repeat = it; problem = null }, Modifier.fillMaxWidth(), label = "Again",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                )
                if (repeat.isNotEmpty() && repeat != password) StatusLine("The two aren't the same yet.", color = PhaseColor.Text2)
            }
            problem?.let { StatusLine(it, signal = Signal.Fault) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (again) "Sign in" else "Change it", ::go, kind = PillKind.Primary, enabled = ready, height = 44.dp)
                Pill("Cancel", onDone, kind = PillKind.Quiet, height = 44.dp)
            }
        }
    }
}
