package app.sine.core

import app.sine.core.play.shuffledFrom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ShuffleTest {
    @Test
    fun theSongPlayingGoesFirstAndEveryOtherFollows() {
        repeat(50) { seed ->
            val order = shuffledFrom(current = 7, count = 20, random = Random(seed))
            assertEquals(7, order.first())
            assertEquals((0 until 20).toList(), order.sorted())
        }
    }

    @Test
    fun nothingPlayingShufflesThemAll() {
        assertEquals((0 until 5).toList(), shuffledFrom(-1, 5).sorted())
        assertEquals(emptyList(), shuffledFrom(-1, 0).toList())
    }
}
