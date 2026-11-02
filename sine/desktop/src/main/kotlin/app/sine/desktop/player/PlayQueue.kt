package app.sine.desktop.player

import app.sine.core.model.Track
import kotlin.random.Random

/** Repeat: off, the queue round again, or this song. */
enum class Repeat { OFF, ALL, ONE }

/** A song in the queue. [queued]: added by hand, rather than part of what's playing. */
data class Entry(val track: Track, val queued: Boolean = false, val key: Long = 0)

/**
 * The queue (#37), the same rules as on the phone: songs you add play next,
 * after the song playing and before the rest, in the order you added them,
 * shuffled or not. The rest, Up next, is what was playing: the album, the
 * playlist.
 *
 * [entries] are the songs as they came; [order] is how they play, as places
 * in [entries], and [pos] is where in [order] the song playing is. Every
 * change makes a new queue: the player holds one at a time.
 */
data class PlayQueue(
    val entries: List<Entry> = emptyList(),
    val order: List<Int> = emptyList(),
    val pos: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.OFF,
    private val nextKey: Long = 1,
) {
    val current: Entry? get() = order.getOrNull(pos)?.let { entries[it] }
    val isEmpty: Boolean get() = order.isEmpty()

    /** The songs after the one playing, in the order they play, with their places in [order]. */
    val ahead: List<Pair<Int, Entry>> get() = ((pos + 1) until order.size).map { it to entries[order[it]] }

    /** Added by hand and still to come: they play next. */
    val queuedAhead: List<Pair<Int, Entry>> get() = ahead.takeWhile { it.second.queued }

    /** The rest: what was playing goes on after the songs added. */
    val upNext: List<Pair<Int, Entry>> get() = ahead.dropWhile { it.second.queued }

    /** Plays [tracks] from [index]; shuffled, that one first and the rest in any order. */
    fun start(tracks: List<Track>, index: Int, random: Random = Random): PlayQueue {
        if (tracks.isEmpty()) return copy(entries = emptyList(), order = emptyList(), pos = -1)
        val at = index.coerceIn(0, tracks.lastIndex)
        val entries = tracks.mapIndexed { i, t -> Entry(t, key = nextKey + i) }
        val order = if (shuffle) listOf(at) + (tracks.indices - at).shuffled(random) else tracks.indices.toList()
        return copy(entries = entries, order = order, pos = order.indexOf(at), nextKey = nextKey + tracks.size)
    }

    /** Adds songs to the queue: after the song playing and those added before them. Nothing playing, they start. */
    fun add(tracks: List<Track>): PlayQueue {
        if (tracks.isEmpty()) return this
        if (isEmpty) return start(tracks, 0).let { q -> q.copy(entries = q.entries.map { it.copy(queued = true) }) }
        val added = tracks.mapIndexed { i, t -> Entry(t, queued = true, key = nextKey + i) }
        val first = entries.size
        val at = pos + 1 + queuedAhead.size
        val order = order.toMutableList().apply { addAll(at, added.indices.map { first + it }) }
        return copy(entries = entries + added, order = order, nextKey = nextKey + tracks.size)
    }

    /** The next song's place, or null at the end (round again with repeat). Repeat one stays: the player plays it again. */
    fun next(): PlayQueue? = when {
        pos + 1 < order.size -> copy(pos = pos + 1)
        repeat == Repeat.ALL && order.isNotEmpty() -> copy(pos = 0)
        else -> null
    }

    fun previous(): PlayQueue = when {
        pos > 0 -> copy(pos = pos - 1)
        repeat == Repeat.ALL && order.isNotEmpty() -> copy(pos = order.lastIndex)
        else -> this
    }

    /**
     * Plays the song at [at], its place in [order]. One further on than the
     * songs added to the queue leaves them still next, after it; one of them
     * skips those before it.
     */
    fun skipTo(at: Int): PlayQueue {
        if (at !in order.indices) return this
        val queued = queuedAhead.map { it.first }
        if (queued.isEmpty() || at in queued || at <= pos) return copy(pos = at)
        val moved = queued.map { order[it] }
        val rest = order.filterIndexed { i, _ -> i !in queued }.toMutableList()
        val target = rest.indexOf(order[at])
        rest.addAll(target + 1, moved)
        return copy(order = rest, pos = target)
    }

    /** Takes the song at [at] out of what's to come. */
    fun remove(at: Int): PlayQueue {
        if (at !in order.indices || at == pos) return this
        return copy(order = order.filterIndexed { i, _ -> i != at }, pos = if (at < pos) pos - 1 else pos)
    }

    /**
     * Shuffle on: the song playing stays, the songs added stay next, the rest
     * play in any order. Off: the rest go back to their own order.
     */
    fun withShuffle(on: Boolean, random: Random = Random): PlayQueue {
        if (on == shuffle) return this
        val playing = order.getOrNull(pos) ?: return copy(shuffle = on)
        val queued = queuedAhead.map { order[it.first] }
        if (on) {
            val rest = ahead.map { order[it.first] }.filter { it !in queued }.shuffled(random)
            return copy(order = order.take(pos + 1) + queued + rest, shuffle = true)
        }
        // What was playing, in its own order; songs added that have played drop out.
        val own = entries.indices.filter { !entries[it].queued }
        val newOrder = if (entries[playing].queued) listOf(playing) + queued + own
        else own.flatMap { if (it == playing) listOf(it) + queued else listOf(it) }
        return copy(order = newOrder, pos = newOrder.indexOf(playing), shuffle = false)
    }

    fun withRepeat(mode: Repeat): PlayQueue = copy(repeat = mode)

    /** Repeat goes off, the queue, this song, and round again: the phone's order. */
    fun nextRepeat(): Repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]

    /** The songs as they play, from the first. */
    val tracks: List<Track> get() = order.map { entries[it].track }
}
