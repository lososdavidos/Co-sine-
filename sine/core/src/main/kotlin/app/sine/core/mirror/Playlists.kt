package app.sine.core.mirror

import app.sine.core.cosine.SmartPlaylist
import kotlinx.serialization.Serializable

/**
 * A manual playlist as Cosine sends it (§2.4): every entry in order, shown
 * or not. Those whose track isn't in the mirror are hidden, as Cosine hides
 * them, and keep their place.
 */
@Serializable
data class MirrorPlaylist(
    val id: String,
    val name: String,
    val owner: String = "",
    val mine: Boolean = true,
    val sharedWith: List<String> = emptyList(),
    val invited: List<String> = emptyList(),
    val tracks: List<String> = emptyList(),
    val created: String? = null,
    val changed: String? = null,
)

/** A playlist someone shared with this account, waiting for an answer (§2.3). */
@Serializable
data class PlaylistInvite(val id: String, val name: String, val owner: String, val songs: Int = 0)

/** A smart playlist as last worked out by Cosine: its rule and its songs. */
@Serializable
data class MirrorSmart(
    val id: String,
    val name: String,
    val rule: String,
    val span: String? = null,
    val months: Int? = null,
    val days: Int? = null,
    val size: Int = 100,
    val description: String = "",
    val tracks: List<String> = emptyList(),
) {
    fun toRule() = SmartPlaylist(id, name, rule, span, months, days, size, description)
}

/**
 * One playlist edit made here, kept until Cosine has it (§5.2): what was
 * done, not the list that resulted, so edits made elsewhere meanwhile stay.
 * An entry is its track and which of that track's entries it is ([nth],
 * from 0), counted in the playlist just before the op, hidden entries
 * included. A playlist made here and not yet sent is named by the id of
 * the create that made it, in [new].
 */
@Serializable
data class PlaylistOp(
    val id: String,
    val op: String,
    val playlist: String? = null,
    val new: String? = null,
    val name: String? = null,
    val tracks: List<String>? = null,
    val track: String? = null,
    val nth: Int = 0,
    val after: String? = null,
    val afterNth: Int = 0,
)

@Serializable
data class PlaylistOpResult(val id: String, val playlist: String? = null, val skipped: String? = null)

/**
 * Playlist edits as ops, and what they do: the same rules as Cosine's
 * (playlists.Op), so what this phone shows while offline is what Cosine
 * ends up with.
 */
object PlaylistOps {
    const val CREATE = "create"
    const val RENAME = "rename"
    const val ADD = "add"
    const val REMOVE = "remove"
    const val MOVE = "move"
    const val DELETE = "delete"

    private const val LOCAL = "new:"

    /** The id a playlist made here goes by until Cosine has it. */
    fun localId(createId: String) = LOCAL + createId

    fun isLocal(id: String) = id.startsWith(LOCAL)

    /** An op on playlist [id], local or Cosine's. */
    fun on(id: String, opId: String, op: String): PlaylistOp =
        if (isLocal(id)) PlaylistOp(opId, op, new = id.removePrefix(LOCAL)) else PlaylistOp(opId, op, playlist = id)

    fun create(opId: String, name: String, tracks: List<String>) = PlaylistOp(opId, CREATE, name = name, tracks = tracks)

    /** Whether [op] changes playlist [id], local or Cosine's: what makes it, or an edit to it. */
    fun touches(op: PlaylistOp, id: String): Boolean =
        if (isLocal(id)) id.removePrefix(LOCAL).let { op.new == it || (op.op == CREATE && op.id == it) }
        else op.playlist == id

    /** Applies [op] to [lists], playlists by id. An op that no longer fits (its playlist gone) changes nothing. */
    fun apply(lists: Map<String, MirrorPlaylist>, op: PlaylistOp): Map<String, MirrorPlaylist> {
        if (op.op == CREATE) {
            val name = op.name?.trim().orEmpty()
            if (name.isEmpty()) return lists
            val id = localId(op.id)
            return lists + (id to MirrorPlaylist(id, name, tracks = op.tracks.orEmpty()))
        }
        val id = op.new?.let { localId(it) } ?: op.playlist ?: return lists
        val p = lists[id] ?: return lists
        val changed = when (op.op) {
            RENAME -> op.name?.trim()?.takeIf { it.isNotEmpty() }?.let { p.copy(name = it) }
            DELETE -> return lists - id
            ADD -> op.tracks?.takeIf { it.isNotEmpty() }?.let { p.copy(tracks = p.tracks + it) }
            REMOVE -> {
                val i = nth(p.tracks, op.track ?: return lists, op.nth)
                if (i < 0) null else p.copy(tracks = p.tracks.toMutableList().apply { removeAt(i) })
            }
            MOVE -> move(p.tracks, op.track ?: return lists, op.nth, op.after, op.afterNth)?.let { p.copy(tracks = it) }
            else -> null
        }
        return if (changed == null) lists else lists + (id to changed)
    }

