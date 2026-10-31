package app.sine.core

import app.sine.core.play.insertedAfter
import app.sine.core.play.removedRange
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

    @Test
    fun songsAddedToTheQueueStillGoNextOnceShuffled() {
        repeat(50) { seed ->
            val order = shuffledFrom(current = 3, count = 12, queued = listOf(4, 5, 6), random = Random(seed))
            assertEquals(listOf(3, 4, 5, 6), order.take(4))
            assertEquals((0 until 12).toList(), order.sorted())
        }
    }

    @Test
    fun addedSongsGoRightAfterTheSongBeforeThemInTheOrder() {
        // Playing 2, shuffled: 2 then 0 then 3 then 1.
        val order = intArrayOf(2, 0, 3, 1)
        // Two added after 2 (at 3): they play next, in order; 3 moved to 5.
        assertEquals(listOf(2, 3, 4, 0, 5, 1), insertedAfter(order, 3, 2).toList())
        // Another after those (at 5, after 4): below them.
        assertEquals(listOf(2, 3, 4, 5, 0, 6, 1), insertedAfter(insertedAfter(order, 3, 2), 5, 1).toList())
        // At the front.
        assertEquals(listOf(0, 3, 1, 4, 2), insertedAfter(order, 0, 1).toList())
        // Into nothing: shuffled.
        assertEquals((0 until 6).toList(), insertedAfter(IntArray(0), 0, 6, Random(1)).sorted())
    }

    @Test
    fun takingSongsOutKeepsTheRestInOrder() {
        assertEquals(listOf(1, 0, 3, 2), removedRange(intArrayOf(3, 1, 0, 4, 2), 3, 4).toList())
        assertEquals(listOf(0, 1), removedRange(intArrayOf(2, 0, 3, 1), 1, 3).toList())
    }
}
