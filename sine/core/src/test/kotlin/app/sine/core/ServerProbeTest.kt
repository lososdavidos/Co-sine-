package app.sine.core

import app.sine.core.server.ProbeResult
import app.sine.core.server.ServerKind
import app.sine.core.server.ServerProbe
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerProbeTest {
    private val server = MockWebServer()
    private val probe = ServerProbe(OkHttpClient())

    @AfterTest
    fun tearDown() = server.close()

    private fun routes(f: (String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = f(request.url.encodedPath)
        }
        server.start()
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test
    fun `navidrome is identified as compatibility mode`() = runTest {
        routes { path ->
            if (path == "/rest/ping.view") MockResponse.Builder().body(
                """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.58","openSubsonic":true,"error":{"code":10,"message":"missing"}}}"""
            ).build()
            else MockResponse.Builder().code(404).build()
        }
        val found = assertIs<ProbeResult.Found>(probe.probe(base()))
        val kind = assertIs<ServerKind.Subsonic>(found.kind)
        assertEquals("navidrome", kind.serverType)
        assertEquals("Subsonic server · compatibility mode", kind.label)
        assertEquals(emptySet(), kind.capabilities)
    }

    @Test
    fun `cosine is identified with its capabilities`() = runTest {
        routes { path ->
            if (path == "/cosine/v1/capabilities") MockResponse.Builder().body(
                """{"version":"0.1.0","capabilities":["ingest","delta-sync"]}"""
            ).build()
            else MockResponse.Builder().code(404).build()
        }
        val found = assertIs<ProbeResult.Found>(probe.probe(base()))
        val kind = assertIs<ServerKind.Cosine>(found.kind)
        assertEquals(setOf("ingest", "delta-sync"), kind.capabilities)
        assertEquals("Cosine · full features", kind.label)
    }

    @Test
    fun `a web server that is neither is reported as such`() = runTest {
        routes { MockResponse.Builder().code(200).body("<html>hello</html>").build() }
        assertIs<ProbeResult.NotAServer>(probe.probe(base()))
    }

    @Test
    fun `an address without scheme falls back to http`() = runTest {
        routes { path ->
            if (path == "/rest/ping.view") MockResponse.Builder().body(
                """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
            ).build()
            else MockResponse.Builder().code(404).build()
        }
        val hostPort = "${server.hostName}:${server.port}"
        val found = assertIs<ProbeResult.Found>(probe.probe(hostPort))
        assertEquals("http://$hostPort", found.baseUrl)
    }

    @Test
    fun `nothing listening is unreachable`() = runTest {
        server.start()
        val dead = base()
        server.close()
        assertIs<ProbeResult.Unreachable>(probe.probe(dead))
    }
}
