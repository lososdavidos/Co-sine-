package app.sine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.core.text.Voice
import app.sine.graph
import kotlinx.coroutines.delay

/** Now playing with the queue below it on the same surface — no overlay opening another (§6.5). */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val player = LocalContext.current.graph.player
    val state by player.state.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(state.current, state.isPlaying) {
        while (true) {
            positionMs = player.positionMs()
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onClose) { Text("Close") }
        val item = state.current
        if (item == null) {
            Message("Nothing playing.")
            return@Column
        }
        val meta = item.mediaMetadata
        val duration = state.durationMs
        val fraction = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Art(meta.artworkUri, 240.dp)
            Text(meta.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(meta.artist, meta.albumTitle).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = scrub ?: fraction,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { player.seekTo((it * duration).toLong()) }
                    scrub = null
                },
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shownMs = scrub?.let { (it * duration).toLong() } ?: positionMs
                Text(Voice.formatDuration((shownMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
                Text(Voice.formatDuration((duration / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { player.previous() }) { Text("Prev") }
                OutlinedButton(onClick = { player.togglePlay() }) { Text(if (state.isPlaying) "Pause" else "Play") }
                OutlinedButton(onClick = { player.next() }) { Text("Next") }
                OutlinedButton(onClick = { player.toggleShuffle() }) { Text(if (state.shuffle) "Shuffle on" else "Shuffle off") }
            }
        }

        Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.queue) { i, q ->
                ItemRow(
                    title = q.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = q.mediaMetadata.artist?.toString(),
                    art = null,
                    showArt = false,
                    selected = i == state.index,
                ) { player.skipTo(i) }
            }
        }
    }
}
