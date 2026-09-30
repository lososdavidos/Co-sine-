package app.sine.core.cosine

import kotlinx.serialization.Serializable

/** Identifiers in outside catalogues; empty unless a catalogue matched. */
@Serializable
data class CatalogueIds(
    val mbRecording: String? = null,
    val mbRelease: String? = null,
    val mbReleaseGroup: String? = null,
    val mbArtist: String? = null,
)

/** What a Track is, as the resolver or a person decided (§3.2). */
@Serializable
data class TrackIdentity(
    val artist: String,
    val release: String,
    val title: String,
    val trackArtist: String? = null,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val year: Int = 0,
    val source: String = "",
    val tier: Int = 0,
    val confidence: Double = 0.0,
    val ids: CatalogueIds = CatalogueIds(),
    val reviewed: Boolean = false,
)

@Serializable
data class ReviewFile(
    val path: String,
    val source: String = "",
    val sourceUrl: String? = null,
    val sizeBytes: Long = 0,
    val durationSec: Int = 0,
)

@Serializable
data class ReviewItem(
    val trackId: String,
    val identity: TrackIdentity,
    val why: String = "",
    val files: List<ReviewFile> = emptyList(),
)

@Serializable
data class ReviewPage(val total: Int, val items: List<ReviewItem> = emptyList())

/** One possible identity from a catalogue, labelled by origin (§6.12). Chosen by [key]. */
@Serializable
data class Candidate(
    val key: String,
    val artist: String,
    val release: String,
    val title: String,
    val trackArtist: String? = null,
    val trackNo: Int = 0,
    val year: Int = 0,
    val source: String = "",
    val confidence: Double = 0.0,
    val ids: CatalogueIds = CatalogueIds(),
)

/** Overrides for a candidate search; empty fields use the Track's current identity. */
@Serializable
data class ReviewQuery(val artist: String = "", val title: String = "", val album: String = "")

/** An identity typed by hand: for unreleased material, the only honest answer (§6.12). */
@Serializable
data class ManualIdentity(
    val artist: String,
    val title: String,
    val release: String = "",
    val trackNo: Int = 0,
    val year: Int = 0,
)
