package app.sine.desktop

import app.sine.core.download.DownloadLayout
import app.sine.core.download.DownloadedTrack
import app.sine.core.model.Track
import app.sine.core.session.AccountSession
import app.sine.core.subsonic.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** How the downloads are going: songs waiting, and the one coming in. */
data class DownloadProgress(val waiting: Int = 0, val current: String? = null, val failed: Int = 0)

/**
 * Downloads on the desktop (§2.2): each song as the server stores it, in
 * the music folder, Artist / Album / Track under a folder per account, so
 * the folder makes sense on its own. They play from there with no server
 * (G1), as they do on the phone.
 */
class Downloads(private val graph: DesktopGraph) {
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()
    private val one = Mutex()

    /** Downloads the songs not already here. */
    fun download(session: AccountSession, tracks: List<Track>) {
        val missing = tracks.distinctBy { it.id }.filterNot { it.id in session.downloads }
        if (missing.isEmpty()) return
        _progress.update { it.copy(waiting = it.waiting + missing.size) }
        graph.scope.launch {
            one.withLock {
                for (t in missing) {
                    _progress.update { it.copy(current = t.title) }
                    val ok = runCatching { fetch(session, t) }.isSuccess
                    _progress.update { it.copy(waiting = (it.waiting - 1).coerceAtLeast(0), failed = it.failed + if (ok) 0 else 1) }
                }
                _progress.update { it.copy(current = null) }
            }
        }
    }

    /** Takes songs off this computer: their files and their entries. */
    fun remove(session: AccountSession, trackIds: Collection<String>) {
        for (id in trackIds) session.downloads[id]?.location?.let { File(it).delete() }
        session.downloads.remove(trackIds)
    }

    private suspend fun fetch(session: AccountSession, track: Track) = withContext(Dispatchers.IO) {
        val account = session.account
        val segments = DownloadLayout.segments(account.username + " @ " + account.serverUrl.substringAfter("://"), track)
        val target = segments.fold(graph.music) { dir, s -> File(dir, s) }
        target.parentFile.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        graph.http.newCall(Request.Builder().url(session.client.downloadUrl(track.id)).build()).execute().use { r ->
            check(SubsonicClient.isFile(r.code, r.header("Content-Type"))) { "Server refused ${track.title}: HTTP ${r.code}" }
            part.outputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
        if (!part.renameTo(target)) {
            target.delete()
            check(part.renameTo(target))
        }
        // The cover too, so it shows with no server.
        val cover = track.coverArt?.let { art ->
            val file = session.coverFile(art)
            if (!file.exists()) runCatching {
                graph.http.newCall(Request.Builder().url(session.client.coverArtUrl(art, 600)).build()).execute().use { r ->
                    if (r.isSuccessful) { file.parentFile.mkdirs(); file.outputStream().use { r.body.byteStream().copyTo(it) } }
                }
            }
            file.takeIf { it.exists() }?.path
        }
        session.downloads.put(DownloadedTrack(track, target.path, target.length(), System.currentTimeMillis(), cover))
    }
}
