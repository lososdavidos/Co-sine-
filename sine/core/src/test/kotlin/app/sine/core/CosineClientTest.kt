package app.sine.core

import app.sine.core.cosine.CosineClient
import app.sine.core.cosine.CosineException
import app.sine.core.cosine.PendingShare
import app.sine.core.cosine.PendingShares
import app.sine.core.cosine.ShareText
import app.sine.core.subsonic.SubsonicCredentials
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
                           {"title":"One","uploader":"Skeler","url":"https://sc/one","source":"soundcloud","durationSec":125,"inLibrary":true},
                           {"title":"Two","url":"https://sc/two","newField":1}]}"""
                    )
                    "/cosine/v1/ingest" -> json("""{"jobs":[{"id":7,"url":"https://sc/two","title":"https://sc/two","status":"queued"}]}""", 202)
                    "/cosine/v1/ingest/jobs" -> json(
                        """{"jobs":[{"id":7,"url":"u","title":"Two","status":"running","progress":0.42},
                                    {"id":6,"url":"u","title":"One","status":"known","error":"Already in your library.","trackId":"tr-1"}]}"""
                    )
                    "/cosine/v1/ingest/jobs/6/retry" -> json("""{"job":{"id":6,"url":"u","title":"One","status":"queued"}}""", 202)
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
        assertEquals(125, lookup.items[0].durationSec)
        c.submit(listOf("https://sc/two"))
        val jobs = c.jobs()
        assertEquals(1, sessionsIssued)
        assertTrue(jobs[0].isActive)
        assertTrue(jobs[1].isKnown)
        assertEquals(0.42, jobs[0].progress)
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
