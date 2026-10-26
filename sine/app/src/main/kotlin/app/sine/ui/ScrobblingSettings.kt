package app.sine.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.cosine.Scrobbler
import app.sine.core.cosine.Scrobbling
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Signal
import app.sine.ui.phase.Slab
import app.sine.ui.phase.StatusLine
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Voice.SCROBBLING_EXPLAINED, color = PhaseColor.Text2, style = PhaseType.Body)
        val s = state
        if (s == null) {
            StatusLine(problem ?: "Loading…", signal = if (problem != null) Signal.Server else Signal.None, color = PhaseColor.Text2)
            return@Column
        }

        val fm = s.connected.firstOrNull { it.service == "lastfm" }
        Slab {
            when {
                fm != null && fm.error == null -> Connected("Last.fm", fm, busy) { act { session.disconnectScrobbler("lastfm"); "Last.fm disconnected." } }
                lastFmToken != null -> {
                    ServiceName("Last.fm")
                    StatusLine(Voice.LASTFM_APPROVE, signal = Signal.Server)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Finish connecting", {
                            val token = lastFmToken ?: return@Pill
                            act {
                                val name = session.finishLastFm(token)
                                lastFmToken = null
                                Voice.scrobblingAs(name) + "."
                            }
                        }, kind = PillKind.Primary, height = 44.dp, enabled = !busy)
                        Pill("Open the page again", { approveAt?.let { open(context, it) } }, kind = PillKind.Quiet, height = 44.dp)
                        Pill("Cancel", { lastFmToken = null }, kind = PillKind.Quiet, height = 44.dp)
                    }
                }
                s.lastfmAvailable -> {
                    ServiceName("Last.fm")
                    if (fm != null) StatusLine(Voice.SCROBBLING_STOPPED, signal = Signal.Fault)
                    Pill(if (fm != null) "Connect again" else "Connect Last.fm", {
                        act {
                            val approval = session.startLastFm()
                            approveAt = approval.url
                            lastFmToken = approval.token
                            open(context, approval.url)
                            null
                        }
                    }, height = 44.dp, enabled = !busy)
                }
                else -> {
                    ServiceName("Last.fm")
                    Text(Voice.LASTFM_NOT_SET_UP, color = PhaseColor.Text2, style = PhaseType.Small)
                }
            }
        }

        val lb = s.connected.firstOrNull { it.service == "listenbrainz" }
        Slab {
            if (lb != null && lb.error == null) {
                Connected("ListenBrainz", lb, busy) { act { session.disconnectScrobbler("listenbrainz"); "ListenBrainz disconnected." } }
            } else {
                ServiceName("ListenBrainz")
                if (lb != null) StatusLine(Voice.SCROBBLING_STOPPED, signal = Signal.Fault)
                PhaseField(listenBrainzToken, { listenBrainzToken = it.trim() }, label = "User token", placeholder = "Paste your token")
                Text(Voice.LISTENBRAINZ_TOKEN, color = PhaseColor.Text2, style = PhaseType.Small)
                Pill("Connect ListenBrainz", {
                    val token = listenBrainzToken
                    act {
                        val name = session.connectListenBrainz(token)
                        listenBrainzToken = ""
                        Voice.scrobblingAs(name) + "."
                    }
                }, height = 44.dp, enabled = !busy && listenBrainzToken.isNotEmpty())
            }
        }
        Text("Plays from before you connect aren't sent.", color = PhaseColor.Text3, style = PhaseType.Small)
    }
}

@Composable
private fun ServiceName(name: String) {
    Text(name, color = PhaseColor.Text1, style = PhaseType.Title.copy(fontSize = 20.sp))
}

/** A connected service: who it scrobbles as, and plays still to send, which is Cosine at work. */
@Composable
private fun Connected(service: String, s: Scrobbler, busy: Boolean, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(service, Modifier.weight(1f), color = PhaseColor.Text1, style = PhaseType.Title.copy(fontSize = 20.sp))
        Pill("Disconnect", onDisconnect, kind = PillKind.Quiet, height = 40.dp, enabled = !busy)
    }
    Text(Voice.scrobblingAs(s.username), color = PhaseColor.Text1, style = PhaseType.Body)
    if (s.pending > 0) StatusLine(Voice.scrobblesWaiting(s.pending), signal = Signal.Server, color = PhaseColor.Text2)
}

private fun open(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
