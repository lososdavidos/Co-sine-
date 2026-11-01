package app.sine.core

import app.sine.core.cosine.Jam
import app.sine.core.cosine.JamSong
import app.sine.core.cosine.at
import kotlin.test.Test
import kotlin.test.assertEquals

class JamClockTest {
    private val jam = Jam(
        id = 1, host = "alice",
        queue = listOf(JamSong("tr-1", "One", "A", durationSec = 100), JamSong("tr-2", "Two", "A", durationSec = 200)),
        current = 0, positionMs = 90_000, playing = true, serverTime = 1_000_000,
    )

    @Test
    fun theMusicMovesOnWithTheClock() {
        assertEquals(0 to 90_000L, jam.at(1_000_000))
        assertEquals(0 to 95_000L, jam.at(1_005_000))
        // Past the first song's end: into the second.
        assertEquals(1 to 20_000L, jam.at(1_030_000))
        // Past the last: at its end.
        assertEquals(1 to 200_000L, jam.at(9_000_000))
    }

    @Test
    fun pausedStaysPut() {
        assertEquals(0 to 90_000L, jam.copy(playing = false).at(5_000_000))
        assertEquals(0 to 0L, jam.copy(queue = emptyList()).at(5_000_000))
    }
}
