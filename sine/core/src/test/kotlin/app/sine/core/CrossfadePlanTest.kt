package app.sine.core

import app.sine.core.play.Crossfade
import app.sine.core.play.CrossfadePlan
import app.sine.core.play.CrossfadePlan.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two players simulated as they behave: loading takes time, and a player
 * told to play says it is at once but sounds only a little later. Every
 * 10 ms (50 when the plan isn't watching closely), what's heard is noted.
 */
class CrossfadePlanTest {
    private class Sim(
        val loadMs: Long = 400,
        /** From play to sound. */
        val latencyMs: Long = 60,
        val seconds: Int = 6,
        val songMs: Long = 30_000,
    ) {
        val plan = CrossfadePlan()
        var clock = 0L
        var index = 0
        var position = 0L
        var playing = true
        var volume = 1f
        var tailLoadedAt = -1L
        var tailFrom = 0L
        var tailPlayAt = -1L
        var tailPausedPos = -1L
        var tailVolume = 0f
        val actions = mutableListOf<Action>()

        val tailReady get() = tailLoadedAt >= 0 && clock - tailLoadedAt >= loadMs
        val tailSounding get() = tailPlayAt >= 0 && clock - tailPlayAt >= latencyMs
        val tailPosition get() = when {
            tailPausedPos >= 0 -> tailPausedPos
            tailSounding -> minOf(songMs, tailFrom + clock - tailPlayAt - latencyMs)
            else -> tailFrom
        }

        fun now() = CrossfadePlan.Now(
            seconds, index, position, songMs, playing, hasNext = index < 3, sameAlbum = false, repeatOne = false, held = false,
            tailReady = tailReady, tailPlaying = tailPlayAt >= 0 && tailPausedPos < 0, tailPositionMs = tailPosition,
            tailEnded = tailPosition >= songMs, clockMs = clock,
        )

        /** How loud song [i] is heard now, from either player. */
        fun heard(i: Int): Float {
            var v = 0f
            if (index == i && playing) v += volume
            if (i == index - 1 && tailSounding && tailPausedPos < 0 && playing && tailPosition < songMs) v += tailVolume
            return v
        }

        fun step(ms: Long) {
            clock += ms
            if (playing) {
                position += ms
                if (position >= songMs) { index++; position -= songMs }
            }
            for (a in plan.next(now())) {
                actions += a
                when (a) {
                    is Action.Arm -> { tailLoadedAt = clock; tailFrom = a.fromMs; tailVolume = 0f; tailPlayAt = -1 }
                    Action.StartTail -> tailPlayAt = clock
                    Action.Swap -> { tailVolume = 1f; volume = 0f; index++; position = 0 }
                    is Action.Levels -> { tailVolume = a.tail; volume = a.player }
                    Action.PauseTail -> tailPausedPos = tailPosition
                    Action.ResumeTail -> { tailFrom = tailPausedPos; tailPausedPos = -1; tailPlayAt = clock - latencyMs }
                    Action.Drop -> { tailLoadedAt = -1; tailPlayAt = -1; tailPausedPos = -1; tailVolume = 0f; volume = 1f }
                }
            }
        }

        fun run(untilMs: Long, each: (Sim) -> Unit = {}) {
            while (clock < untilMs) {
                step(if (plan.close(now())) 10 else 50)
                each(this)
            }
        }
    }

    @Test
    fun theSongGoingIsNeverSilentBeforeItFades() {
        val s = Sim()
        s.position = 15_000
        var heardDrop = 0f
        var leastPower = 9f
        s.run(20_000) {
            // Until the fade's start (24 s into the first song), the first song is heard in full.
            val beforeFade = it.index == 0 && it.position < 24_000
            if (beforeFade) heardDrop = maxOf(heardDrop, 1f - it.heard(0))
            // Through the fade, the two together keep their loudness.
            if (it.index == 1 && it.position in 100..5_500) {
                val a = it.heard(0); val b = it.heard(1)
                leastPower = minOf(leastPower, a * a + b * b)
            }
        }
        assertEquals(0f, heardDrop, "the song going dipped before its fade")
        assertTrue(leastPower > 0.9f, "loudness dipped to $leastPower in the fade")
        assertTrue(s.actions.any { it == Action.Swap })
        // And at the handover itself: the song going heard at full, from one player or the other.
        val i = s.actions.indexOf(Action.Swap)
        assertTrue(i > 0 && s.actions[i - 1] == Action.StartTail || s.actions.take(i).contains(Action.StartTail))
        // Done: the tail let go, the next song at full.
        assertEquals(CrossfadePlan.State.Idle, s.plan.state)
        assertEquals(1f, s.volume)
    }

    @Test
    fun theHandoverIsAtTheSameMoment() {
        val s = Sim(latencyMs = 80)
        s.position = 15_000
        var swappedAt = -1L
        val heard = mutableListOf<Pair<Long, Float>>()
        s.run(12_000) {
            if (swappedAt < 0 && it.actions.contains(Action.Swap)) swappedAt = it.clock
            heard += it.clock to it.heard(0)
        }
        assertTrue(swappedAt > 0)
        // Around the swap, every 10 ms: the song going at (all but) full, from one player or the other.
        val around = heard.filter { it.first in (swappedAt - 500)..(swappedAt + 100) }
        assertTrue(around.size > 50)
        val least = around.minOf { it.second }
        assertTrue(least > 0.99f, "the song going dropped to $least around the handover")
    }

    @Test
    fun aTailTooSlowToLoadMeansNoFadeNotACut() {
        val s = Sim(loadMs = 12_000)
        s.position = 15_000
        var least = 1f
        s.run(30_000) { if (it.index == 0) least = minOf(least, it.heard(0)) }
        assertEquals(1f, least, "the song was cut")
        assertFalse(s.actions.contains(Action.Swap))
        assertTrue(s.actions.contains(Action.Drop))
        assertEquals(1, s.index)
        assertEquals(1f, s.volume)
    }

    @Test
    fun aSeekIntoTheLastSecondsPlaysOut() {
        val s = Sim()
        s.position = 27_000
        s.run(4_000)
        assertTrue(s.actions.none { it is Action.Arm })
        assertEquals(1, s.index)
    }

    @Test
    fun aSkipWhileArmedLetsGo() {
        val s = Sim()
        s.position = 15_000
        s.run(1_000)
        assertEquals(CrossfadePlan.State.Armed, s.plan.state)
        s.index = 2; s.position = 0
        s.step(50)
        assertEquals(CrossfadePlan.State.Idle, s.plan.state)
        assertEquals(Action.Drop, s.actions.last())
    }

    @Test
    fun aPauseMidFadeHoldsBoth() {
        val s = Sim()
        s.position = 15_000
        s.run(11_000)
        assertEquals(CrossfadePlan.State.Fading, s.plan.state)
        s.playing = false
        s.step(50)
        assertEquals(Action.PauseTail, s.actions.last())
        val held = s.tailPosition
        repeat(20) { s.step(50) }
        assertEquals(held, s.tailPosition)
        s.playing = true
        s.step(50)
        assertTrue(s.actions.contains(Action.ResumeTail))
        s.run(20_000)
        assertEquals(CrossfadePlan.State.Idle, s.plan.state)
        assertEquals(1f, s.volume)
    }

    @Test
    fun turnedOffWhileArmedLetsGo() {
        val s = Sim()
        s.position = 15_000
        s.run(1_000)
        assertEquals(listOf(Action.Drop), s.plan.cancel())
        assertEquals(CrossfadePlan.State.Idle, s.plan.state)
        assertEquals(Crossfade.ARM_AHEAD_MS, 10_000L)
    }
}
