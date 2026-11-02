package app.sine.desktop

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A small Subsonic server with real music's names (public domain works),
 * enough for the desktop's pages to show what they show: artists, albums,
 * songs, playlists and covers. Covers are plain colours.
 */
class FakeSubsonic {
    val server = MockWebServer()

    /** When set, the account's password: sign-ins are checked against it, and changePassword changes it. */
    @Volatile var password: String? = null
    val url: String get() = server.url("/").toString().trimEnd('/')

    private data class Album(val id: String, val name: String, val artist: String, val artistId: String, val year: Int, val colour: Color, val songs: List<Pair<String, Int>>)

    private val albums = listOf(
        Album(
            "al-1", "Goldberg Variations", "J. S. Bach", "ar-1", 1741, Color(0x2E3A46),
            listOf(
                "Aria" to 185, "Variation 1" to 105, "Variation 2" to 39, "Variation 3, Canone all'unisono" to 72,
                "Variation 4" to 46, "Variation 5" to 38, "Variation 6, Canone alla seconda" to 34, "Variation 7, al tempo di Giga" to 72,
                "Variation 8" to 54, "Variation 9, Canone alla terza" to 59, "Variation 10, Fughetta" to 43, "Variation 11" to 66,
                "Variation 12, Canone alla quarta" to 60, "Variation 13" to 270, "Variation 14" to 71, "Variation 15, Canone alla quinta" to 151,
            ),
        ),
        Album("al-2", "Gymnopédies", "Erik Satie", "ar-2", 1888, Color(0x8A5A3C), listOf("Gymnopédie No. 1" to 185, "Gymnopédie No. 2" to 160, "Gymnopédie No. 3" to 150)),
        Album("al-3", "Suite bergamasque", "Claude Debussy", "ar-3", 1905, Color(0x2F6B66), listOf("Prélude" to 270, "Menuet" to 300, "Clair de lune" to 302, "Passepied" to 230)),
        Album("al-4", "Nocturnes, Op. 9", "Frédéric Chopin", "ar-4", 1832, Color(0x6B3F70), listOf("Nocturne in B-flat minor" to 330, "Nocturne in E-flat major" to 273, "Nocturne in B major" to 420)),
        Album("al-5", "The Four Seasons", "Antonio Vivaldi", "ar-5", 1725, Color(0x8C7A2E), listOf("Spring, I. Allegro" to 202, "Spring, II. Largo" to 160, "Summer, III. Presto" to 170, "Autumn, I. Allegro" to 300)),
        Album("al-6", "Requiem", "W. A. Mozart", "ar-6", 1791, Color(0x7A2E2E), listOf("Introitus" to 290, "Kyrie" to 160, "Dies irae" to 110, "Lacrimosa" to 190)),
    )

    private fun song(a: Album, i: Int) = """{"id":"${a.id}-${i + 1}","title":${q(a.songs[i].first)},"album":${q(a.name)},"albumId":"${a.id}","artist":${q(a.artist)},"artistId":"${a.artistId}","track":${i + 1},"duration":${a.songs[i].second},"coverArt":"${a.id}","suffix":"flac","year":${a.year}${if (i == 0) ""","starred":"2026-10-01T10:00:00Z"""" else ""}}"""
    private fun songs(a: Album) = a.songs.indices.joinToString(",") { song(a, it) }
    private fun albumJson(a: Album, withSongs: Boolean) =
        """{"id":"${a.id}","name":${q(a.name)},"artist":${q(a.artist)},"artistId":"${a.artistId}","coverArt":"${a.id}","songCount":${a.songs.size},"duration":${a.songs.sumOf { it.second }},"year":${a.year}${if (withSongs) ""","song":[${songs(a)}]""" else ""}}"""
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun ok(inner: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body("""{"subsonic-response":{"status":"ok","version":"1.16.1"${if (inner.isEmpty()) "" else ",$inner"}}}""")
        .build()

    private fun cover(id: String): MockResponse {
        val a = albums.firstOrNull { it.id == id || it.artistId == id } ?: albums[0]
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = a.colour
        g.fillRect(0, 0, 64, 64)
        g.color = a.colour.brighter()
        g.fillOval(10, 10, 44, 44)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(bytes)).build()
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val id = url.queryParameter("id").orEmpty()
                password?.let { pw ->
                    val md5 = java.security.MessageDigest.getInstance("MD5").digest((pw + url.queryParameter("s")).toByteArray()).joinToString("") { "%02x".format(it) }
                    if (url.queryParameter("t") != md5) {
                        return MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password."}}}""").build()
                    }
                }
                return when (url.encodedPath.substringAfterLast('/').removeSuffix(".view")) {
                    "ping" -> ok("")
                    "getArtists" -> ok(""""artists":{"index":[{"name":"A","artist":[${albums.joinToString(",") { """{"id":"${it.artistId}","name":${q(it.artist)},"albumCount":1,"coverArt":"${it.id}"}""" }}]}]}""")
                    "getArtist" -> albums.first { it.artistId == id }.let { a ->
                        ok(""""artist":{"id":"${a.artistId}","name":${q(a.artist)},"albumCount":1,"coverArt":"${a.id}","album":[${albumJson(a, false)}]}""")
                    }
                    "getAlbumList2" -> ok(""""albumList2":{"album":[${albums.joinToString(",") { albumJson(it, false) }}]}""")
                    "getAlbum" -> ok(""""album":${albumJson(albums.first { it.id == id }, true)}""")
                    "getSong" -> albums.firstNotNullOf { a -> a.songs.indices.firstOrNull { "${a.id}-${it + 1}" == id }?.let { song(a, it) } }.let { ok(""""song":$it""") }
                    "getPlaylists" -> ok(""""playlists":{"playlist":[{"id":"pl-1","name":"Late and quiet","songCount":3,"duration":760,"coverArt":"al-2","owner":"me"}]}""")
                    "getPlaylist" -> ok(""""playlist":{"id":"pl-1","name":"Late and quiet","songCount":3,"duration":760,"coverArt":"al-2","owner":"me","entry":[${song(albums[1], 0)},${song(albums[2], 2)},${song(albums[3], 1)}]}""")
                    "getStarred2" -> ok(""""starred2":{"song":[${albums.joinToString(",") { song(it, 0) }}]}""")
                    "search3" -> {
                        val term = url.queryParameter("query").orEmpty().lowercase()
                        val hits = albums.filter { term in it.name.lowercase() || term in it.artist.lowercase() }
                        ok(""""searchResult3":{"album":[${hits.joinToString(",") { albumJson(it, false) }}],"song":[${hits.joinToString(",") { songs(it) }}]}""")
                    }
                    "getCoverArt" -> cover(id)
                    "star", "unstar", "scrobble" -> ok("")
                    "changePassword" -> {
                        password = String(url.queryParameter("password")!!.removePrefix("enc:").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                        ok("")
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    fun close() = server.close()
}
