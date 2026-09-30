package app.sine.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.sine.core.download.DownloadLayout
import app.sine.core.download.DownloadPlanner
import app.sine.core.download.DownloadedTrack
import app.sine.core.model.Track
import app.sine.data.AccountSession
import app.sine.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads one unit (track, album, artist or playlist) into the visible folder.
 * Files are written byte-for-byte as the server stores them — no transcoding (NG7).
 * Each file is written as `.part` and renamed when complete, so an interrupted
 * download never looks finished.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val session = inputData.getString(KEY_ACCOUNT)?.let { graph.sessionById(it) } ?: return Result.failure()
        val node = Downloads.parseNode(inputData.getString(KEY_TYPE), inputData.getString(KEY_ID))
            ?: return Result.failure()
        val treeUri = graph.settings.state.value.downloadTreeUri ?: return Result.failure()
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri.toUri()) ?: return Result.failure()
        if (!root.canWrite()) return Result.failure()

        val tracks = try {
            DownloadPlanner(session.client, session.downloads).missing(node)
        } catch (e: IOException) {
            return Result.retry()
        }
        if (tracks.isEmpty()) return Result.success()

        ensureNoMedia(root)
        var failures = 0
        tracks.forEachIndexed { i, track ->
            setForeground(foregroundInfo(i, tracks.size, track.title))
            try {
                download(session, root, track)
            } catch (e: IOException) {
                failures++
            }
        }
        return when {
            failures == 0 -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    /** Stops every other media app, gallery and car system indexing the downloads (§2.5). */
    private fun ensureNoMedia(root: DocumentFile) {
        if (root.findFile(DownloadLayout.NOMEDIA) == null) {
            root.createFile("application/octet-stream", DownloadLayout.NOMEDIA)
        }
    }

    private suspend fun download(session: AccountSession, root: DocumentFile, track: Track) =
        withContext(Dispatchers.IO) {
            val segments = DownloadLayout.segments(session.account.displayName, track)
            var dir = root
            for (name in segments.dropLast(1)) {
                dir = dir.findFile(name)?.takeIf { it.isDirectory }
                    ?: dir.createDirectory(name)
                    ?: throw IOException("Cannot create folder $name")
            }
            val fileName = segments.last()
            dir.findFile(fileName)?.delete()
            dir.findFile("$fileName.part")?.delete()
            val part = dir.createFile("application/octet-stream", "$fileName.part")
                ?: throw IOException("Cannot create $fileName")

            val request = Request.Builder().url(session.client.downloadUrl(track.id)).build()
            val bytes = try {
                session.http.newCall(request).execute().use { response ->
                    val type = response.header("Content-Type").orEmpty()
                    if (!response.isSuccessful || type.startsWith("application/json") || type.startsWith("text/xml")) {
                        throw IOException("Server refused ${track.title}: HTTP ${response.code}")
                    }
                    val out = applicationContext.contentResolver.openOutputStream(part.uri)
                        ?: throw IOException("Cannot write $fileName")
                    out.use { response.body.byteStream().copyTo(it) }
                }
            } catch (e: IOException) {
                part.delete()
                throw e
            }
            if (!part.renameTo(fileName)) {
                part.delete()
                throw IOException("Cannot finish $fileName")
            }

            val cover = track.coverArt?.let { fetchCover(session, it) }
            session.downloads.put(
                DownloadedTrack(
                    track = track,
                    location = part.uri.toString(),
                    sizeBytes = bytes,
                    downloadedAt = System.currentTimeMillis(),
                    coverFile = cover?.path,
                )
            )
        }

    /** Covers go in app-private storage: the visible folder holds only what the user asked for. */
    private fun fetchCover(session: AccountSession, coverArt: String): File? {
        val file = session.coverFile(coverArt)
        if (file.exists()) return file
        return runCatching {
            val request = Request.Builder().url(session.client.coverArtUrl(coverArt, 600)).build()
            session.http.newCall(request).execute().use { r ->
                if (!r.isSuccessful || r.header("Content-Type").orEmpty().startsWith("application/json")) return null
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                tmp.renameTo(file)
                file
            }
        }.getOrNull()
    }

    private fun foregroundInfo(done: Int, total: Int, title: String): ForegroundInfo {
        val nm = NotificationManagerCompat.from(applicationContext)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Downloads")
                .build()
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${done + 1} of $total")
            .setContentText(title)
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_TYPE = "type"
        const val KEY_ID = "id"
        private const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 3
    }
}
