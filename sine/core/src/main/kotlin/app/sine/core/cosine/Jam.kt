package app.sine.core.cosine

import kotlinx.serialization.Serializable

/*
 * Jam's party mode (§4C), as Cosine reports it: who's in it, one queue
 * anyone adds to, and where the music is at serverTime.
 */

@Serializable
data class JamMember(val name: String, val host: Boolean = false, val joined: Boolean = false, val present: Boolean = false)

@Serializable
data class JamSong(
    val track: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverArt: String? = null,
    val durationSec: Int = 0,
    val addedBy: String = "",
    /** You have it; else you hear it through the jam. */
    val inLibrary: Boolean = false,
)

@Serializable
data class Jam(
    val id: Long,
    val host: String,
    val members: List<JamMember> = emptyList(),
    val queue: List<JamSong> = emptyList(),
    val current: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    /** Unix ms on the server when this was true: listeners set their clocks by it. */
    val serverTime: Long = 0,
    val version: Long = 0,
    val ended: Boolean = false,
    val you: JamMember = JamMember(""),
)

@Serializable
data class JamInvitation(val id: Long, val host: String, val members: Int = 0)

/** The jam you're in, if any, and the ones you're asked to. */
@Serializable
data class Jams(val jam: Jam? = null, val invitations: List<JamInvitation> = emptyList())

/**
 * Where a jam's music is at [serverNow] (server time, Unix ms): from where it
 * was at its serverTime, on through the songs' lengths, stopping at the end
 * of the last. The same sum Cosine does, so every listener lands together.
 */
fun Jam.at(serverNow: Long): Pair<Int, Long> {
    if (queue.isEmpty()) return 0 to 0L
    var index = current.coerceIn(0, queue.lastIndex)
    var position = positionMs + if (playing) (serverNow - serverTime).coerceAtLeast(0) else 0
    while (playing) {
        val length = queue[index].durationSec * 1000L
        if (length <= 0 || position < length) break
        if (index == queue.lastIndex) return index to length
        position -= length
        index++
    }
    return index to position
}
