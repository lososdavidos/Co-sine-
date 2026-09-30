package app.sine.core

import app.sine.core.subsonic.AlbumListType
import app.sine.core.subsonic.SubsonicClient
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.core.subsonic.SubsonicException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubsonicClientTest {
    private val server = MockWebServer()
    private lateinit var client: SubsonicClient

    @BeforeTest
    fun setUp() {
        server.start()
        client = SubsonicClient(
            server.url("/navidrome/").toString(),
            SubsonicCredentials("alice", "tok", "salt"),
            OkHttpClient(),
        )
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun respond(inner: String) = server.enqueue(
        MockResponse.Builder()
            .body("""{"subsonic-response":{"status":"ok","version":"1.16.1",$inner}}""")
            .build()
    )

    @Test
    fun `token auth from the Subsonic documentation`() {
        val c = SubsonicCredentials.fromPassword("admin", "sesame", "c19b2d")
        assertEquals("26719a1196d2a940705a59634eb18eab", c.token)
    }

    @Test
    fun `requests carry token auth and json format under the base path`() = runTest {
        respond(""""artists":{"index":[]}""")
        client.artists()
        val url = server.takeRequest().url
        assertEquals("/navidrome/rest/getArtists.view", url.encodedPath)
        assertEquals("alice", url.queryParameter("u"))
        assertEquals("tok", url.queryParameter("t"))
        assertEquals("salt", url.queryParameter("s"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("sine", url.queryParameter("c"))
    }

    @Test
    fun `artists are flattened across indexes`() = runTest {
        respond(
            """"artists":{"index":[
              {"name":"B","artist":[{"id":"a1","name":"barnacle boi","albumCount":3}]},
              {"name":"S","artist":[{"id":"a2","name":"Skeler","albumCount":7,"coverArt":"ar-a2"}]}
            ]}"""
        )
        val artists = client.artists()
        assertEquals(listOf("barnacle boi", "Skeler"), artists.map { it.name })
        assertEquals("ar-a2", artists[1].coverArt)
    }

    @Test
    fun `album carries its tracks and ignores unknown fields`() = runTest {
        respond(
            """"album":{"id":"al1","name":"Tides","artist":"Deadcrow","artistId":"a3","songCount":2,
              "someOpenSubsonicField":{"x":1},
              "song":[
                {"id":"t1","title":"One","track":1,"duration":200,"suffix":"opus","size":3000000,"artist":"Deadcrow","album":"Tides","albumId":"al1"},
                {"id":"t2","title":"Two","track":2,"duration":180,"suffix":"mp3","artist":"Deadcrow","album":"Tides","albumId":"al1"}
              ]}"""
        )
        val detail = client.album("al1")
        assertEquals("Tides", detail.album.name)
        assertEquals(listOf("t1", "t2"), detail.tracks.map { it.id })
        assertEquals("opus", detail.tracks[0].suffix)
        assertEquals(3_000_000L, detail.tracks[0].sizeBytes)
        assertEquals("al1", server.takeRequest().url.queryParameter("id"))
    }

    @Test
    fun `album list passes type and paging`() = runTest {
        respond(""""albumList2":{"album":[{"id":"x","name":"X","artist":"Y"}]}""")
        val albums = client.albums(AlbumListType.NEWEST, size = 50, offset = 100)
        assertEquals("X", albums.single().name)
        val url = server.takeRequest().url
        assertEquals("newest", url.queryParameter("type"))
        assertEquals("50", url.queryParameter("size"))
        assertEquals("100", url.queryParameter("offset"))
    }

    @Test
    fun `playlist entries become tracks`() = runTest {
        respond(""""playlist":{"id":"p1","name":"Night","songCount":1,"entry":[{"id":"t9","title":"Nine","artist":"plenka","album":"Nine"}]}""")
        val p = client.playlist("p1")
        assertEquals("Night", p.playlist.name)
        assertEquals("plenka", p.tracks.single().artist)
    }

    @Test
    fun `failed status becomes SubsonicException`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
                .build()
        )
        val e = assertFailsWith<SubsonicException> { client.ping() }
        assertEquals(40, e.code)
        assertTrue(e.isAuthFailure)
    }

    @Test
    fun `stream never asks the server to transcode`() {
        val url = client.streamUrl("t1").toHttpUrl()
        assertEquals("raw", url.queryParameter("format"))
        assertEquals(null, url.queryParameter("f"))
        assertTrue(url.encodedPath.endsWith("/rest/stream.view"))
    }

    @Test
    fun `scrobble sends the real play time`() = runTest {
        respond(""""x":1""")
        client.scrobble("t1", 1_700_000_000_000)
        val url = server.takeRequest().url
        assertEquals("1700000000000", url.queryParameter("time"))
        assertEquals("true", url.queryParameter("submission"))
    }

    @Test
    fun `single track lookup`() = runTest {
        respond(""""song":{"id":"t5","title":"Five","artist":"Skeler","album":"Five","suffix":"flac"}""")
        assertEquals("flac", client.track("t5").suffix)
        assertEquals("/navidrome/rest/getSong.view", server.takeRequest().url.encodedPath)
    }
}
