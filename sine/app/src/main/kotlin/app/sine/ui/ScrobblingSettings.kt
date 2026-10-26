package app.sine.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import app.sine.core.cosine.Scrobbler
import app.sine.core.cosine.Scrobbling
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import kotlinx.coroutines.launch

/**
 * Where this account's plays are scrobbled (§4A). Cosine does the
 * scrobbling, so every play counts, whichever app heard it; here the
 * account connects its Last.fm or ListenBrainz. Needs the server.
 */
@Composable
internal fun ScrobblingSettings(session: AccountSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Scrobbling?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Connecting Last.fm: the page to approve Cosine at, and the token to finish with.
    var approveAt by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFmToken by rememberSaveable { mutableStateOf<String?>(null) }
    var listenBrainzToken by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun act(work: suspend () -> String?) {
        busy = true
        scope.launch {
            attemptChange(context.applicationContext, session) { work().also { state = session.scrobbling() } }
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        if (!attemptChange(context.applicationContext, session) { state = session.scrobbling(); null }) {
            problem = "Scrobbling settings need the server."
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Voice.SCROBBLING_EXPLAINED, style = MaterialTheme.typography.bodySmall)
        val s = state
        if (s == null) {
            Text(problem ?: "Loading…")
            return@Column
        }

        Text("Last.fm", style = MaterialTheme.typography.titleSmall)
        val fm = s.connected.firstOrNull { it.service == "lastfm" }
        when {
            fm != null && fm.error == null -> Connected(fm, busy) { act { session.disconnectScrobbler("lastfm"); "Last.fm disconnected." } }
            lastFmToken != null -> {
                Text(Voice.LASTFM_APPROVE)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        val token = lastFmToken ?: return@Button
                        act {
                            val name = session.finishLastFm(token)
                            lastFmToken = null
                            Voice.scrobblingAs(name) + "."
                        }
                    }) { Text("Finish connecting") }
                    TextButton(onClick = { approveAt?.let { open(context, it) } }) { Text("Open the page again") }
                    TextButton(onClick = { lastFmToken = null }) { Text("Cancel") }
                }
            }
            s.lastfmAvailable -> {
                if (fm != null) Text(Voice.SCROBBLING_STOPPED)
                OutlinedButton(enabled = !busy, onClick = {
                    act {
                        val approval = session.startLastFm()
                        approveAt = approval.url
                        lastFmToken = approval.token
                        open(context, approval.url)
                        null
                    }
                }) { Text(if (fm != null) "Connect again" else "Connect Last.fm") }
            }
            else -> Text(Voice.LASTFM_NOT_SET_UP)
        }

        Text("ListenBrainz", style = MaterialTheme.typography.titleSmall)
        val lb = s.connected.firstOrNull { it.service == "listenbrainz" }
        if (lb != null && lb.error == null) {
            Connected(lb, busy) { act { session.disconnectScrobbler("listenbrainz"); "ListenBrainz disconnected." } }
        } else {
            if (lb != null) Text(Voice.SCROBBLING_STOPPED)
            OutlinedTextField(
                value = listenBrainzToken, onValueChange = { listenBrainzToken = it.trim() }, singleLine = true,
                label = { Text("User token") }, modifier = Modifier.fillMaxWidth(),
            )
            Text(Voice.LISTENBRAINZ_TOKEN, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && listenBrainzToken.isNotEmpty(), onClick = {
                val token = listenBrainzToken
                act {
                    val name = session.connectListenBrainz(token)
                    listenBrainzToken = ""
                    Voice.scrobblingAs(name) + "."
                }
            }) { Text("Connect ListenBrainz") }
        }
    }
}

@Composable
private fun Connected(s: Scrobbler, busy: Boolean, onDisconnect: () -> Unit) {
    Text(Voice.scrobblingAs(s.username))
    if (s.pending > 0) Text(Voice.scrobblesWaiting(s.pending), style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = !busy, onClick = onDisconnect) { Text("Disconnect") }
}

private fun open(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
