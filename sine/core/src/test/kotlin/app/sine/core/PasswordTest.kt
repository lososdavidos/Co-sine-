package app.sine.core

import app.sine.core.account.Account
import app.sine.core.server.ServerKind
import app.sine.core.session.AccountSession
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.core.subsonic.SubsonicException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A server with one account whose password can change, as Cosine and Navidrome do. */
class PasswordTest {
    private var password = "old"
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val md5 = MessageDigest.getInstance("MD5").digest((password + url.queryParameter("s")).toByteArray()).joinToString("") { "%02x".format(it) }
                if (url.queryParameter("t") != md5) return envelope(""""status":"failed","error":{"code":40,"message":"Wrong username or password."}""")
                if (url.encodedPath.endsWith("changePassword.view")) {
                    password = String(url.queryParameter("password")!!.removePrefix("enc:").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                }
                return envelope(""""status":"ok"""")
            }
        }
        start()
    }
    private val dir = Files.createTempDirectory("sine-password").toFile()

    private fun envelope(inner: String) = MockResponse.Builder().body("""{"subsonic-response":{$inner,"version":"1.16.1"}}""").build()

    private fun session(): AccountSession {
        val account = Account.create(server.url("/").toString(), SubsonicCredentials.fromPassword("me", "old"), ServerKind.Subsonic("1.16.1"), 1L)
        return AccountSession(dir, account, OkHttpClient(), connected = { true }, homeDevice = "phone")
    }

    @AfterTest
    fun done() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `changing the password keeps the account signed in`() = runTest {
        val s = session()
        val before = s.account
        val after = s.changePassword("correct horse")
        assertEquals("correct horse", password)
        assertNotEquals(before.token, after.token)
        assertEquals(after, s.account)
        // The client signs in with the new one at once.
        s.client.ping()
        assertFalse(s.refused.value)
    }

    @Test
    fun `a password changed elsewhere is noticed, and signing in again mends it`() = runTest {
        val s = session()
        password = "changed elsewhere"
        assertFailsWith<SubsonicException> { s.client.ping() }
        assertTrue(s.refused.value)

        assertFailsWith<SubsonicException> { s.signInAgain("a guess") }
        assertTrue(s.refused.value)

        s.signInAgain("changed elsewhere")
        assertFalse(s.refused.value)
        s.client.ping()
    }
}
