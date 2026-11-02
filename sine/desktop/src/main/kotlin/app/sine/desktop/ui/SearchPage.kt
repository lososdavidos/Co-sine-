package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sine.core.model.SearchHit
import app.sine.core.model.SearchRanking
import app.sine.core.session.AccountSession
import app.sine.core.text.Voice
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import kotlinx.coroutines.delay

/**
 * Search (§6.8): the mirror's answer at once on Cosine, else the server's
 * after a pause in typing. Artists, albums and playlists first, then the
 * songs, which select and play like any list.
 */
@Composable
fun SearchPage(session: AccountSession, query: String) {
    val nav = LocalNav.current
    val q = query.trim()
    val load = rememberLoad(session, q) {
        if (q.isEmpty()) return@rememberLoad emptyList<SearchHit>()
        val results = session.searchMirror(q) ?: run {
            delay(250)
            session.fetch(offline = { it.search(q) }, remote = { it.search(q, 40) }).value
        }
        SearchRanking.rank(q, results, session.searchPlaylists(q))
    }
    val hits = (load as? Load.Done)?.value.orEmpty()
    val songs = remember(hits) { hits.filterIsInstance<SearchHit.OfTrack>().map { it.track } }
    val others = remember(hits) { hits.filterNot { it is SearchHit.OfTrack } }
    SongTable(session, songs, Modifier.fillMaxSize()) {
        item {
            Column {
                PageTitle(if (q.isEmpty()) "Search" else q, label = if (q.isEmpty()) null else "Search")
                when {
                    q.isEmpty() -> Text("Type in the field at the top of the sidebar. Ctrl+K takes you there from anywhere.", color = PhaseColor.Text2, style = PhaseType.Body)
                    load !is Load.Done -> Loaded(load) {}
                    hits.isEmpty() -> Text(Voice.NO_SEARCH_RESULTS, color = PhaseColor.Text2, style = PhaseType.Body)
                }
                for (hit in others.take(12)) {
                    val hover = remember { MutableInteractionSource() }
                    val hovered by hover.collectIsHoveredAsState()
                    val (label, cover, round, dest) = when (hit) {
                        is SearchHit.OfArtist -> Quad("Artist", hit.artist.coverArt, true, Dest.Artist(hit.artist.id, hit.artist.name))
                        is SearchHit.OfAlbum -> Quad("Album", hit.album.coverArt, false, Dest.Album(hit.album.id, hit.album.name))
                        is SearchHit.OfPlaylist -> Quad("Playlist", hit.playlist.coverArt, false, Dest.Playlist(hit.playlist.id, hit.playlist.name))
                        is SearchHit.OfTrack -> continue
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(PhaseShape.Square)
                            .background(if (hovered) PhaseColor.Raised else PhaseColor.Ground)
                            .hoverable(hover).clickable { nav.go(dest) }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        androidx.compose.foundation.layout.Box(Modifier.padding(start = 46.dp)) { Cover(session, cover, 44.dp, round = round, px = 150) }
                        Column(Modifier.weight(1f)) {
                            Text(hit.name, color = PhaseColor.Text1, style = PhaseType.Name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (hit is SearchHit.OfAlbum) Text(hit.album.artist, color = PhaseColor.Text2, style = PhaseType.Small)
                        }
                        PhaseLabel(label)
                    }
                }
                if (songs.isNotEmpty()) PhaseLabel("Songs", Modifier.padding(top = 20.dp, bottom = 6.dp))
            }
        }
    }
}

private data class Quad(val label: String, val cover: String?, val round: Boolean, val dest: Dest)
