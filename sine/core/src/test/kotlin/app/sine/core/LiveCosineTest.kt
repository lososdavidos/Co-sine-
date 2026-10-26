package app.sine.core

import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.ManualIdentity
import app.sine.core.download.DownloadIndex
import app.sine.core.download.DownloadPlanner
import app.sine.core.mirror.LibraryMirror
import app.sine.core.mirror.PlaylistOps
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
import kotlin.test.assertNotNull
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
        val playlist = sub.playlists().single { !it.readonly }
        assertEquals(listOf("Surge", "Tides"), sub.playlist(playlist.id).tracks.map { it.title })

        // Playlists made and edited the way the app does it, over plain Subsonic (§2.4).
        assertTrue(Capability.PLAYLISTS in kind.capabilities, "$kind")
        val surgeId = album.tracks[1].id
        val made = assertNotNull(sub.createPlaylist("Contract mix", listOf(tides.id)))
        sub.updatePlaylist(made, add = listOf(surgeId, tides.id))
        assertEquals(listOf("Tides", "Surge", "Tides"), sub.playlist(made).tracks.map { it.title })
        sub.updatePlaylist(made, name = "Contract mix 2", removeAt = listOf(2))
        sub.replacePlaylist(made, listOf(surgeId, tides.id))
        val edited = sub.playlist(made)
        assertEquals("Contract mix 2", edited.playlist.name)
        assertEquals(listOf("Surge", "Tides"), edited.tracks.map { it.title })
        sub.deletePlaylist(made)
        assertTrue(sub.playlists().none { it.id == made })

        // Smart playlists: listed beside them, read-only, a rule's songs.
        assertTrue(Capability.SMART_PLAYLISTS in kind.capabilities, "$kind")
        val smart = sub.playlists().filter { it.readonly }
        assertEquals(6, smart.size, "$smart")
        val recent = smart.single { it.name == "Recently added" }
        assertEquals("Added in the last 30 days", recent.comment)
        assertEquals(setOf("Tides", "Surge"), sub.playlist(recent.id).tracks.map { it.title }.toSet())
        assertFailsWith<app.sine.core.subsonic.SubsonicException> { sub.updatePlaylist(recent.id, add = listOf(tides.id)) }

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

        // A smart playlist's rule, made in the app, is a playlist everywhere.
        val rules = cosine.smartPlaylists()
        assertEquals(smart.map { it.id }.toSet(), rules.map { it.id }.toSet())
        val heavy = cosine.saveSmart(app.sine.core.model.SmartRules.starting("most-played", "Contract heavy").copy(span = "all"))
        assertEquals("Most played of all time", heavy.description)
        assertTrue(sub.playlists().any { it.id == heavy.id && it.readonly && it.name == "Contract heavy" })
        val dusty = cosine.saveSmart(app.sine.core.model.SmartRules.withSetting(heavy.copy(name = "Contract dust", rule = "forgotten", span = null)))
        assertEquals(6, dusty.months)
        sub.deletePlaylist(heavy.id)
        assertTrue(cosine.smartPlaylists().none { it.id == heavy.id })
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

        // The library mirror (§5.2): all of it once, and what it holds is
        // exactly what browsing over Subsonic shows.
        assertTrue(Capability.DELTA_SYNC in kind.capabilities, "$kind")
        val mirror = LibraryMirror(Files.createTempFile("library", ".json").toFile())
        val first = cosine.sync(0)
        assertTrue(first.full)
        mirror.apply(first)
        val mirrored = mirror.library.value
        assertEquals(sub.album(ep.id).tracks, mirrored.album(ep.id)!!.tracks)
        assertEquals(sub.artists().map { it.id to it.name }.toSet(), mirrored.artists().map { it.id to it.name }.toSet())
        assertEquals(2, mirrored.artist(fixture.id)!!.albums.single { it.id == ep.id }.trackCount)
        assertTrue(mirrored.search("surge").tracks.any { it.title == "Surge (VIP)" })
        val unchanged = cosine.sync(mirror.cursor, mirror.smartTag)
        assertTrue(!unchanged.full && unchanged.tracks.isEmpty() && unchanged.removedTracks.isEmpty(), "$unchanged")
        assertEquals(null, unchanged.smart, "smart playlists unchanged: not sent again")

        // Playlists come with the mirror, and edits made here go as ops (§5.2).
        assertTrue(Capability.PLAYLIST_SYNC in kind.capabilities, "$kind")
        assertEquals(listOf("Surge (VIP)", "Tides"), mirror.library.value.playlist(playlist.id)!!.tracks.map { it.title })
        assertEquals(6, mirror.library.value.playlists().count { it.readonly })
        var opNo = 0
        val newOp = { "contract-op-${++opNo}" }
        val create = PlaylistOps.create(newOp(), "Contract offline", listOf(tides.id))
        val local = PlaylistOps.localId(create.id)
        mirror.edit(listOf(create, PlaylistOps.on(local, newOp(), PlaylistOps.ADD).copy(tracks = listOf(surgeId))))
        val entries = mirror.library.value.entries(local)!!
        mirror.edit(PlaylistOps.reorder(local, entries, entries.indices.toList(), listOf(1, 0), newOp))
        assertEquals(listOf("Surge (VIP)", "Tides"), mirror.library.value.playlist(local)!!.tracks.map { it.title })
        val results = cosine.playlistOps(mirror.pending)
        assertEquals(results, cosine.playlistOps(mirror.pending), "a resend changes nothing")
        mirror.sent(results)
        mirror.apply(cosine.sync(mirror.cursor, mirror.smartTag))
        val offline = mirror.library.value.playlist(local)!!
        assertTrue(offline.playlist.id.startsWith("pl-"), offline.playlist.id)
        assertEquals(listOf("Surge (VIP)", "Tides"), offline.tracks.map { it.title })
        assertEquals(listOf("Surge (VIP)", "Tides"), sub.playlist(offline.playlist.id).tracks.map { it.title })
        assertTrue(mirror.pending.isEmpty())

        // Shared with a second account (§2.3), who accepts it and gets its songs.
        assertTrue(Capability.SHARED_PLAYLISTS in kind.capabilities, "$kind")
        val friendCreds = SubsonicCredentials.fromPassword(System.getenv("COSINE_FRIEND_USER"), System.getenv("COSINE_FRIEND_PASS"))
        val friend = CosineClient(found.baseUrl, friendCreds, http)
        assertEquals(listOf(friendCreds.username), cosine.accounts())
        cosine.share(offline.playlist.id, friendCreds.username)
        val invited = friend.sync(0)
        assertEquals(offline.playlist.id, invited.invites.single().id)
        assertEquals(2, friend.accept(offline.playlist.id))
        val theirs = friend.sync(0)
        assertEquals(false, theirs.playlists.single { it.id == offline.playlist.id }.mine)
        assertEquals(2, theirs.tracks.size, "the shared playlist's songs are in their library")
        mirror.apply(cosine.sync(mirror.cursor, mirror.smartTag))
        assertEquals(listOf(friendCreds.username), mirror.library.value.playlist(offline.playlist.id)!!.playlist.sharedWith)
        friend.playlistOps(listOf(PlaylistOps.on(offline.playlist.id, "friend-1", PlaylistOps.DELETE)))
        assertTrue(friend.sync(0).playlists.none { it.id == offline.playlist.id }, "left")
        assertTrue(sub.playlists().any { it.id == offline.playlist.id }, "still the owner's")

        // Scrobbling is the server's (§4A); nothing connected yet.
        assertTrue(Capability.SCROBBLING in kind.capabilities, "$kind")
        val scrobbling = cosine.scrobbling()
        assertTrue(scrobbling.connected.isEmpty() && !scrobbling.lastfmAvailable, "$scrobbling")

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

        // What was just added reaches the mirror as a change, not a full copy.
        val delta = cosine.sync(mirror.cursor, mirror.smartTag)
        assertTrue(!delta.full && delta.tracks.any { it.id == status.trackId }, "$delta")
        mirror.apply(delta)
        assertEquals(fetched, mirror.library.value.track(status.trackId!!))

        // Removing it (§4.1): gone from browsing, and from the mirror as a change.
        assertTrue(Capability.REMOVE in kind.capabilities, "$kind")
        assertEquals(1, cosine.remove(listOf(status.trackId!!)))
        assertTrue(sub.search("fetched").tracks.none { it.id == status.trackId }, "removed, but still offered")
        val gone = cosine.sync(mirror.cursor, mirror.smartTag)
        assertEquals(listOf(status.trackId), gone.removedTracks)
        mirror.apply(gone)
        assertEquals(null, mirror.library.value.track(status.trackId!!))
    }
}
