package app.sine.ui

import android.content.Context
import app.sine.core.model.Track
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Adds songs to the queue: the one thing a swipe, the shelf and anything
 * else mean by it. In a jam the queue is the jam's (§4C), so they go there,
 * for everyone; there's no separate "add to jam". Otherwise they play next,
 * after any added before them (#37).
 */
internal fun queueSongs(context: Context, session: AccountSession, tracks: List<Track>, scope: CoroutineScope) {
    if (tracks.isEmpty()) return
    val graph = context.graph
    val jam = graph.jam.jam.value
    if (jam != null && session.cosine != null) {
        scope.launch {
            try {
                session.cosine.addToJam(jam.id, tracks.map { it.id })
                graph.jam.refresh()
                say(if (tracks.size == 1) "Added to the jam's queue." else "${tracks.size} songs added to the jam's queue.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("The jam didn't take it.", Signal.Server)
            }
        }
        return
    }
    val n = graph.player.addToQueue(session, tracks)
    say(if (n == 0) Voice.TRACK_UNAVAILABLE else if (n == 1) "Added to the queue." else "$n songs added to the queue.")
}

/** Adding one song to the queue, from a row's swipe. */
@androidx.compose.runtime.Composable
internal fun rememberQueue(session: AccountSession): (Track) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return androidx.compose.runtime.remember(session) { { t -> queueSongs(context, session, listOf(t), scope) } }
}
