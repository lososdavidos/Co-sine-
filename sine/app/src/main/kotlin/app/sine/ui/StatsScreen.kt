package app.sine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.cosine.Stats
import app.sine.core.cosine.StatsBucket
import app.sine.core.cosine.StatsEntry
import app.sine.core.cosine.StatsSummary
import app.sine.core.cosine.StatsYear
import app.sine.core.text.Voice
import app.sine.data.AccountSession
import app.sine.data.Sourced
import app.sine.graph
import app.sine.ui.phase.Chip
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.RowNumber
import app.sine.ui.phase.SectionLabel
import app.sine.ui.phase.accentFill
import app.sine.ui.phase.numberColumn
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Listening (§6.13): what you played, never what to play next (NG3). A span
 * to look at, its totals, its listening over time as blocks (grey, the
 * period you're in now Sine), and who and what led it, each with the
 * first time you played it. Then every year at a glance.
 */
@Composable
fun StatsScreen(session: AccountSession, nav: Pages) {
    var window by rememberSaveable { mutableStateOf("30d") }
    val load = rememberLoad(session, window) {
        val c = session.cosine ?: throw IllegalStateException(Voice.STATS_NEED_COSINE)
        Sourced(c.stats(window) to c.statsYears(), false)
    }
    LoadContent(load) { (stats, years), _ -> StatsContent(session, stats, years, window, { window = it }, nav) }
}

/** The screen as loaded: a span's report, and the years. */
@Composable
internal fun StatsContent(session: AccountSession, stats: Stats, years: List<StatsYear>, window: String, onWindow: (String) -> Unit, nav: Pages) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                TopSpace()
                Text("Listening", color = PhaseColor.Text1, style = PhaseType.display(52.sp))
            }
        }
        item { Windows(stats, window, onWindow) }
        item { Totals(stats.report.summary) }
        item { OverTime(stats) }
        if (stats.report.summary.plays == 0) item {
            Text("Nothing played here yet.", Modifier.padding(20.dp), color = PhaseColor.Text2, style = PhaseType.Body)
        }
        ranking("Top artists", stats.report.artists, round = true, session) { nav.navigate(Routes.artist(it.id)) }
        ranking("Top albums", stats.report.albums, round = false, session) { nav.navigate(Routes.album(it.id)) }
        ranking("Top songs", stats.report.tracks, round = false, session = null, onOpen = null)
        if (years.isNotEmpty()) {
            item { SectionLabel("Year by year", end = "Newest first") }
            itemsIndexed(years, key = { _, y -> "year-${y.year}" }) { _, y ->
                YearRow(y, selected = window == y.year.toString()) { onWindow(y.year.toString()) }
            }
        }
    }
}

/** The spans worth offering, as the server lists them; the one shown is the light pill. */
@Composable
private fun Windows(stats: Stats, window: String, onChoose: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val windows = stats.windows.ifEmpty { listOf(stats.report.window) }
        for (w in windows) Chip(shortLabel(w.key, w.label), w.key == window, { onChoose(w.key) })
    }
}

private fun shortLabel(key: String, label: String) = when (key) {
    "30d" -> "30 days"
    "year" -> "This year"
    "all" -> "All time"
    else -> label
}

/** The totals, large, their names small above them. */
@Composable
private fun Totals(s: StatsSummary) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Total("Plays", count(s.plays), Modifier.weight(1f))
        Total("Listening", hours(s.seconds), Modifier.weight(1f))
        Total("Songs", count(s.tracks), Modifier.weight(1f))
    }
}

@Composable
private fun Total(name: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PhaseLabel(name)
        Text(value, color = PhaseColor.Text1, style = PhaseType.display(36.sp), maxLines = 1)
    }
}

/**
 * Listening over time, in blocks (§6.13): colour is a fill, never a line.
 * Grey, but for the period you're in now, which is Sine. A few labels under
 * them, mono; the busiest one named above.
 */
