package app.sine.core

import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.PendingShare
import app.sine.core.cosine.PendingShares
import app.sine.core.cosine.ShareText
import app.sine.core.mirror.PlaylistOps
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.core.text.Voice
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CosineClientTest {
    private val server = MockWebServer()
    private var sessionsIssued = 0
    private var validSession = ""
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    @AfterTest
    fun tearDown() = server.close()

    private fun client(): CosineClient {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += "${request.method} $path"
                if (path == "/cosine/v1/capabilities") return json("""{"version":"0.2.0","capabilities":["ingest"]}""")
                if (path == "/cosine/v1/auth") {
                    val body = request.body?.utf8() ?: ""
                    if (!body.contains("\"token\":\"tok\"")) return json("""{"error":"Wrong username or password."}""", 401)
                    validSession = "s${++sessionsIssued}"
                    return json("""{"token":"$validSession"}""")
                }
                if (request.headers["Authorization"] != "Bearer $validSession") {
                    return json("""{"error":"Session expired. Authenticate again."}""", 401)
                }
                return when (path) {
                    "/cosine/v1/lookup" -> json(
                        """{"kind":"collection","title":"Tides","items":[
                           {"title":"One","uploader":"Skeler","url":"https://sc/one","source":"soundcloud","durationSec":125,"inLibrary":true,"releaseSec":124},
                           {"title":"Two","url":"https://sc/two","newField":1,"preview":true}]}"""
                    )
                    "/cosine/v1/ingest" -> {
                        bodies += request.body?.utf8() ?: ""
                        json("""{"jobs":[{"id":7,"url":"https://sc/two","title":"https://sc/two","status":"queued"}]}""", 202)
                    }
                    "/cosine/v1/smart" -> if (request.method == "GET") json(
                        """{"playlists":[{"id":"sp-1","name":"Recently added","rule":"added","days":30,"size":100,"description":"Added in the last 30 days"}],"rules":["added"]}"""
                    ) else {
                        bodies += request.body?.utf8() ?: ""
                        json("""{"id":"sp-9","name":"Heavy","rule":"most-played","span":"30d","size":100,"description":"Most played in the last 30 days"}""", 201)
                    }
                    "/cosine/v1/smart/sp-9" -> {
                        bodies += "${request.method} " + (request.body?.utf8() ?: "")
                        json("""{"id":"sp-9","name":"Dust","rule":"forgotten","months":12,"size":100,"description":"x"}""")
                    }
                    "/cosine/v1/ingest/jobs" -> json(
                        """{"jobs":[{"id":7,"url":"u","title":"Two","status":"running","progress":0.42},
                                    {"id":6,"url":"u","title":"One","status":"known","error":"Already in your library.","trackId":"tr-1"}]}"""
                    )
                    "/cosine/v1/ingest/jobs/6/retry" -> json("""{"job":{"id":6,"url":"u","title":"One","status":"queued"}}""", 202)
                    "/cosine/v1/sync" -> {
                        bodies += request.url.query ?: ""
                        json("""{"cursor":5,"full":true,"smartTag":"t2","smart":[{"id":"sp-1","name":"Recently added","rule":"added","days":30,"tracks":["tr-1"]}],
                                 "playlists":[{"id":"pl-4","name":"Ours","owner":"bob","mine":false,"sharedWith":["alice"],"tracks":["tr-1","tr-9"]}],
                                 "invites":[{"id":"pl-5","name":"Theirs","owner":"carol","songs":3}],"removedPlaylists":[]}""")
                    }
                    "/cosine/v1/playlists/ops" -> {
                        bodies += request.body?.utf8() ?: ""
                        json("""{"results":[{"id":"o1","playlist":"pl-4"},{"id":"o2","playlist":"pl-4","skipped":"missing"}]}""")
                    }
                    "/cosine/v1/accounts" -> json("""{"accounts":["bob","carol"]}""")
                    "/cosine/v1/playlists/pl-4/share" -> {
                        bodies += request.body?.utf8() ?: ""
                        json("""{"ok":true}""")
                    }
                    "/cosine/v1/playlists/pl-4/share/bob" -> json("""{"ok":true}""")
                    "/cosine/v1/playlists/pl-5/accept" -> json("""{"added":12}""")
                    "/cosine/v1/playlists/pl-5/decline" -> json("""{"error":"That invitation isn't open any more."}""", 404)
                    "/cosine/v1/scrobbling" -> json(
                        """{"connected":[{"service":"listenbrainz","username":"alice_lb","pending":2},
                                         {"service":"lastfm","username":"AliceFM","error":"refused"}],"lastfmAvailable":true}"""
                    )
                    "/cosine/v1/scrobbling/lastfm/start" -> json("""{"url":"https://www.last.fm/api/auth/?api_key=k&token=t1","token":"t1"}""")
                    "/cosine/v1/scrobbling/lastfm/finish" -> {
                        bodies += request.body?.utf8() ?: ""
                        json("""{"username":"AliceFM"}""")
                    }
                    "/cosine/v1/scrobbling/listenbrainz" ->
                        if ((request.body?.utf8() ?: "").contains("bad")) json("""{"error":"ListenBrainz didn't accept that token."}""", 400)
                        else json("""{"username":"alice_lb"}""")
                    "/cosine/v1/scrobbling/lastfm" -> json("""{"ok":true}""")
                    else -> json("""{"error":"Only failed or refused jobs can be retried"}""", 409)
                }
            }
        }
        server.start()
        return CosineClient(server.url("/").toString(), SubsonicCredentials("alice", "tok", "salt"), OkHttpClient())
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `capabilities need no session`() = runTest {
        assertEquals(setOf("ingest"), client().capabilities())
        assertEquals(0, sessionsIssued)
    }

    @Test
    fun `authenticates once and reuses the session`() = runTest {
        val c = client()
        val lookup = c.lookup("https://sc/set")
        assertTrue(lookup.isCollection)
        assertEquals(listOf(true, false), lookup.items.map { it.inLibrary })
        assertEquals(listOf(false, true), lookup.items.map { it.preview })
        assertEquals(125, lookup.items[0].durationSec)
        assertEquals("Same length as the release", Voice.lengthNote(lookup.items[0].durationSec, lookup.items[0].releaseSec))
        assertEquals(null, Voice.lengthNote(lookup.items[1].durationSec, lookup.items[1].releaseSec))
        assertEquals("0:44 longer than the release", Voice.lengthNote(225, 181))
        assertEquals("2:31 shorter than the release", Voice.lengthNote(30, 181))
        c.submit(listOf("https://sc/two"))
        val jobs = c.jobs()
        assertEquals(1, sessionsIssued)
        assertTrue(jobs[0].isActive)
        assertTrue(jobs[1].isKnown)
        assertEquals(0.42, jobs[0].progress)
    }

    @Test
    fun `songs picked from a playlist name it, and whether to keep it`() = runTest {
        val c = client()
        c.submit(listOf("https://sc/two"))
        c.submit(listOf("https://sc/two"), from = "https://sc/set")
        c.submit(listOf("https://sc/two"), from = "https://sc/set", keepPlaylist = false)
        assertTrue("from" !in bodies[0] && "playlist" !in bodies[0], bodies[0])
        assertTrue(bodies[1].contains("\"from\":\"https://sc/set\"") && bodies[1].contains("\"playlist\":true"), bodies[1])
        assertTrue(bodies[2].contains("\"playlist\":false"), bodies[2])
    }

    @Test
    fun `smart playlists' rules`() = runTest {
        val c = client()
        val lists = c.smartPlaylists()
        assertEquals("Added in the last 30 days", lists.single().description)
        assertEquals(30, lists.single().days)
        val made = c.saveSmart(app.sine.core.model.SmartRules.starting("most-played", "Heavy").copy(span = "30d"))
        assertEquals("sp-9", made.id)
        assertTrue(bodies.last().contains("\"span\":\"30d\"") && !bodies.last().contains("months"), bodies.last())
        val changed = c.saveSmart(app.sine.core.model.SmartRules.withSetting(made.copy(name = "Dust", rule = "forgotten", span = null)))
        assertEquals(12, changed.months)
        assertTrue(bodies.last().startsWith("PUT ") && bodies.last().contains("\"months\":6"), bodies.last())
    }

    @Test
    fun `playlists come with the sync, and edits go as ops`() = runTest {
        val c = client()
        val changes = c.sync(0, "t1")
        assertEquals("after=0&smart=t1", bodies.last())
        assertEquals("t2", changes.smartTag)
        assertEquals(listOf("tr-1"), changes.smart!!.single().tracks)
        val ours = changes.playlists.single()
        assertEquals(false, ours.mine)
        assertEquals("bob", ours.owner)
        assertEquals(listOf("tr-1", "tr-9"), ours.tracks)
        assertEquals("carol", changes.invites.single().owner)

        val results = c.playlistOps(listOf(
            PlaylistOps.create("o1", "Night drive", listOf("tr-1")),
            PlaylistOps.on(PlaylistOps.localId("o1"), "o2", PlaylistOps.REMOVE).copy(track = "tr-1"),
        ))
        assertTrue(bodies.last().contains("\"op\":\"create\"") && bodies.last().contains("\"new\":\"o1\""), bodies.last())
        assertEquals("pl-4", results[0].playlist)
        assertEquals("missing", results[1].skipped)
    }

    @Test
    fun `sharing playlists`() = runTest {
        val c = client()
        assertEquals(listOf("bob", "carol"), c.accounts())
        c.share("pl-4", "bob")
        assertEquals("""{"with":"bob"}""", bodies.last())
        c.unshare("pl-4", "bob")
        assertTrue("DELETE /cosine/v1/playlists/pl-4/share/bob" in requests)
        assertEquals(12, c.accept("pl-5"))
        val e = assertFailsWith<CosineException> { c.decline("pl-5") }
        assertEquals("That invitation isn't open any more.", e.message)
    }

    @Test
    fun `connecting scrobblers`() = runTest {
        val c = client()
        val state = c.scrobbling()
        assertTrue(state.lastfmAvailable)
        assertEquals(2, state.connected.first { it.service == "listenbrainz" }.pending)
        assertEquals("refused", state.connected.first { it.service == "lastfm" }.error)
        val approval = c.startLastFm()
        assertEquals("t1", approval.token)
        assertEquals("AliceFM", c.finishLastFm(approval.token))
        assertEquals("""{"token":"t1"}""", bodies.last())
        assertEquals("alice_lb", c.connectListenBrainz("good"))
        assertEquals("ListenBrainz didn't accept that token.", assertFailsWith<CosineException> { c.connectListenBrainz("bad") }.message)
        c.disconnectScrobbler("lastfm")
        assertTrue("DELETE /cosine/v1/scrobbling/lastfm" in requests)
    }

    @Test
    fun `an expired session is renewed transparently`() = runTest {
        val c = client()
        c.jobs()
        validSession = "rotated-by-server"
        assertEquals(2, c.jobs().size)
        assertEquals(2, sessionsIssued)
    }

    @Test
    fun `server errors surface their message`() = runTest {
        val c = client()
        assertEquals("queued", c.retry(6, force = true).status)
        val e = assertFailsWith<CosineException> { c.retry(99) }
        assertEquals(409, e.status)
        assertEquals("Only failed or refused jobs can be retried", e.message)
    }

    @Test
    fun `wrong credentials are reported, not retried forever`() = runTest {
        client()
        val bad = CosineClient(server.url("/").toString(), SubsonicCredentials("alice", "nope", "salt"), OkHttpClient())
        val e = assertFailsWith<CosineException> { bad.jobs() }
        assertEquals(401, e.status)
        assertEquals("Wrong username or password.", e.message)
    }
}

