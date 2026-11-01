package app.sine.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.sine.core.cosine.CosineException
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * Uploads files picked on the phone to Cosine (§4), one at a time, saying
 * how it goes in the shelf. Each is filed like anything else, into this
 * account's library. It carries on while you move about the app.
 */
fun startUpload(context: Context, session: AccountSession, uris: List<Uri>) {
    val cosine = session.cosine ?: return
    if (uris.isEmpty()) return
    val app = context.applicationContext
    app.graph.scope.launch {
        var filed = 0
        var had = 0
        var refused = 0
        for ((i, uri) in uris.withIndex()) {
            val name = displayName(app, uri) ?: "upload"
            say(if (uris.size == 1) "Uploading $name…" else "Uploading ${i + 1} of ${uris.size}: $name…", Signal.Server)
            try {
                val up = cosine.upload(name, ContentBody(app, uri))
                if (up.duplicate) had++ else filed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: CosineException) {
                refused++
                if (uris.size == 1) {
                    say(e.message ?: "Cosine refused $name.", Signal.Fault)
                    return@launch
                }
            } catch (e: IOException) {
                say("The upload stopped: Cosine couldn't be reached.", Signal.Server)
                return@launch
            }
        }
        session.syncSoon()
        say(
            listOfNotNull(
                filed.takeIf { it > 0 }?.let { if (it == 1) "Uploaded, and in your library." else "$it songs uploaded, and in your library." },
                had.takeIf { it > 0 }?.let { if (it == 1) "1 was there already." else "$it were there already." },
                refused.takeIf { it > 0 }?.let { if (it == 1) "1 wasn't music Cosine can keep." else "$it weren't music Cosine can keep." },
            ).joinToString(" "),
            if (refused > 0 && filed == 0) Signal.Fault else Signal.None,
        )
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment

/** A picked file's bytes, read afresh each time they're asked for, so a retried request sends them whole. */
private class ContentBody(private val context: Context, private val uri: Uri) : RequestBody() {
    override fun contentType(): MediaType? = context.contentResolver.getType(uri)?.toMediaTypeOrNull()

    override fun contentLength(): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read the file.")
        input.source().use { sink.writeAll(it) }
    }
}
