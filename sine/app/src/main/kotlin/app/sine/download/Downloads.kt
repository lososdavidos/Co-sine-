package app.sine.download

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.sine.core.model.DownloadNode
import app.sine.data.AccountSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Pinned downloads: explicit, never evicted, in the folder the user chose (§2.5). */
object Downloads {
    const val TAG = "downloads"

    fun enqueue(context: Context, session: AccountSession, node: DownloadNode, allowMetered: Boolean) {
        val (type, id) = when (node) {
            is DownloadNode.OfTrack -> "track" to node.id
            is DownloadNode.OfAlbum -> "album" to node.id
            is DownloadNode.OfArtist -> "artist" to node.id
            is DownloadNode.OfPlaylist -> "playlist" to node.id
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_ACCOUNT to session.account.id,
                    DownloadWorker.KEY_TYPE to type,
                    DownloadWorker.KEY_ID to id,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("dl:${session.account.id}:$type:$id", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun remove(context: Context, session: AccountSession, trackIds: Collection<String>) =
        withContext(Dispatchers.IO) {
            for (id in trackIds) {
                val entry = session.downloads[id] ?: continue
                runCatching { DocumentFile.fromSingleUri(context, entry.location.toUri())?.delete() }
            }
            session.downloads.remove(trackIds)
        }

    suspend fun removeAll(context: Context, session: AccountSession) =
        remove(context, session, session.downloads.tracks.value.keys.toList())

    fun parseNode(type: String?, id: String?): DownloadNode? {
        if (id == null) return null
        return when (type) {
            "track" -> DownloadNode.OfTrack(id)
            "album" -> DownloadNode.OfAlbum(id)
            "artist" -> DownloadNode.OfArtist(id)
            "playlist" -> DownloadNode.OfPlaylist(id)
            else -> null
        }
    }
}
