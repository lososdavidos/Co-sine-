package app.sine.core.cosine

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
)

/** What Add's single field resolved to (§6.10). */
@Serializable
data class Lookup(
    val kind: String,
    val title: String? = null,
    val backend: String? = null,
    val items: List<AddItem> = emptyList(),
) {
    val isSearch get() = kind == "search"
    val isCollection get() = kind == "collection"
}

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
@Serializable private data class IngestRequest(val urls: List<String>, val force: Boolean)
@Serializable private data class RetryRequest(val force: Boolean)
@Serializable private data class JobsResponse(val jobs: List<IngestJob>)
@Serializable private data class JobResponse(val job: IngestJob)
@Serializable private data class ErrorResponse(val error: String)
@Serializable private data class CapabilitiesResponse(val version: String, val capabilities: Set<String> = emptySet())

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

    suspend fun lookup(input: String): Lookup =
        send(post("lookup", LookupRequest.serializer(), LookupRequest(input)), Lookup.serializer())

    suspend fun submit(urls: List<String>, force: Boolean = false): List<IngestJob> =
        send(post("ingest", IngestRequest.serializer(), IngestRequest(urls, force)), JobsResponse.serializer()).jobs

    suspend fun jobs(limit: Int = 50): List<IngestJob> =
        send(get("ingest/jobs", "limit" to limit.toString()), JobsResponse.serializer()).jobs

    suspend fun retry(jobId: Long, force: Boolean = false): IngestJob =
        send(post("ingest/jobs/$jobId/retry", RetryRequest.serializer(), RetryRequest(force)), JobResponse.serializer()).job

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl =
        base.newBuilder().addPathSegments("cosine/v1/$path").apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private fun get(path: String, vararg query: Pair<String, String>) =
        Request.Builder().url(url(path, *query)).get()

    private fun <T> post(path: String, serializer: KSerializer<T>, body: T) =
        Request.Builder().url(url(path)).post(WireJson.encodeToString(serializer, body).toRequestBody(JSON))

    private suspend fun <T> send(builder: Request.Builder, serializer: KSerializer<T>, authed: Boolean = true): T =
        withContext(Dispatchers.IO) {
            if (!authed) return@withContext execute(builder.build(), serializer)
            try {
                execute(builder.header("Authorization", "Bearer ${token()}").build(), serializer)
            } catch (e: CosineException) {
                if (e.status != 401) throw e
                session = null
                execute(builder.header("Authorization", "Bearer ${token()}").build(), serializer)
            }
        }

    private fun token(): String = session ?: run {
        val body = AuthRequest(credentials.username, credentials.token, credentials.salt)
        val request = Request.Builder().url(url("auth"))
            .post(WireJson.encodeToString(AuthRequest.serializer(), body).toRequestBody(JSON)).build()
        execute(request, AuthResponse.serializer()).token.also { session = it }
    }

    private fun <T> execute(request: Request, serializer: KSerializer<T>): T =
        http.newCall(request).execute().use { response ->
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
        val JSON = "application/json".toMediaType()
        val WireJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}
