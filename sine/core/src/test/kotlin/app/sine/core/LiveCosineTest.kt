package app.sine.core

import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.ManualIdentity
import app.sine.core.download.DownloadIndex
import app.sine.core.download.DownloadPlanner
import app.sine.core.model.DownloadNode
import app.sine.core.server.Capability
import app.sine.core.server.ProbeResult
import app.sine.core.server.ServerKind
import app.sine.core.server.ServerProbe
import app.sine.core.subsonic.AlbumListType
import app.sine.core.subsonic.SubsonicClient
import app.sine.core.subsonic.SubsonicCredentials
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sine's real client code against a real, running Cosine: the contract
 * between the two, end to end. Skipped unless COSINE_URL is set.
 *
 * The server must be set up with account COSINE_USER / COSINE_PASS holding
 * the fixture library the harness creates (see .github/workflows/sine.yml):
 * "Tides" and "Surge" by "Contract Fixture" on the release "Tides EP", with a
 * cover, one playlist, and FETCH_URL serving a downloadable audio file.
 */
class LiveCosineTest {
    private val url = System.getenv("COSINE_URL")
    private val http = OkHttpClient()

    private fun bytes(u: String, range: String? = null): Pair<Int, ByteArray> {
        val req = Request.Builder().url(u).apply { if (range != null) header("Range", range) }.build()
        return http.newCall(req).execute().use { it.code to it.body.bytes() }
    }

    @Test
    fun `sine and cosine speak the same language`(): Unit = runBlocking {
        assumeTrue("set COSINE_URL to run against a live server", url != null)
        val creds = SubsonicCredentials.fromPassword(System.getenv("COSINE_USER"), System.getenv("COSINE_PASS"))

        // First run (§6.20): the probe names the server before login.
        val found = assertIs<ProbeResult.Found>(ServerProbe(http).probe(url!!.removePrefix("http://")))
        val kind = assertIs<ServerKind.Cosine>(found.kind)
        assertEquals("Cosine · full features", kind.label)
        assertTrue(Capability.INGEST in kind.capabilities && Capability.REVIEW in kind.capabilities, "$kind")

        // Browsing over the Subsonic API, exactly as the app does.
        val sub = SubsonicClient(found.baseUrl, creds, http)
        sub.ping()
        val fixture = sub.artists().single { it.name == "Contract Fixture" }
        val ep = sub.artist(fixture.id).albums.single { it.name == "Tides EP" }
        val album = sub.album(ep.id)
        assertEquals(listOf("Tides", "Surge"), album.tracks.map { it.title })
        val tides = album.tracks[0]
        assertEquals(1, tides.trackNumber)
        assertEquals("mp3", tides.suffix)
        assertEquals(tides, sub.track(tides.id))
        for (type in AlbumListType.entries) sub.albums(type, size = 50)
        assertTrue(sub.albums(AlbumListType.NEWEST).any { it.id == ep.id })
        assertTrue(sub.search("tides").tracks.any { it.id == tides.id })
        val playlist = sub.playlists().single()
        assertEquals(listOf("Surge", "Tides"), sub.playlist(playlist.id).tracks.map { it.title })

        // Playback and downloads: original bytes, seekable; artwork.
        val (code, full) = bytes(sub.downloadUrl(tides.id))
        assertEquals(200, code)
        assertEquals(tides.sizeBytes, full.size.toLong())
        val (partial, tail) = bytes(sub.streamUrl(tides.id), "bytes=-100")
        assertEquals(206, partial)
        assertTrue(full.takeLast(100).toByteArray().contentEquals(tail))
        val (artCode, art) = bytes(sub.coverArtUrl(tides.coverArt!!, 600))
        assertEquals(200, artCode)
        assertTrue(art.isNotEmpty())
        sub.scrobble(tides.id, 1_700_000_000_000)
        sub.scrobble(tides.id, 1_700_000_000_000) // an offline resend
        assertTrue(sub.albums(AlbumListType.RECENT).any { it.id == ep.id })

        // Download units expand the same way against Cosine.
        val planner = DownloadPlanner(sub, DownloadIndex(Files.createTempFile("idx", ".json").toFile()))
        assertEquals(2, planner.expand(DownloadNode.OfAlbum(ep.id)).size)
        assertEquals(2, planner.expand(DownloadNode.OfArtist(fixture.id)).size)
        assertEquals(2, planner.expand(DownloadNode.OfPlaylist(playlist.id)).size)
        assertEquals(tides.id, planner.expand(DownloadNode.OfTrack(tides.id)).single().id)

        // The native protocol: review.
        val cosine = CosineClient(found.baseUrl, creds, http)
        assertEquals(kind, cosine.describe())
        val queue = cosine.reviewQueue()
        assertTrue(queue.total >= 2, "$queue")
        val surge = queue.items.single { it.identity.title == "Surge" }
        assertEquals(surge.trackId, cosine.reviewItem(surge.trackId).trackId)
        try {
            cosine.candidates(surge.trackId) // may be empty; MusicBrainz may be unreachable
        } catch (e: CosineException) {
            assertEquals(502, e.status)
        }
        val fixed = cosine.correct(surge.trackId, ManualIdentity(artist = "Contract Fixture", title = "Surge (VIP)", release = "Tides EP", trackNo = 2))
        assertTrue(fixed.identity.reviewed)
        assertEquals("manual", fixed.identity.source)
        assertTrue(sub.album(ep.id).tracks.any { it.title == "Surge (VIP)" }, "a correction shows up when browsing")
        val tidesItem = queue.items.single { it.identity.title == "Tides" }
        assertTrue(cosine.confirm(tidesItem.trackId).identity.reviewed)
        assertEquals(queue.total - 2, cosine.reviewQueue().total)

        // The native protocol: adding music from a link.
        val link = System.getenv("FETCH_URL")
        val lookup = cosine.lookup("Listen to this $link")
        assertEquals("single", lookup.kind)
        val job = cosine.submit(listOf(link)).single()
        var status = job
        repeat(100) {
            if (!status.isActive) return@repeat
            delay(200)
            status = cosine.jobs().single { it.id == job.id }
        }
        assertEquals("done", status.status, "$status")
        val fetched = sub.track(status.trackId!!)
        assertTrue(fetched.sizeBytes!! > 0)
        assertEquals("known", cosine.submit(listOf(link)).single().let { j ->
            var s = j
            repeat(100) { if (s.isActive) { delay(200); s = cosine.jobs().single { it.id == j.id } } }
            s.status
        })
        assertFailsWith<CosineException> { cosine.retry(status.id) } // a finished job can't be retried
    }
}
