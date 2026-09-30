package app.sine.core.subsonic

import app.sine.core.model.AlbumDetail
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.ArtistDetail
import app.sine.core.model.Playlist
import app.sine.core.model.PlaylistDetail
import app.sine.core.model.SearchResults
import app.sine.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The server answered, and said no. Distinct from [IOException] (unreachable). */
class SubsonicException(val code: Int, message: String) : Exception(message) {
    val isAuthFailure get() = code == 40 || code == 41 || code == 44
}

enum class AlbumListType(val wire: String) {
    NEWEST("newest"),
    ALPHABETICAL_BY_NAME("alphabeticalByName"),
    ALPHABETICAL_BY_ARTIST("alphabeticalByArtist"),
    RECENT("recent"),
    FREQUENT("frequent"),
    RANDOM("random"),
    STARRED("starred"),
}

/**
 * A well-mannered Subsonic client (compat mode, §2.1). Cosine serves the same
 * API (§5.4), so this client works against both.
 *
 * Streams request `format=raw`: Sine never asks a server to transcode (NG7).
 */
class SubsonicClient(
    baseUrl: String,
    private val credentials: SubsonicCredentials,
    private val http: OkHttpClient,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    internal fun url(method: String, vararg params: Pair<String, Any?>): HttpUrl =
        base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment("$method.view")
            .addQueryParameter("u", credentials.username)
            .addQueryParameter("t", credentials.token)
            .addQueryParameter("s", credentials.salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .apply {
                if (method != "stream" && method != "download" && method != "getCoverArt") {
                    addQueryParameter("f", "json")
                }
                for ((k, v) in params) if (v != null) addQueryParameter(k, v.toString())
            }
            .build()

    suspend fun ping() {
        call("ping")
    }

    suspend fun artists(): List<Artist> =
        call("getArtists", "artists", ArtistsDto.serializer())
            .index.flatMap { it.artist }.map { it.toModel() }

    suspend fun artist(id: String): ArtistDetail {
        val dto = call("getArtist", "artist", ArtistDto.serializer(), "id" to id)
        return ArtistDetail(dto.toModel(), dto.album.map { it.toModel() })
    }

    suspend fun albums(type: AlbumListType, size: Int = 500, offset: Int = 0): List<Album> =
        call(
            "getAlbumList2", "albumList2", AlbumListDto.serializer(),
            "type" to type.wire, "size" to size, "offset" to offset,
        ).album.map { it.toModel() }

    suspend fun album(id: String): AlbumDetail {
        val dto = call("getAlbum", "album", AlbumDto.serializer(), "id" to id)
        return AlbumDetail(dto.toModel(), dto.song.map { it.toModel() })
    }

    suspend fun track(id: String): Track =
        call("getSong", "song", SongDto.serializer(), "id" to id).toModel()

    suspend fun playlists(): List<Playlist> =
        call("getPlaylists", "playlists", PlaylistsDto.serializer())
            .playlist.map { it.toModel() }

    suspend fun playlist(id: String): PlaylistDetail {
        val dto = call("getPlaylist", "playlist", PlaylistDto.serializer(), "id" to id)
        return PlaylistDetail(dto.toModel(), dto.entry.map { it.toModel() })
    }

    suspend fun search(query: String, limit: Int = 20): SearchResults {
        val dto = call(
            "search3", "searchResult3", SearchResult3Dto.serializer(),
            "query" to query, "artistCount" to limit, "albumCount" to limit, "songCount" to limit * 2,
        )
        return SearchResults(
            dto.artist.map { it.toModel() },
            dto.album.map { it.toModel() },
            dto.song.map { it.toModel() },
        )
    }

    /** Record a play. [timeMillis] is when it actually happened (Q70: the client's clock is trusted). */
    suspend fun scrobble(trackId: String, timeMillis: Long) {
        call("scrobble", "id" to trackId, "time" to timeMillis, "submission" to true)
    }

    fun streamUrl(trackId: String): String =
        url("stream", "id" to trackId, "format" to "raw").toString()

    /** The original file, byte for byte. Used for pinned downloads. */
    fun downloadUrl(trackId: String): String = url("download", "id" to trackId).toString()

    fun coverArtUrl(coverArtId: String, size: Int? = null): String =
        url("getCoverArt", "id" to coverArtId, "size" to size).toString()

    private suspend fun call(method: String, vararg params: Pair<String, Any?>): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(method, *params)).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful && body.isBlank()) {
                    throw IOException("HTTP ${response.code} from $method")
                }
                parseEnvelope(body)
            }
        }

    private suspend fun <T> call(
        method: String,
        key: String,
        deserializer: DeserializationStrategy<T>,
        vararg params: Pair<String, Any?>,
    ): T {
        val root = call(method, *params)
        val element = root[key] ?: throw IOException("Missing '$key' in $method response")
        return WireJson.decodeFromJsonElement(deserializer, element)
    }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "sine"

        internal val WireJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /** Returns the inner `subsonic-response` object, or throws [SubsonicException] on status=failed. */
        internal fun parseEnvelope(body: String): JsonObject {
            val root = try {
                WireJson.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw IOException("Not a Subsonic response", e)
            }
            val inner = root["subsonic-response"]?.jsonObject
                ?: throw IOException("Not a Subsonic response")
            val status = inner["status"]?.jsonPrimitive?.content
            if (status != "ok") {
                val err = inner["error"]?.let { WireJson.decodeFromJsonElement(ErrorDto.serializer(), it) }
                    ?: ErrorDto(0, "Unknown error")
                throw SubsonicException(err.code, err.message)
            }
            return inner
        }
    }
}