class SharesTest {
    @Test
    fun `finds the link in share text`() {
        assertEquals("https://on.soundcloud.com/AbC12",
            ShareText.extractUrl("Listen to Tides by Skeler on #SoundCloud https://on.soundcloud.com/AbC12"))
        assertEquals("https://soundcloud.com/skeler/tides", ShareText.extractUrl("soundcloud.com/skeler/tides"))
        assertEquals("https://youtu.be/x", ShareText.extractUrl("(https://youtu.be/x)"))
        assertNull(ShareText.extractUrl("skeler tides"))
        assertNull(ShareText.extractUrl("v1.2"))
    }

    @Test
    fun `pending shares persist per account and survive a failed flush`() = runTest {
        val file = Files.createTempFile("pending", ".json").toFile()
        val shares = PendingShares(file)
        shares.add(PendingShare("a", "https://x/1", 1))
        shares.add(PendingShare("a", "https://x/1", 2)) // same link twice: kept once
        shares.add(PendingShare("b", "https://x/2", 3))

        assertFailsWith<IllegalStateException> { shares.flush("a") { error("offline") } }
        assertEquals(1, PendingShares(file).forAccount("a").size)

        val sent = mutableListOf<String>()
        assertEquals(1, shares.flush("a") { sent += it })
        assertEquals(listOf("https://x/1"), sent)
        assertTrue(shares.forAccount("a").isEmpty())
        assertEquals(1, shares.forAccount("b").size)
        file.delete()
    }
}

class ReviewClientTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() = server.close()

    @Test
    fun `review round trip`() = runTest {
        val bodies = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                bodies += "$path ${request.body?.utf8() ?: ""}"
                val body = when (path) {
                    "/cosine/v1/auth" -> """{"token":"t"}"""
                    "/cosine/v1/review" -> """{"total":1,"items":[{"trackId":"tr-1","why":"Guessed from the file name.",
                        "identity":{"artist":"skeler","release":"tides","title":"tides","source":"filename","tier":4,"confidence":0.2,"reviewed":false},
                        "files":[{"path":"skeler/tides/tides.mp3","source":"inbox","sizeBytes":10,"durationSec":201}]}]}"""
                    "/cosine/v1/review/tr-1/candidates" -> """{"candidates":[{"key":"rec/rel","artist":"Skeler","release":"Tides","title":"Tides",
                        "year":2019,"source":"musicbrainz","tier":1,"confidence":0.92,"ids":{"mbRecording":"rec","mbRelease":"rel"}},
                        {"key":"discogs:release/1","artist":"skeler.","release":"Tides","title":"Tides","source":"discogs","tier":2,
                        "confidence":0.9,"ids":{"discogs":"release/1"},"cover":"https://i.discogs.com/x.jpeg"}]}"""
                    else -> """{"trackId":"tr-1","why":"","identity":{"artist":"Skeler","release":"Tides","title":"Tides","reviewed":true},"files":[]}"""
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
        val c = CosineClient(server.url("/").toString(), SubsonicCredentials("a", "tok", "s"), OkHttpClient())

        val page = c.reviewQueue()
        assertEquals(1, page.total)
        assertEquals("filename", page.items[0].identity.source)
        assertEquals(201, page.items[0].files[0].durationSec)

        val cands = c.candidates("tr-1", app.sine.core.cosine.ReviewQuery(album = "Tides EP"))
        assertEquals("rec", cands[0].ids.mbRecording)
        assertEquals(0.92, cands[0].confidence)
        assertEquals("release/1", cands[1].ids.discogs)
        assertEquals("Discogs", app.sine.core.text.Voice.sourceName(cands[1].source))

        assertTrue(c.choose("tr-1", app.sine.core.cosine.ReviewQuery(), cands[0].key).identity.reviewed)
        c.correct("tr-1", app.sine.core.cosine.ManualIdentity(artist = "Skeler", title = "Tides (VIP)"))
        c.confirm("tr-1")

        assertTrue(bodies.any { it.startsWith("/cosine/v1/review/tr-1/candidates") && it.contains("\"album\":\"Tides EP\"") })
        assertTrue(bodies.any { it.startsWith("/cosine/v1/review/tr-1/choose") && it.contains("\"key\":\"rec/rel\"") })
        assertTrue(bodies.any { it.startsWith("/cosine/v1/review/tr-1/correct") && it.contains("\"title\":\"Tides (VIP)\"") })
        assertTrue(bodies.any { it.startsWith("/cosine/v1/review/tr-1/confirm") })
    }
}
