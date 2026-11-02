package app.sine.desktop.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI

/**
 * A song's bytes, as FFmpeg reads them: in order, with a jump now and then
 * (to find a format's index, or for a seek). Sine fetches them itself, so a
 * stream goes over the same connection, proxy and certificates as the rest
 * of the app.
 */
interface ByteSource : AutoCloseable {
    /** Up to [len] bytes into [into]; -1 at the end. */
    fun read(into: ByteArray, len: Int): Int
    /** Moves to [pos]. False if it can't. */
    fun seek(pos: Long): Boolean
    val position: Long
    /** The whole length, or -1 when it isn't known. */
    val size: Long

    companion object {
        /** A local file (a path or a file: URI) or an http(s) URL. */
        fun open(location: String, http: OkHttpClient): ByteSource = when {
            location.startsWith("http://") || location.startsWith("https://") -> HttpSource(http, location)
            location.startsWith("file:") -> FileSource(File(URI(location)))
            else -> FileSource(File(location))
        }
    }
}

class FileSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override fun read(into: ByteArray, len: Int): Int = raf.read(into, 0, len)
    override fun seek(pos: Long): Boolean {
        if (pos < 0 || pos > raf.length()) return false
        raf.seek(pos)
        return true
    }
    override val position: Long get() = raf.filePointer
    override val size: Long = raf.length()
    override fun close() = raf.close()
}

/**
 * A URL, read with ranged requests: a seek closes the response and asks
 * again from there (a short jump forward just reads through). A dropped
 * connection is asked again from where it stopped, twice before it fails.
 */
class HttpSource(private val http: OkHttpClient, private val url: String) : ByteSource {
    private var response: Response? = null
    private var body: InputStream? = null
    override var position = 0L
        private set
    override var size = -1L
        private set

    init {
        connect() // Fails here, at once, for a song that isn't there.
    }

    private fun connect() {
        close()
        val request = Request.Builder().url(url).header("Range", "bytes=$position-").build()
        val r = http.newCall(request).execute()
        if (!r.isSuccessful) {
            r.close()
            throw IOException("HTTP ${r.code}")
        }
        val stream = r.body.byteStream()
        when (r.code) {
            206 -> r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { size = it }
            else -> {
                // No ranges here: the whole file, from the start, read through to where it was.
                r.body.contentLength().takeIf { it >= 0 }?.let { size = it }
                var skip = position
                while (skip > 0) {
                    val n = stream.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
            }
        }
        response = r
        body = stream
    }

    override fun read(into: ByteArray, len: Int): Int {
        if (size in 0..position) return -1
        var tries = 0
        while (true) {
            try {
                val stream = body ?: run { connect(); body!! }
                val n = stream.read(into, 0, len)
                if (n > 0) position += n
                return n
            } catch (e: IOException) {
                if (++tries > 2) throw e
                body = null
            }
        }
    }

    override fun seek(pos: Long): Boolean {
        if (pos < 0 || (size >= 0 && pos > size)) return false
        if (pos == position) return true
        val stream = body
        if (stream != null && pos > position && pos - position < SKIP_THROUGH) {
            var skip = pos - position
            runCatching {
                while (skip > 0) {
                    val n = stream.skip(skip)
                    if (n <= 0) break
                    skip -= n
                }
            }
            if (skip == 0L) {
                position = pos
                return true
            }
        }
        close()
        position = pos
        return true
    }

    override fun close() {
        response?.close()
        response = null
        body = null
    }

    private companion object {
        const val SKIP_THROUGH = 256 * 1024L
    }
}
