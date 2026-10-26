package app.sine.core.cosine

import app.sine.core.mirror.PlaylistOp
import app.sine.core.mirror.PlaylistOpResult
import app.sine.core.mirror.SyncChanges
import app.sine.core.server.ServerKind
import app.sine.core.subsonic.SubsonicCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cosine answered with an error. [message] is already user-facing. */
class CosineException(val status: Int, message: String) : Exception(message)

@Serializable
data class AddItem(
    val title: String,
    val uploader: String = "",
    val url: String,
    val thumbnail: String? = null,
    val source: String = "",
    val durationSec: Int = 0,
    val plays: Long = 0,
    val inLibrary: Boolean = false,
    /** Only a short preview is offered (a SoundCloud Go+ track); Cosine refuses to add it. */
    val preview: Boolean = false,
    /** The song's length as released, when this result is that song and a label's upload of it was found. */
    val releaseSec: Int = 0,
    /** "album" or "playlist" for a collection, picked to see its songs; empty for a song. */
    val kind: String = "",
    /** A collection's number of songs, when the source says. */
    val tracks: Int = 0,
    /** A collection that is what the search asked for. */
    val asked: Boolean = false,
) {
    val isCollection get() = kind.isNotEmpty()
}

/** What Add's single field resolved to (§6.10). */
@Serializable
data class Lookup(
    val kind: String,
    val title: String? = null,
    val backend: String? = null,
    val items: List<AddItem> = emptyList(),
    /** For a collection: album, playlist or channel. */
    val collection: String? = null,
    /** An album's artist, when the source says. */
    val artist: String? = null,
    /** A collection's link, named when adding songs picked from it. */
    val url: String? = null,
    /** For a search: albums and playlists found, those asked for first. */
    val collections: List<AddItem> = emptyList(),
) {
    val isSearch get() = kind == "search"
    val isCollection get() = kind == "collection"
    val isAlbum get() = collection == "album"
    val isPlaylist get() = collection == "playlist"
}

/** A smart playlist's rule (§2.4), as Cosine keeps it. Its songs come from the Subsonic API under [id]. */
@Serializable
data class SmartPlaylist(
    val id: String = "",
    val name: String,
    val rule: String,
    /** most-played: 30d, year or all. */
    val span: String? = null,
    /** forgotten: not played in this many months. */
    val months: Int? = null,
    /** added: in the last this many days. */
    val days: Int? = null,
    val size: Int = 100,
    val description: String = "",
)

/** Where an account's plays are scrobbled (§4A): a service, as whom, and whether it stopped. */
@Serializable
data class Scrobbler(
    val service: String,
    val username: String,
    /** Set when the service refused the account's token: connect again. */
    val error: String? = null,
    /** Plays not sent yet. */
    val pending: Int = 0,
)

@Serializable
data class Scrobbling(
    val connected: List<Scrobbler> = emptyList(),
    /** Last.fm can be connected: the server has its own Last.fm API account. */
    val lastfmAvailable: Boolean = false,
)

/** Connecting Last.fm: approve Cosine at [url], then finish with [token]. */
@Serializable
data class LastFmApproval(val url: String, val token: String)

@Serializable
data class IngestJob(
    val id: Long,
    val url: String,
    val title: String,
    val status: String,
    val progress: Double = 0.0,
    val error: String? = null,
    val trackId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isActive get() = status == "queued" || status == "running"
    val isFailed get() = status == "failed"
    /** Q12: refused because it's already in the library; "Add anyway" forces it. */
    val isKnown get() = status == "known"
}

@Serializable private data class AuthRequest(val username: String, val token: String, val salt: String)
@Serializable private data class AuthResponse(val token: String)
@Serializable private data class LookupRequest(val input: String)
@Serializable private data class IngestRequest(
    val urls: List<String>,
    val force: Boolean,
    val from: String? = null,
    val playlist: Boolean? = null,
)
@Serializable private data class SmartResponse(val playlists: List<SmartPlaylist> = emptyList())
@Serializable private data class RetryRequest(val force: Boolean)
@Serializable private data class JobsResponse(val jobs: List<IngestJob>)
@Serializable private data class JobResponse(val job: IngestJob)
@Serializable private data class ErrorResponse(val error: String)
@Serializable private data class CandidatesResponse(val candidates: List<Candidate> = emptyList())
@Serializable private data class ChooseRequest(val query: ReviewQuery, val key: String)
@Serializable private data class RemoveRequest(val tracks: List<String>)
@Serializable private data class RemoveResponse(val removed: Int)
@Serializable private data class CapabilitiesResponse(val version: String, val capabilities: Set<String> = emptySet())
@Serializable private data class OpsRequest(val ops: List<PlaylistOp>)
@Serializable private data class OpsResponse(val results: List<PlaylistOpResult> = emptyList())
@Serializable private data class AccountsResponse(val accounts: List<String> = emptyList())
@Serializable private data class ShareRequest(val with: String)
@Serializable private data class OkResponse(val ok: Boolean = true)
@Serializable private data class AcceptResponse(val added: Int = 0)
@Serializable private data class TokenRequest(val token: String)
@Serializable private data class UsernameResponse(val username: String = "")

