package app.sine.core.play

/**
 * Crossfade's steps (Q69), apart from any player so they can be tested:
 * given how the two players are now, what to do next. The player plays
 * the queue; the tail carries the end of a song while the player has
 * moved on to the next.
 *
 * The handover is what has to be seamless, or the fade starts with a cut:
 * the song going must never be silent while the tail gets ready. So:
 *
 * 1. Armed: within [Crossfade.ARM_AHEAD_MS] of the fade, the song's end is
 *    loaded in the tail, silent, waiting at the fade's start.
 * 2. Starting: at the fade's start, if the tail is ready, it starts, still
 *    silent, under the player: both play the same music.
 * 3. Fading: once the tail truly plays, the two swap (tail up, player
 *    down and on to the next song), and the fade runs by the tail's
 *    position.
 *
 * Not ready in time, or anything changed meanwhile (a skip, a seek, the
 * setting), and it lets go: the song plays out as it is, unfaded, never cut.
 */
class CrossfadePlan {
    /** How the players are, each time it's asked. */
    data class Now(
        val seconds: Int,
        val index: Int,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val hasNext: Boolean,
        val sameAlbum: Boolean,
        val repeatOne: Boolean,
        val held: Boolean,
        val tailReady: Boolean,
        val tailPlaying: Boolean,
        val tailPositionMs: Long,
        val tailEnded: Boolean,
        val clockMs: Long,
    )

    sealed interface Action {
        /** Load the song playing into the tail at [fromMs], silent and paused. */
        data class Arm(val fromMs: Long) : Action
        /** Start the tail, silent. */
        data object StartTail : Action
        /** Tail to full, player silent and on to its next song. */
        data object Swap : Action
        data class Levels(val tail: Float, val player: Float) : Action
        data object PauseTail : Action
        data object ResumeTail : Action
        /** Let go: tail stopped and emptied, player at full. */
        data object Drop : Action
    }

    enum class State { Idle, Armed, Starting, Fading }

    var state = State.Idle
        private set
    private var index = -1
    private var durationMs = 0L
    private var fromMs = 0L
    private var since = 0L

    /** Whether to be asked often: the fade's start is near, or the tail is starting. */
    fun close(now: Now): Boolean =
        state == State.Starting || (state == State.Armed && now.positionMs >= fromMs - CLOSE_MS)

    fun next(now: Now): List<Action> = when (state) {
        State.Idle -> idle(now)
        State.Armed -> armed(now)
        State.Starting -> starting(now)
        State.Fading -> fading(now)
    }

    /** Stops whatever is under way: the setting turned off, the player let go. */
    fun cancel(): List<Action> {
        if (state == State.Idle) return emptyList()
        state = State.Idle
        return listOf(Action.Drop)
    }

    private fun idle(n: Now): List<Action> {
        if (!n.playing || n.held || n.repeatOne) return emptyList()
        if (!Crossfade.armsNow(n.seconds, n.positionMs, n.durationMs, n.hasNext, n.sameAlbum)) return emptyList()
        index = n.index
        durationMs = n.durationMs
        fromMs = Crossfade.fadeFrom(n.seconds, n.durationMs)
        state = State.Armed
        return listOf(Action.Arm(fromMs))
    }

    private fun armed(n: Now): List<Action> {
        val changed = n.index != index || n.seconds <= 0 || n.held || n.repeatOne ||
            n.positionMs < fromMs - Crossfade.ARM_AHEAD_MS - SLACK_MS ||
            // Seeked over the fade's start: it plays out.
            n.positionMs > fromMs + SLACK_MS
        if (changed) return cancel()
        if (n.positionMs < fromMs) return emptyList()
        // The fade's start. The tail not ready, or nothing playing: no fade this time.
        if (!n.tailReady || !n.playing) return cancel()
        state = State.Starting
        since = n.clockMs
        return listOf(Action.StartTail)
    }

    private fun starting(n: Now): List<Action> {
        if (n.index != index || !n.playing) return cancel()
        if (n.tailPlaying && n.tailPositionMs >= fromMs + PLAYING_MS) {
            state = State.Fading
            val (out, inn) = Crossfade.gains(Crossfade.progress(n.tailPositionMs, fromMs, durationMs))
            return listOf(Action.Swap, Action.Levels(out, inn))
        }
        if (n.clockMs - since > START_LIMIT_MS) return cancel()
        return emptyList()
    }

    private fun fading(n: Now): List<Action> {
        // Paused mid-fade: both wait; on again, both go on.
        if (!n.playing) return if (n.tailPlaying) listOf(Action.PauseTail) else emptyList()
        val out = mutableListOf<Action>()
        if (!n.tailPlaying && n.tailReady) out += Action.ResumeTail
        val (going, coming) = Crossfade.gains(Crossfade.progress(n.tailPositionMs, fromMs, durationMs))
        if (going <= 0.001f || n.tailEnded) {
            state = State.Idle
            return out + Action.Drop
        }
        return out + Action.Levels(going, coming)
    }

    companion object {
        /** Played this far, the tail is truly sounding, not just told to. */
        const val PLAYING_MS = 30L
        /** Not started by then: no fade this time. */
        const val START_LIMIT_MS = 1_500L
        /** Within this of the fade's start, it's watched closely. */
        const val CLOSE_MS = 1_000L
        const val SLACK_MS = 500L
    }
}