@Composable
private fun OverTime(stats: Stats) {
    val buckets = stats.report.overTime
    if (buckets.isEmpty()) return
    val unit = stats.report.window.bucket
    val most = buckets.maxBy { it.plays }
    // The window runs up to now, so its last block is the one you're in.
    val live = stats.report.window.key in setOf("30d", "year", "all")
    val top = most.plays.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            PhaseLabel("Plays by $unit", Modifier.weight(1f))
            if (most.plays > 0) PhaseLabel("Most: ${bucketName(most, unit)}, ${count(most.plays)}")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clearAndSetSemantics {
                    contentDescription = "Plays by $unit: " + buckets.joinToString(", ") { "${bucketName(it, unit)} ${it.plays}" }
                },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 20) 2.dp else 4.dp),
        ) {
            buckets.forEachIndexed { i, b ->
                val now = live && i == buckets.lastIndex
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val share = b.plays.toFloat() / top
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(share.coerceAtLeast(if (b.plays > 0) 0.03f else 0.01f))
                            .clip(PhaseShape.Square)
                            .then(if (now) Modifier.accentFill() else Modifier.background(PhaseColor.Bar)),
                    )
                }
            }
        }
        // Labels: every one when there are few, else a handful spread across.
        val step = if (buckets.size <= 12) 1 else (buckets.size + 4) / 5
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            buckets.forEachIndexed { i, b ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % step == 0 || i == buckets.lastIndex && buckets.size <= 12) {
                        Text(axisLabel(b, unit, buckets.size), color = PhaseColor.Text3, style = PhaseType.Data.copy(fontSize = 10.sp), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * A ranking: who or what, how often, and when you first played it, with a
 * block as long as its share of the first. Artists are round, as people are.
 */
private fun LazyListScope.ranking(
    title: String,
    entries: List<StatsEntry>,
    round: Boolean,
    session: AccountSession?,
    onOpen: ((StatsEntry) -> Unit)?,
) {
    if (entries.isEmpty()) return
    item(key = "title-$title") { SectionLabel(title, end = "Plays · first played") }
    val top = entries.maxOf { it.plays }.coerceAtLeast(1)
    itemsIndexed(entries, key = { _, e -> "$title-${e.id}" }) { i, e ->
        RankRow(i + 1, e, e.plays.toFloat() / top, round, session, onOpen)
    }
}

@Composable
private fun RankRow(rank: Int, e: StatsEntry, share: Float, round: Boolean, session: AccountSession?, onOpen: ((StatsEntry) -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = onOpen?.let { { it(e) } } ?: {
        // A song plays, its library copy looked up first.
        scope.launch {
            val s = context.graph.activeSession.value ?: return@launch
            val track = runCatching { s.track(e.id).value }.getOrNull()
            if (track == null) say(Voice.TRACK_UNAVAILABLE) else context.graph.player.play(s, listOf(track))
        }
        Unit
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(PhaseShape.Square)
            .clickable(onClick = open)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowNumber(rank, numberColumn(10))
        if (session != null) Art(session.artwork(session.coverOf(e.id), 150), 44.dp, round = round)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!e.artist.isNullOrBlank()) Text(e.artist!!, color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(count(e.plays), e.firstPlayed?.let(::monthYear)).joinToString(" · "),
                    color = PhaseColor.Text2, style = PhaseType.Data, maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().height(3.dp).clip(PhaseShape.Square).background(PhaseColor.Raised)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().background(PhaseColor.Bar))
            }
        }
    }
}

/** A year at a glance; tapped, it's the span shown above. */
@Composable
private fun YearRow(y: StatsYear, selected: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(PhaseShape.Square)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(y.year.toString(), Modifier.width(76.dp), color = if (selected) PhaseColor.Sine else PhaseColor.Text1, style = PhaseType.display(32.sp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${count(y.summary.plays)} plays · ${hours(y.summary.seconds)}", color = PhaseColor.Text1, style = PhaseType.Name)
            val led = listOfNotNull(y.topArtist?.name, y.topTrack?.name).joinToString(" · ")
            if (led.isNotEmpty()) Text("Most: $led", color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(2.dp))
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** Listening time: hours, or minutes under one. */
internal fun hours(seconds: Long): String = when {
    seconds >= 3600 -> "${count((seconds / 3600).toInt())} h"
    else -> "${seconds / 60} min"
}

private fun parse(iso: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(iso) }.getOrNull()

private fun monthYear(iso: String): String? = parse(iso)?.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))

/** A block's name as said: "July", "3 October", "2024". */
private fun bucketName(b: StatsBucket, unit: String): String {
    val t = parse(b.start) ?: return b.label
    return when (unit) {
        "month" -> t.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        "day" -> t.format(DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()))
        else -> t.year.toString()
    }
}

/** Under a block: a month's initial, a day's date, a year's last two digits when there are many. */
private fun axisLabel(b: StatsBucket, unit: String, n: Int): String {
    val t = parse(b.start) ?: return b.label
    return when (unit) {
        "month" -> t.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        "day" -> t.dayOfMonth.toString()
        else -> if (n > 6) "'" + (t.year % 100).toString().padStart(2, '0') else t.year.toString()
    }
}
