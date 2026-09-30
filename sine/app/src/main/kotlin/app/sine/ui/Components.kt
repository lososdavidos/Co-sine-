package app.sine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sine.core.model.Album
import app.sine.core.model.Artist
import app.sine.core.model.Playlist
import app.sine.core.model.Track
import app.sine.core.text.Voice
import app.sine.data.Sourced
import coil3.compose.AsyncImage
import kotlin.coroutines.cancellation.CancellationException

/*
 * Deliberately unstyled: Material 3 defaults throughout. The design language is
 * being replaced, so nothing here encodes Graphit or any other visual system.
 * Structure and behaviour only.
 */

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T, val fromDownloads: Boolean) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> Sourced<T>): Load<T> {
    val state by produceState<Load<T>>(Load.Loading, *keys) {
        value = try {
            val s = block()
            Load.Ready(s.value, s.fromDownloads)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    return state
}

@Composable
fun <T> LoadContent(load: Load<T>, content: @Composable (T, Boolean) -> Unit) {
    when (load) {
        Load.Loading -> Message("Loading…")
        is Load.Failed -> Message(load.message)
        is Load.Ready -> content(load.value, load.fromDownloads)
    }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Art(model: Any?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    title: String,
    subtitle: String?,
    art: Any?,
    modifier: Modifier = Modifier,
    showArt: Boolean = true,
    trailing: String? = null,
    selected: Boolean = false,
    dimmed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) colors.primaryContainer else colors.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showArt) Art(art, 48.dp)
        Column(Modifier.weight(1f)) {
            val ink = if (dimmed) colors.onBackground.copy(alpha = 0.4f) else colors.onBackground
            Text(title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, color = ink.copy(alpha = ink.alpha * 0.7f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall)
    }
}

fun Track.durationText() = Voice.formatDuration(durationSec)
fun Artist.subtitle() = if (albumCount == 1) "1 album" else "$albumCount albums"
fun Album.subtitle() = listOfNotNull(artist.ifBlank { null }, year?.toString()).joinToString(" · ")
fun Playlist.subtitle() = if (trackCount == 1) "1 track" else "$trackCount tracks"
