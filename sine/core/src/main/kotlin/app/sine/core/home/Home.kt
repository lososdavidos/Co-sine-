package app.sine.core.home

import app.sine.core.cosine.SmartPlaylist
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Home (§6.4): a canvas of tiles the person arranges. What a tile shows is
 * worked out on the phone; the arrangement is theirs, kept here and on
 * Cosine, the phone's and the desktop's each their own.
 */

/** A tile's three sizes (§6.4.2): small square, medium square, wide. */
@Serializable
enum class TileSize(val columns: Int, val rows: Int) {
    @SerialName("1x1") Small(1, 1),
    @SerialName("2x2") Medium(2, 2),
    @SerialName("4x2") Wide(4, 2);

    /** Resize goes small, medium, wide and round again. */
    fun next(): TileSize = entries[(ordinal + 1) % entries.size]
}

object TileKind {
    const val ARTIST = "artist"
    const val ALBUM = "album"
    /** A playlist; a list tile is a smart playlist pinned (§2.4). */
    const val PLAYLIST = "playlist"
    const val NOW_PLAYING = "now-playing"
    const val SHUFFLE_ALL = "shuffle-all"
    const val DOWNLOADED = "downloaded"
    const val REVIEW = "review"
    const val INGEST = "ingest"

    /** Something of yours pinned from its own page, rather than made by Sine. */
    fun pinned(kind: String) = kind == ARTIST || kind == ALBUM || kind == PLAYLIST
}

@Serializable
data class Tile(
    val kind: String,
    /** What it's of: an artist, album or playlist id; a smart playlist's is sp-…. */
    val ref: String? = null,
    val size: TileSize = TileSize.Medium,
    /** Its name when pinned, shown until the library has it. */
    val name: String? = null,
    /** Plays rather than opens (§6.4.1). */
    val shuffle: Boolean = false,
) {
    /** One tile per thing and way of opening it, so its key is what it is. */
    val id: String get() = listOfNotNull(kind, ref, "shuffle".takeIf { shuffle }).joinToString(":")
}

@Serializable
data class HomeLayout(
    val tiles: List<Tile> = emptyList(),
    /** When it last changed, in milliseconds: of two copies, the later wins. */
    val updated: Long = 0,
)

/** A tile and the cell its top-left corner sits in. */
data class Placed(val tile: Tile, val column: Int, val row: Int)

object TileGrid {
    const val COLUMNS = 4

    /**
     * Places the tiles in order, each in the first spot from the top where
     * it fits, so small tiles fill the gaps bigger ones leave. A tile sits
     * on a multiple of its own width, keeping squares in line (§6.4.2).
     */
    fun place(tiles: List<Tile>, columns: Int = COLUMNS): List<Placed> {
        val rows = ArrayList<BooleanArray>()
        fun taken(r: Int, c: Int) = r < rows.size && rows[r][c]
        fun fits(r: Int, c: Int, w: Int, h: Int) = (r until r + h).none { rr -> (c until c + w).any { taken(rr, it) } }
        return tiles.map { t ->
            val w = minOf(t.size.columns, columns)
            val h = t.size.rows
            var row = 0
            var column: Int
            while (true) {
                column = (0..columns - w step w).firstOrNull { fits(row, it, w, h) } ?: -1
                if (column >= 0) break
                row++
            }
            while (rows.size < row + h) rows += BooleanArray(columns)
            for (r in row until row + h) for (c in column until column + w) rows[r][c] = true
            Placed(t, column, row)
        }
    }

    /** How many rows [placed] tiles take. */
    fun height(placed: List<Placed>): Int = placed.maxOfOrNull { it.row + it.tile.size.rows } ?: 0
}

/** Changes to an arrangement: each returns the new one, the old untouched. */
object HomeEdits {
    /** Adds [tile] at the end, unless it's there already. */
    fun add(tiles: List<Tile>, tile: Tile): List<Tile> = if (tiles.any { it.id == tile.id }) tiles else tiles + tile

    fun remove(tiles: List<Tile>, id: String): List<Tile> = tiles.filterNot { it.id == id }

    fun resize(tiles: List<Tile>, id: String): List<Tile> = tiles.map { if (it.id == id) it.copy(size = it.size.next()) else it }

    /** Moves tile [id] to [index] in the order, where the grid places it from. */
    fun move(tiles: List<Tile>, id: String, index: Int): List<Tile> {
        val from = tiles.indexOfFirst { it.id == id }
        if (from < 0) return tiles
        val rest = tiles.toMutableList()
        val t = rest.removeAt(from)
        rest.add(index.coerceIn(0, rest.size), t)
        return rest
    }
}

object HomeDefaults {
    /**
     * The first arrangement (§6.4): now playing across the top, then what's
     * new and what you play most, the quick ones in a block of small tiles,
     * then looking back. Only tiles this server and phone can fill: list
     * tiles are the account's own smart playlists, found by their rule.
     */
    fun tiles(smart: List<SmartPlaylist>, mirrored: Boolean, review: Boolean, ingest: Boolean): List<Tile> {
        fun list(rule: String, size: TileSize = TileSize.Medium) =
            smart.firstOrNull { it.rule == rule && it.id.isNotEmpty() }?.let { Tile(TileKind.PLAYLIST, it.id, size, it.name) }
        return listOfNotNull(
            Tile(TileKind.NOW_PLAYING, size = TileSize.Wide),
            list("added"),
            list("most-played"),
            Tile(TileKind.SHUFFLE_ALL, size = TileSize.Small).takeIf { mirrored },
            Tile(TileKind.DOWNLOADED, size = TileSize.Small),
            Tile(TileKind.REVIEW, size = TileSize.Small).takeIf { review },
            Tile(TileKind.INGEST, size = TileSize.Small).takeIf { ingest },
            list("this-time-last-year"),
            list("never-played"),
            list("forgotten"),
        )
    }
}