/**
 * Cosine's native protocol (§2.1). Authenticates once with the Subsonic
 * token + salt Sine already holds, then uses the session token it gets back;
 * an expired session is renewed transparently, once per call.
 */
class CosineClient(
    baseUrl: String,
    private val credentials: SubsonicCredentials,
    private val http: OkHttpClient,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    @Volatile private var session: String? = null

    suspend fun capabilities(): Set<String> = describe().capabilities

    /** What this Cosine is now: its version and capabilities, which change as it is upgraded (§9.3). */
    suspend fun describe(): ServerKind.Cosine =
        send(get("capabilities"), CapabilitiesResponse.serializer(), authed = false)
            .let { ServerKind.Cosine(it.version, it.capabilities) }

    /**
     * A search in Add answers once every source has, and Cosine runs only
     * a few yt-dlp at a time: on a small server, busy fetching, that takes
     * longer than other calls are given.
     */
    private val searching = http.newBuilder().readTimeout(LOOKUP_WAIT_SECONDS, TimeUnit.SECONDS).build()

    suspend fun lookup(input: String): Lookup =
        send(post("lookup", LookupRequest.serializer(), LookupRequest(input)), Lookup.serializer(), via = searching)

    /**
     * Queues links. [from] is the album or playlist they were picked from
     * (its [Lookup.url]): an album lends them its name; a playlist becomes
     * one in the library unless [keepPlaylist] is false.
     */
    suspend fun submit(
        urls: List<String>,
        force: Boolean = false,
        from: String? = null,
        keepPlaylist: Boolean = true,
    ): List<IngestJob> {
        val body = IngestRequest(urls, force, from, if (from != null) keepPlaylist else null)
        return send(post("ingest", IngestRequest.serializer(), body), JobsResponse.serializer()).jobs
    }

    /** The account's smart playlists' rules. */
    suspend fun smartPlaylists(): List<SmartPlaylist> =
        send(get("smart"), SmartResponse.serializer()).playlists

    /** Saves a smart playlist: a new one when its [SmartPlaylist.id] is empty. */
    suspend fun saveSmart(playlist: SmartPlaylist): SmartPlaylist {
        val builder = if (playlist.id.isEmpty()) post("smart", SmartPlaylist.serializer(), playlist)
        else Request.Builder().url(url("smart/${playlist.id}"))
            .put(WireJson.encodeToString(SmartPlaylist.serializer(), playlist).toRequestBody(JSON))
        return send(builder, SmartPlaylist.serializer())
    }

    suspend fun jobs(limit: Int = 50): List<IngestJob> =
        send(get("ingest/jobs", "limit" to limit.toString()), JobsResponse.serializer()).jobs

    suspend fun retry(jobId: Long, force: Boolean = false): IngestJob =
        send(post("ingest/jobs/$jobId/retry", RetryRequest.serializer(), RetryRequest(force)), JobResponse.serializer()).job

    suspend fun reviewQueue(limit: Int = 200, offset: Int = 0): ReviewPage =
        send(get("review", "limit" to limit.toString(), "offset" to offset.toString()), ReviewPage.serializer())

    suspend fun reviewItem(trackId: String): ReviewItem =
        send(get("review/$trackId"), ReviewItem.serializer())

    suspend fun candidates(trackId: String, query: ReviewQuery = ReviewQuery()): List<Candidate> =
        send(post("review/$trackId/candidates", ReviewQuery.serializer(), query), CandidatesResponse.serializer()).candidates

    suspend fun choose(trackId: String, query: ReviewQuery, key: String): ReviewItem =
        send(post("review/$trackId/choose", ChooseRequest.serializer(), ChooseRequest(query, key)), ReviewItem.serializer())

    suspend fun correct(trackId: String, identity: ManualIdentity): ReviewItem =
        send(post("review/$trackId/correct", ManualIdentity.serializer(), identity), ReviewItem.serializer())

    /**
     * Takes tracks out of this account's library (§4.1). They stay out: the
     * Inbox won't bring them back, though adding one again on purpose will.
     * Returns how many were in the library.
     */
    suspend fun remove(trackIds: List<String>): Int =
        send(post("library/remove", RemoveRequest.serializer(), RemoveRequest(trackIds)), RemoveResponse.serializer()).removed

    /**
     * The library since [after] (0: all of it), for the mirror (§5.2), and
     * the smart playlists unless those as of [smartTag] are current.
     */
    suspend fun sync(after: Long, smartTag: String = ""): SyncChanges =
        send(get("sync", "after" to after.toString(), "smart" to smartTag), SyncChanges.serializer())

    /** Sends playlist edits made here, maybe offline, in order. Each is applied once, however often sent. */
    suspend fun playlistOps(ops: List<PlaylistOp>): List<PlaylistOpResult> =
        send(post("playlists/ops", OpsRequest.serializer(), OpsRequest(ops)), OpsResponse.serializer()).results

    /** The other accounts on this server, to share a playlist with. */
    suspend fun accounts(): List<String> = send(get("accounts"), AccountsResponse.serializer()).accounts

    suspend fun share(playlistId: String, with: String) {
        send(post("playlists/$playlistId/share", ShareRequest.serializer(), ShareRequest(with)), OkResponse.serializer())
    }

    suspend fun unshare(playlistId: String, name: String) {
        send(delete("playlists/$playlistId/share/$name"), OkResponse.serializer())
    }

    /** Takes up a shared playlist. Returns how many of its songs went into your library. */
    suspend fun accept(playlistId: String): Int =
        send(post("playlists/$playlistId/accept", OkResponse.serializer(), OkResponse()), AcceptResponse.serializer()).added

    suspend fun decline(playlistId: String) {
        send(post("playlists/$playlistId/decline", OkResponse.serializer(), OkResponse()), OkResponse.serializer())
    }

    suspend fun scrobbling(): Scrobbling = send(get("scrobbling"), Scrobbling.serializer())

    suspend fun startLastFm(): LastFmApproval =
        send(post("scrobbling/lastfm/start", OkResponse.serializer(), OkResponse()), LastFmApproval.serializer())

    /** Finishes connecting Last.fm once Cosine was approved in the browser. Returns the Last.fm username. */
    suspend fun finishLastFm(token: String): String =
        send(post("scrobbling/lastfm/finish", TokenRequest.serializer(), TokenRequest(token)), UsernameResponse.serializer()).username

    suspend fun connectListenBrainz(token: String): String =
        send(post("scrobbling/listenbrainz", TokenRequest.serializer(), TokenRequest(token)), UsernameResponse.serializer()).username

    suspend fun disconnectScrobbler(service: String) {
        send(delete("scrobbling/$service"), OkResponse.serializer())
    }

    suspend fun confirm(trackId: String): ReviewItem =
        send(Request.Builder().url(url("review/$trackId/confirm")).post(ByteArray(0).toRequestBody(JSON)), ReviewItem.serializer())

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl =
        base.newBuilder().addPathSegments("cosine/v1/$path").apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private fun get(path: String, vararg query: Pair<String, String>) =
        Request.Builder().url(url(path, *query)).get()

    private fun delete(path: String) = Request.Builder().url(url(path)).delete()

    private fun <T> post(path: String, serializer: KSerializer<T>, body: T) =
        Request.Builder().url(url(path)).post(WireJson.encodeToString(serializer, body).toRequestBody(JSON))

    private suspend fun <T> send(
        builder: Request.Builder,
        serializer: KSerializer<T>,
        authed: Boolean = true,
        via: OkHttpClient = http,
    ): T =
        withContext(Dispatchers.IO) {
            if (!authed) return@withContext execute(builder.build(), serializer, via)
            try {
                execute(builder.header("Authorization", "Bearer ${token()}").build(), serializer, via)
            } catch (e: CosineException) {
                if (e.status != 401) throw e
                session = null
                execute(builder.header("Authorization", "Bearer ${token()}").build(), serializer, via)
            }
        }

    private fun token(): String = session ?: run {
        val body = AuthRequest(credentials.username, credentials.token, credentials.salt)
        val request = Request.Builder().url(url("auth"))
            .post(WireJson.encodeToString(AuthRequest.serializer(), body).toRequestBody(JSON)).build()
        execute(request, AuthResponse.serializer()).token.also { session = it }
    }

    private fun <T> execute(request: Request, serializer: KSerializer<T>, via: OkHttpClient = http): T =
        via.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { WireJson.decodeFromString(ErrorResponse.serializer(), text).error }
                    .getOrElse { "Cosine returned HTTP ${response.code}." }
                throw CosineException(response.code, message)
            }
            try {
                WireJson.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw IOException("Unreadable response from Cosine", e)
            }
        }

    private companion object {
        const val LOOKUP_WAIT_SECONDS = 120L
        val JSON = "application/json".toMediaType()
        val WireJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