    /**
     * Moves the [n]th entry of [track] to just after the [afterNth] entry of
     * [after], or to the top when [after] is null, both counted in [tracks]
     * as given. Null when either isn't there.
     */
    fun move(tracks: List<String>, track: String, n: Int, after: String?, afterNth: Int): List<String>? {
        val from = nth(tracks, track, n)
        if (from < 0) return null
        var to = 0
        if (after != null) {
            val a = nth(tracks, after, afterNth)
            if (a < 0) return null
            if (a == from) return tracks
            to = if (a > from) a else a + 1 // counted before the entry left its place
        }
        return tracks.toMutableList().apply { removeAt(from); add(to, track) }
    }

    /** The index of [track]'s [n]th entry (from 0); -1 if there isn't one. */
    fun nth(tracks: List<String>, track: String, n: Int): Int {
        var seen = 0
        tracks.forEachIndexed { i, t ->
            if (t == track) {
                if (seen == n) return i
                seen++
            }
        }
        return -1
    }

    /** Which of its track's entries the entry at [index] is. */
    private fun nthAt(tracks: List<String>, index: Int) = (0 until index).count { tracks[it] == tracks[index] }

    /**
     * The ops that take out the entries at [indexes] of [tracks] (indexes
     * into every entry). Each is counted in the list as it is just before
     * it, so the last go first.
     */
    fun remove(id: String, tracks: List<String>, indexes: List<Int>, newId: () -> String): List<PlaylistOp> =
        indexes.distinct().sortedDescending().filter { it in tracks.indices }.map { i ->
            on(id, newId(), REMOVE).copy(track = tracks[i], nth = nthAt(tracks, i))
        }

    /**
     * The moves that put a playlist's shown entries in a new order. [shown]
     * are the indexes (into every entry, [tracks]) of the entries shown, in
     * order; [order] is the new order, as indexes into [shown]. Hidden
     * entries stay where they are. Only entries out of place move: those
     * outside the longest run already in order, each to just after the
     * entry that now comes before it.
     */
    fun reorder(id: String, tracks: List<String>, shown: List<Int>, order: List<Int>, newId: () -> String): List<PlaylistOp> {
        require(order.sorted() == shown.indices.toList()) { "order must rearrange the shown entries" }
        val wanted = order.map { shown[it] } // entries, by their place in tracks now
        val stay = longestRising(wanted).toSet()
        // Entries are followed by their place now. Each op counts its
        // entries in the list as it is just before it, as Cosine will.
        val sim = tracks.indices.toMutableList()
        val ops = mutableListOf<PlaylistOp>()
        wanted.forEachIndexed { k, entry ->
            if (entry in stay) return@forEachIndexed
            val now = sim.map { tracks[it] }
            val from = sim.indexOf(entry)
            val prev = if (k == 0) null else wanted[k - 1]
            var op = on(id, newId(), MOVE).copy(track = tracks[entry], nth = nthAt(now, from))
            if (prev != null) op = op.copy(after = tracks[prev], afterNth = nthAt(now, sim.indexOf(prev)))
            sim.removeAt(from)
            sim.add(if (prev == null) 0 else sim.indexOf(prev) + 1, entry)
            ops += op
        }
        return ops
    }

    /** The longest subsequence of [xs] that rises, as values. */
    private fun longestRising(xs: List<Int>): List<Int> {
        if (xs.isEmpty()) return emptyList()
        val length = IntArray(xs.size) { 1 }
        val before = IntArray(xs.size) { -1 }
        for (i in xs.indices) for (j in 0 until i) {
            if (xs[j] < xs[i] && length[j] + 1 > length[i]) {
                length[i] = length[j] + 1
                before[i] = j
            }
        }
        var i = length.indices.maxBy { length[it] }
        val out = ArrayList<Int>()
        while (i >= 0) {
            out += xs[i]
            i = before[i]
        }
        return out.reversed()
    }
}
