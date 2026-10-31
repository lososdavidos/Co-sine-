package app.sine.core.play

import kotlin.random.Random

/**
 * The order a queue of [count] plays in once shuffle is turned on while
 * [current] plays: that one first, then the songs added to the queue
 * ([queued], in the order they were added: they still go next, #37), then
 * the rest at random. Shuffling the whole queue instead leaves [current]
 * somewhere in the middle of the order, and everything before it there
 * never plays: shuffle stopped early.
 */
fun shuffledFrom(current: Int, count: Int, queued: List<Int> = emptyList(), random: Random = Random.Default): IntArray {
    val first = listOfNotNull(current.takeIf { it in 0 until count }) + queued.filter { it in 0 until count && it != current }
    val rest = (0 until count).filter { it !in first }.shuffled(random)
    return (first + rest).toIntArray()
}

/**
 * The play order once [count] songs are put into the list at [at]: right
 * after the song before them there, in the order given, so songs added to
 * the queue play next even while shuffled (#37). Into an empty list they
 * are shuffled, as a new queue played shuffled is.
 */
fun insertedAfter(order: IntArray, at: Int, count: Int, random: Random = Random.Default): IntArray {
    val added = List(count) { at + it }
    if (order.isEmpty()) return added.shuffled(random).toIntArray()
    val shifted = order.map { if (it >= at) it + count else it }
    val place = if (at == 0) 0 else shifted.indexOf(at - 1) + 1
    return (shifted.subList(0, place) + added + shifted.subList(place, shifted.size)).toIntArray()
}

/** The play order once the songs from [from] up to [to] are taken out of the list. */
fun removedRange(order: IntArray, from: Int, to: Int): IntArray {
    val n = to - from
    return order.filter { it < from || it >= to }.map { if (it >= to) it - n else it }.toIntArray()
}
