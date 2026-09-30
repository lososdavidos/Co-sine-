package app.sine.core

import app.sine.core.account.Account
import app.sine.core.account.AccountStore
import app.sine.core.account.SettingsStore
import app.sine.core.download.DownloadIndex
import app.sine.core.download.DownloadLayout
import app.sine.core.download.DownloadedTrack
import app.sine.core.download.OfflineLibrary
import app.sine.core.download.PinnedBudget
import app.sine.core.model.Track
import app.sine.core.play.PlayEvent
import app.sine.core.play.PlayLog
import app.sine.core.play.PlaySource
import app.sine.core.server.ServerKind
import app.sine.core.subsonic.SubsonicCredentials
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalStateTest {
    private val dir: File = Files.createTempDirectory("sine").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun track(id: String, artist: String, album: String, n: Int? = null, albumId: String? = null) =
        Track(id = id, title = "T$id", artist = artist, album = album, trackNumber = n, albumId = albumId,
            durationSec = 100, suffix = "opus", sizeBytes = 1000)

    private fun account(url: String, kind: ServerKind, at: Long) =
        Account.create(url, SubsonicCredentials("u", "t", "s"), kind, at)

    @Test
    fun `accounts persist, switch explicitly and survive reload`() {
        val file = File(dir, "accounts.json")
        val store = AccountStore(file)
        val nav = account("http://nas:4533", ServerKind.Subsonic("1.16.1", "navidrome"), 1)
        val cos = account("http://p450:8080", ServerKind.Cosine("0.1", setOf("ingest")), 2)
        store.add(nav)
        store.add(cos)
        assertEquals(cos.id, store.state.value.active?.id)
        store.setActive(nav.id)

        val reloaded = AccountStore(file).state.value
        assertEquals(nav.id, reloaded.active?.id)
        assertEquals(2, reloaded.accounts.size)
        assertIs<ServerKind.Cosine>(reloaded.accounts[1].kind)
    }

    @Test
    fun `share target is the newest Cosine account, never a compat one`() {
        val store = AccountStore(File(dir, "a.json"))
        store.add(account("http://c1", ServerKind.Cosine("0.1", emptySet()), 1))
        val newer = account("http://c2", ServerKind.Cosine("0.1", emptySet()), 5)
        store.add(newer)
        store.add(account("http://nav", ServerKind.Subsonic("1.16.1"), 9))
        assertEquals(newer.id, store.state.value.shareTarget?.id)

        val compatOnly = AccountStore(File(dir, "b.json"))
        compatOnly.add(account("http://nav", ServerKind.Subsonic("1.16.1"), 1))
        assertNull(compatOnly.state.value.shareTarget)
    }

    @Test
    fun `removing the active account activates another`() {
        val store = AccountStore(File(dir, "a.json"))
        val a = account("http://a", ServerKind.Subsonic("1"), 1)
        val b = account("http://b", ServerKind.Subsonic("1"), 2)
        store.add(a); store.add(b)
        store.remove(b.id)
        assertEquals(a.id, store.state.value.active?.id)
    }

    @Test
    fun `download index persists and totals`() {
        val file = File(dir, "dl.json")
        val index = DownloadIndex(file)
        index.put(DownloadedTrack(track("1", "A", "X"), "content://1", 500, 0))
        index.put(DownloadedTrack(track("2", "A", "X"), "content://2", 700, 0))
        index.remove(listOf("1"))
        val reloaded = DownloadIndex(file)
        assertEquals(setOf("2"), reloaded.tracks.value.keys)
        assertEquals(700, reloaded.totalBytes)
    }

    @Test
    fun `corrupt state files read as empty rather than crashing`() {
        val file = File(dir, "dl.json").apply { writeText("{not json") }
        assertTrue(DownloadIndex(file).tracks.value.isEmpty())
    }

    @Test
    fun `layout mirrors Artist Album Track and is filesystem safe`() {
        val t = Track(id = "9", title = "What? / Why: VIP", artist = "AC/DC", album = "Live.", trackNumber = 3, suffix = "m4a")
        assertEquals(listOf("u @ nas", "AC_DC", "Live", "03 What_ _ Why_ VIP.m4a"), DownloadLayout.segments("u @ nas", t))
    }

    @Test
    fun `a single with no album is filed under its own title`() {
        val t = Track(id = "9", title = "loner", artist = "rxrrim", album = "", suffix = "opus")
        assertEquals("loner", DownloadLayout.segments("x", t)[2])
    }

    @Test
    fun `pinned budget warns, never blocks`() {
        assertEquals(PinnedBudget.Check.Within, PinnedBudget.check(100, 100, null))
        assertEquals(PinnedBudget.Check.Within, PinnedBudget.check(100, 100, 200))
        assertEquals(PinnedBudget.Check.Over(50), PinnedBudget.check(100, 150, 200))
    }

    @Test
    fun `offline library groups downloads into artists and albums`() {
        val lib = OfflineLibrary(listOf(
            DownloadedTrack(track("2", "Skeler", "Tides", 2, albumId = "al1"), "x", 1, 0),
            DownloadedTrack(track("1", "Skeler", "Tides", 1, albumId = "al1"), "x", 1, 0),
            DownloadedTrack(track("3", "plenka", "", null), "x", 1, 0),
        ))
        assertEquals(listOf("plenka", "Skeler"), lib.artists().map { it.name })
        assertEquals(listOf("1", "2"), lib.albumTracks("al1").map { it.id })
        assertEquals(1, lib.search("t3").tracks.size)
        assertEquals(1, lib.search("tides").albums.size)
    }

    @Test
    fun `local copy always wins over the network`() {
        val d = DownloadedTrack(track("1", "A", "X"), "content://local", 1, 0)
        assertEquals(PlaySource.Local("content://local"), PlaySource.resolve(d, online = true) { "http://s" })
        assertEquals(PlaySource.Remote("http://s"), PlaySource.resolve(null, online = true) { "http://s" })
        assertEquals(PlaySource.Unavailable, PlaySource.resolve(null, online = false) { "http://s" })
    }

    @Test
    fun `play log keeps unsent plays across a failed flush`() = runTest {
        val file = File(dir, "plays.json")
        val log = PlayLog(file)
        log.record(PlayEvent("a", 1))
        log.record(PlayEvent("b", 2))
        log.record(PlayEvent("c", 3))

        val sent = mutableListOf<String>()
        val n = log.flush { if (it.trackId == "b") error("offline") else sent += it.trackId }
        assertEquals(1, n)
        assertEquals(listOf("a"), sent)
        assertEquals(listOf("b", "c"), PlayLog(file).pending().map { it.trackId })

        log.flush { sent += it.trackId }
        assertEquals(listOf("a", "b", "c"), sent)
        assertTrue(log.pending().isEmpty())
    }

    @Test
    fun `a play counts after half the track or four minutes`() {
        assertTrue(PlayLog.counts(50_000, 100_000))
        assertTrue(!PlayLog.counts(49_000, 100_000))
        assertTrue(PlayLog.counts(240_000, 3_600_000))
    }

    @Test
    fun `settings are per account`() {
        val file = File(dir, "settings.json")
        val s = SettingsStore(file)
        s.updateAccount("a") { it.copy(downloadOnMetered = true) }
        s.setDownloadTree("content://tree")
        val reloaded = SettingsStore(file).state.value
        assertTrue(reloaded.forAccount("a").downloadOnMetered)
        assertTrue(!reloaded.forAccount("b").downloadOnMetered)
        assertEquals("content://tree", reloaded.downloadTreeUri)
    }
}
