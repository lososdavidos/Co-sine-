package app.sine.core.play

import kotlin.random.Random

/**
 * The order a queue of [count] plays in once shuffle is turned on while
 * [current] plays: that one first, then the rest at random. Shuffling the
 * whole queue instead leaves [current] somewhere in the middle of the order,
 * and everything before it there never plays: shuffle stopped early.
 */
fun shuffledFrom(current: Int, count: Int, random: Random = Random.Default): IntArray {
    val rest = (0 until count).filter { it != current }.shuffled(random)
    return if (current in 0 until count) intArrayOf(current) + rest.toIntArray() else rest.toIntArray()
}
