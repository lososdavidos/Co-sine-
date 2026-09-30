package app.sine.core.server

import app.sine.core.subsonic.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * What kind of server sits at an address. The capability model (§2.1, §9.3):
 * the client asks, then adapts. "This server is Navidrome" and "this Cosine is
 * older than this Sine" are the same question.
 */
@Serializable
sealed interface ServerKind {
    val capabilities: Set<String>

    /** Human-readable, shown before login so a missing feature is never a surprise (§6.20). */
    val label: String

    @Serializable
    data class Cosine(val version: String, override val capabilities: Set<String>) : ServerKind {
        override val label get() = "Cosine · full features"
    }

    @Serializable
    data class Subsonic(val apiVersion: String, val serverType: String? = null) : ServerKind {
        override val capabilities: Set<String> get() = emptySet()
        override val label get() = "Subsonic server · compatibility mode"
    }
}

/** Native-only capabilities. In compat mode every one of these is absent and its UI hidden. */
object Capability {
    const val INGEST = "ingest"
    const val DELTA_SYNC = "delta-sync"
    const val CROSS_USER = "cross-user"
    const val REVIEW_QUEUE = "review-queue"
    const val VERSION_PINS = "version-pins"
}

sealed interface ProbeResult {
    data class Found(val baseUrl: String, val kind: ServerKind) : ProbeResult
    data class NotAServer(val reason: String) : ProbeResult
    data class Unreachable(val reason: String) : ProbeResult
}

@Serializable
internal data class CosineCapabilitiesDto(val version: String, val capabilities: Set<String> = emptySet())

class ServerProbe(private val http: OkHttpClient) {

    /**
     * Normalises what the user typed and identifies the server. An address with
     * no scheme is tried over https first, then http (plain http is normal on a
     * Tailscale or LAN address).
     */
    suspend fun probe(typed: String): ProbeResult = withContext(Dispatchers.IO) {
        val input = typed.trim().trimEnd('/')
        if (input.isEmpty()) return@withContext ProbeResult.NotAServer("Enter a server address.")
        val candidates = if (input.contains("://")) listOf(input) else listOf("https://$input", "http://$input")

        var last: ProbeResult = ProbeResult.NotAServer("Not a valid address.")
        for (candidate in candidates) {
            if (candidate.toHttpUrlOrNull() == null) continue
            last = probeOne(candidate)
            if (last is ProbeResult.Found) return@withContext last
        }
        last
    }

    private fun probeOne(base: String): ProbeResult {
        try {
            cosine(base)?.let { return ProbeResult.Found(base, it) }
            subsonic(base)?.let { return ProbeResult.Found(base, it) }
        } catch (e: IOException) {
            return ProbeResult.Unreachable(e.message ?: "No response from $base")
        }
        return ProbeResult.NotAServer("No Subsonic or Cosine server at $base.")
    }

    private fun cosine(base: String): ServerKind.Cosine? {
        val url = "$base/cosine/v1/capabilities".toHttpUrlOrNull() ?: return null
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val dto = try {
                SubsonicClient.WireJson.decodeFromString(CosineCapabilitiesDto.serializer(), r.body.string())
            } catch (e: Exception) {
                return null
            }
            return ServerKind.Cosine(dto.version, dto.capabilities)
        }
    }

    /** An unauthenticated ping still answers with version and (OpenSubsonic) server type. */
    private fun subsonic(base: String): ServerKind.Subsonic? {
        val url = "$base/rest/ping.view".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("f", "json")
            ?.addQueryParameter("v", SubsonicClient.API_VERSION)
            ?.addQueryParameter("c", SubsonicClient.CLIENT_NAME)
            ?.build() ?: return null
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body.string()
            val inner = try {
                SubsonicClient.WireJson.parseToJsonElement(body)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("subsonic-response") as? kotlinx.serialization.json.JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            val version = inner["version"]?.jsonPrimitive?.content ?: return null
            val type = inner["type"]?.jsonPrimitive?.content
            return ServerKind.Subsonic(version, type)
        }
    }
}
