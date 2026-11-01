package app.sine.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.sine.graph
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException

/**
 * Covers for the car (§5.2a): Android Auto shows artwork only from a
 * content address, never a file of ours or a server's. The cover kept on
 * the phone is served when there is one; else it's fetched once and kept,
 * so next time it's there offline too. Not exported: the car is given each
 * address as it's handed one.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read only.")
        val (account, coverArt) = uri.pathSegments.takeIf { it.size == 2 } ?.let { it[0] to it[1] } ?: throw FileNotFoundException()
        val ctx = context ?: throw FileNotFoundException()
        val session = ctx.graph.sessionById(account) ?: throw FileNotFoundException()
        val file = session.coverFile(coverArt)
        if (!file.exists()) {
            if (!session.isOnline) throw FileNotFoundException()
            fetch(session.client.coverArtUrl(coverArt, 600), file, ctx)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fetch(url: String, to: File, ctx: Context) {
        val part = File(to.parentFile, to.name + ".part")
        try {
            ctx.graph.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful) throw FileNotFoundException()
                to.parentFile?.mkdirs()
                part.outputStream().use { body.byteStream().copyTo(it) }
            }
            if (!part.renameTo(to)) throw FileNotFoundException()
        } catch (e: java.io.IOException) {
            part.delete()
            throw FileNotFoundException(e.message)
        }
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun authority(context: Context) = context.packageName + ".artwork"

        fun uri(context: Context, account: String, coverArt: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(account).appendPath(coverArt).build()
    }
}
