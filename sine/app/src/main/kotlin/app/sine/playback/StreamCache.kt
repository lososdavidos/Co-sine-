package app.sine.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.TreeSet
import kotlin.coroutines.coroutineContext

/**
 * Keeping what you stream (the cache tier, §2.5): songs streamed are kept
 * on the phone, up to a size set in Settings, the least recently played
 * going first. One kept whole plays again without the network, and doesn't
 * download twice. Downloads you pin are separate, and never go on their own.
 * Each song is kept under its own id, so a changed stream address still finds it.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    private var cache: SimpleCache? = null
    private val evictor = Evictor()
    private var cached: CacheDataSource.Factory? = null

    fun init(context: Context, maxBytes: Long) {
        if (cache != null) return
        evictor.max = maxBytes
        cache = SimpleCache(File(context.cacheDir, "streams"), evictor, StandaloneDatabaseProvider(context))
    }

    /** A new size: what's over it goes at once, least recently played first. */
    fun setMax(bytes: Long) {
        evictor.max = bytes
        cache?.let { evictor.trim(it) }
    }

    /** Whether a song is kept whole, so it plays offline; [key] is its [cacheKey]. */
    fun has(key: String): Boolean {
        val c = cache ?: return false
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && c.isCached(key, 0, length)
    }

    /** How much can be kept, in bytes; 0 keeps nothing. */
    val max: Long get() = evictor.max

    /**
     * Keeps a song ahead of playing it (the prefetch, §2.5), through the
     * same cache the player streams through, under its [cacheKey]. Stops between
     * pieces as soon as [keepGoing] says so, or the caller is cancelled,
     * and what it got so far stays. False when it didn't get all of it.
     */
    suspend fun prefetch(uri: Uri, key: String, keepGoing: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        val factory = cached ?: return@withContext false
        val spec = DataSpec.Builder().setUri(uri).setKey(key).build()
        lateinit var writer: CacheWriter
        writer = CacheWriter(factory.createDataSource(), spec, null) { _, _, _ -> if (!keepGoing()) writer.cancel() }
        val stop = coroutineContext[Job]?.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
            true
        } catch (e: IOException) {
            false
        } finally {
            stop?.dispose()
        }
    }

    /** How much is kept, in bytes. */
    fun size(): Long = cache?.cacheSpace ?: 0

    fun clear() {
        val c = cache ?: return
        for (key in c.keys.toList()) c.removeResource(key)
    }

    /**
     * Streams through the cache, under the song's [cacheKey]; anything else (a
     * download on the phone) straight from [upstream], as there's nothing
     * to keep.
     */
    fun dataSource(upstream: DataSource.Factory): DataSource.Factory {
        val c = cache ?: return upstream
        val cached = CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        this.cached = cached
        return DataSource.Factory { BySchemeDataSource(cached.createDataSource(), upstream.createDataSource()) }
    }

    /** http(s) through the cache, everything else straight. */
    private class BySchemeDataSource(private val remote: DataSource, private val local: DataSource) : DataSource {
        private var open: DataSource? = null
        override fun addTransferListener(listener: TransferListener) {
            remote.addTransferListener(listener)
            local.addTransferListener(listener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            val to = if (scheme == "http" || scheme == "https") remote else local
            open = to
            return to.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = open?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = open?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = open?.responseHeaders ?: emptyMap()
        override fun close() {
            open?.close()
            open = null
        }
    }

    /** Least recently used first, to a size that can change. */
    private class Evictor : CacheEvictor {
        @Volatile var max: Long = 0
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var size = 0L

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
        }
        @Synchronized override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            spans.add(span)
            size += span.length
            evict(cache, 0)
        }
        @Synchronized override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            spans.remove(span)
            size -= span.length
        }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }
        fun trim(cache: Cache) = evict(cache, 0)

        @Synchronized private fun evict(cache: Cache, adding: Long) {
            while (size + adding > max && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }
}
