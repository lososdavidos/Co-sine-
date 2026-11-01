package app.sine.core.cosine

import kotlinx.serialization.Serializable

/*
 * Listening statistics (§6.13), as Cosine reports them: one account's own
 * plays, counted by each song's own credit, listening time from the songs'
 * lengths. Times are ISO 8601, in the server's time zone.
 */

/** A span to look at: the last 30 days, this year, a past year, or all time. */
@Serializable
data class StatsWindow(
    val key: String,
    val label: String,
    /** How listening over time is divided in it: day, month or year. */
    val bucket: String = "day",
)

@Serializable
data class StatsSummary(
    val plays: Int = 0,
    val seconds: Long = 0,
    /** Distinct songs. */
    val tracks: Int = 0,
    val artists: Int = 0,
)

/** One artist, album or song in a ranking: [id] is its Subsonic id (ar-, al-, tr-). */
@Serializable
data class StatsEntry(
    val id: String,
    val name: String,
    val artist: String? = null,
    val plays: Int = 0,
    val seconds: Long = 0,
    /** Its first play ever, not just in the window. */
    val firstPlayed: String? = null,
)

/** A day, month or year of listening. */
@Serializable
data class StatsBucket(val label: String, val start: String = "", val plays: Int = 0, val seconds: Long = 0)

@Serializable
data class StatsReport(
    val window: StatsWindow,
    val summary: StatsSummary = StatsSummary(),
    val artists: List<StatsEntry> = emptyList(),
    val albums: List<StatsEntry> = emptyList(),
    val tracks: List<StatsEntry> = emptyList(),
    val overTime: List<StatsBucket> = emptyList(),
)

/** A window's report, and the windows worth offering beside it. */
@Serializable
data class Stats(val report: StatsReport, val windows: List<StatsWindow> = emptyList())

/** A year at a glance: its totals, and who and what led it. */
@Serializable
data class StatsYear(
    val year: Int,
    val summary: StatsSummary = StatsSummary(),
    val topArtist: StatsEntry? = null,
    val topTrack: StatsEntry? = null,
)
