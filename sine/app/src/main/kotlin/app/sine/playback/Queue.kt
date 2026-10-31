package app.sine.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import app.sine.core.play.insertedAfter
import app.sine.core.play.removedRange
import kotlin.random.Random

/*
 * The queue (#37): songs you add to it play next, after the song playing
 * and before the rest, in the order you added them, shuffled or not. The
 * rest, Next up, is what was playing: the album, the playlist.
 */

/** A song added to the queue carries this in its metadata, which makes it to the service and back. */
private const val QUEUED = "app.sine.queued"

/** Added to the queue by hand, rather than part of what's playing. */
val MediaItem.isQueued: Boolean get() = mediaMetadata.extras?.getBoolean(QUEUED) == true

fun MediaItem.asQueued(): MediaItem = buildUpon()
    .setMediaMetadata(mediaMetadata.buildUpon().setExtras(Bundle(mediaMetadata.extras ?: Bundle()).apply { putBoolean(QUEUED, true) }).build())
    .build()

/** The songs after the one playing, in the order they play: their places in the list. */
private fun Player.ahead(): Sequence<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return emptySequence()
    return generateSequence(timeline.getNextWindowIndex(currentMediaItemIndex, Player.REPEAT_MODE_OFF, shuffleModeEnabled)) {
        timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }.takeWhile { it != C.INDEX_UNSET }.take(timeline.windowCount)
}

/** The songs added to the queue that play next, in order: their places in the list. */
fun Player.queuedAhead(): List<Int> = ahead().takeWhile { getMediaItemAt(it).isQueued }.toList()

/** Adds songs to the queue: after the song playing and those added before them. */
fun Player.queue(items: List<MediaItem>) {
    val after = queuedAhead().lastOrNull() ?: currentMediaItemIndex
    addMediaItems(after + 1, items.map { it.asQueued() })
}

/**
 * Plays the song at [at], its place in the list. One further on than the
 * songs added to the queue leaves them still next, after it; one of them
 * skips those before it.
 */
fun Player.skipTo(at: Int) {
    val queued = queuedAhead()
    if (queued.isEmpty() || at in queued || at !in ahead()) return seekToDefaultPosition(at)
    val items = queued.map { getMediaItemAt(it) }
    seekToDefaultPosition(at)
    for (i in queued.sortedDescending()) removeMediaItem(i)
    addMediaItems(at - queued.count { it < at } + 1, items)
}

/**
 * Plays [items] from [index] instead of what was playing; songs added to
 * the queue and not yet played still go next. Shuffled, it starts with a
 * song picked at random, and the service puts the rest in a random order
 * after it and them (PlaybackService).
 */
fun Player.replaceWith(items: List<MediaItem>, index: Int, startMs: Long, shuffle: Boolean, random: Random = Random.Default) {
    val kept = queuedAhead().map { getMediaItemAt(it) }
    val start = if (shuffle) random.nextInt(items.size) else index
    shuffleModeEnabled = false
    setMediaItems(items, start, if (shuffle) 0 else startMs)
    if (kept.isNotEmpty()) addMediaItems(start + 1, kept)
    shuffleModeEnabled = shuffle
}

/**
 * Media3's shuffled order, except that songs put into the list go right
 * after the song before them there, in order, instead of anywhere at
 * random: added to the queue, they play next while shuffled too.
 */
@OptIn(UnstableApi::class)
class QueueOrder(private val order: IntArray, private val random: Random = Random.Default) : ShuffleOrder {
    private val place = IntArray(order.size).also { for ((p, i) in order.withIndex()) it[i] = p }

    override fun getLength() = order.size
    override fun getNextIndex(index: Int) = order.getOrNull(place[index] + 1) ?: C.INDEX_UNSET
    override fun getPreviousIndex(index: Int) = order.getOrNull(place[index] - 1) ?: C.INDEX_UNSET
    override fun getLastIndex() = order.lastOrNull() ?: C.INDEX_UNSET
    override fun getFirstIndex() = order.firstOrNull() ?: C.INDEX_UNSET
    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder =
        QueueOrder(insertedAfter(order, insertionIndex, insertionCount, random), random)
    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder =
        QueueOrder(removedRange(order, indexFrom, indexToExclusive), random)
    override fun cloneAndClear(): ShuffleOrder = QueueOrder(IntArray(0), random)
}
