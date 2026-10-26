package app.sine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sine.graph
import app.sine.ui.phase.Initial
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Pill
import app.sine.ui.phase.PillKind
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/*
 * The shelf (§6.3, §6.5): one floating surface at the bottom of every screen
 * but now playing. Idle it is navigation; playing, a player row grows out of
 * it; with a selection its navigation becomes the actions for it. It is also
 * where Sine talks back: a message above navigation for a few seconds, or a
 * question that waits for an answer. Nothing opens on top of anything.
 */

/** What the shelf says after something happens. Safe to call from anywhere. */
object Messages {
    data class Message(val text: String, val signal: Signal, val id: Long)

    private val next = AtomicLong()
    private val _current = MutableStateFlow<Message?>(null)
    val current: StateFlow<Message?> = _current.asStateFlow()

    fun say(text: String, signal: Signal = Signal.None) {
        _current.value = Message(text, signal, next.incrementAndGet())
    }

    fun done(id: Long) = _current.update { if (it?.id == id) null else it }
}

fun say(text: String, signal: Signal = Signal.None) = Messages.say(text, signal)

class ShelfAction(val label: String, val kind: PillKind = PillKind.Secondary, val onClick: () -> Unit)

/** A selection's count and what can be done with it, in place of navigation. */
class ShelfSelection(val title: String, val actions: List<ShelfAction>, val onClear: () -> Unit, val note: String? = null)

/** A question that waits for an answer: leave, delete, remove. Say what stays; name the verb. */
class ShelfQuestion(
    val title: String,
    val explanation: String?,
    val actions: List<ShelfAction>,
    val onDismiss: () -> Unit = {},
    /** Offer Cancel too, when none of [actions] is the way out. */
    val cancel: Boolean = false,
)

@Stable
class ShelfController {
    var selection by mutableStateOf<Pair<Any, ShelfSelection>?>(null)
    var question by mutableStateOf<ShelfQuestion?>(null)

    /** How much of the bottom of the screen the shelf covers, for lists to leave clear. */
    var reserve by mutableStateOf(96.dp)

    fun ask(q: ShelfQuestion) {
        question = q
    }
}

val LocalShelf = staticCompositionLocalOf { ShelfController() }

/** Puts [selection] in the shelf while this screen has one. */
@Composable
fun ShowSelection(selection: ShelfSelection?) {
    val shelf = LocalShelf.current
    val owner = remember { Any() }
    SideEffect {
        if (selection != null) shelf.selection = owner to selection
        else if (shelf.selection?.first === owner) shelf.selection = null
    }
    DisposableEffect(shelf) {
        onDispose { if (shelf.selection?.first === owner) shelf.selection = null }
    }
}

/** Space at the bottom of a list so its last row clears the shelf. */
@Composable
fun listPadding(top: Dp = 0.dp): PaddingValues = PaddingValues(top = top, bottom = LocalShelf.current.reserve)

/** Whether the server can be reached, and the account control: the same on every screen. */
class Chrome(val offline: Boolean, val onRetry: () -> Unit, val onAccount: (() -> Unit)?)

val LocalChrome = staticCompositionLocalOf { Chrome(false, {}, null) }

/** Space under the status bar and the floating back pill, for a page whose top isn't its own. */
@Composable
fun TopSpace() {
    Spacer(Modifier.statusBarsPadding().height(72.dp))
}

/** The account control (§6.11): you, as a circle. */
@Composable
fun AccountCircle(floating: Boolean = false) {
    val chrome = LocalChrome.current
    val onAccount = chrome.onAccount ?: return
    val name = LocalContext.current.graph.activeSession.collectAsStateWithLifecycle().value?.account?.username.orEmpty()
    Initial(
        name,
        Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onAccount)
            .semantics { contentDescription = "Accounts and settings" },
        color = if (floating) PhaseColor.Float else PhaseColor.Lift,
    )
}

/** Offline, said where you look first. Tapping asks the server again. */
@Composable
fun OfflineMark() {
    val chrome = LocalChrome.current
    if (!chrome.offline) return
    Box(
        Modifier
            .clip(PhaseShape.Square)
            .background(PhaseColor.Float)
            .border(1.dp, PhaseColor.LineStrong, PhaseShape.Square)
            .clickable(role = Role.Button, onClick = chrome.onRetry)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) { PhaseLabel("Offline", color = PhaseColor.OnScrim) }
}

/** A top-level screen's title, its actions and the account control. */
@Composable
fun TitleBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), style = PhaseType.display(40.sp), color = PhaseColor.Text1, maxLines = 1)
        actions()
        OfflineMark()
        AccountCircle()
    }
}

/** Over any page that isn't top level: back, offline, the account control. Solid pills, so it reads over artwork. */
@Composable
fun DetailBar(back: String, onBack: () -> Unit, showAccount: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(back, onBack, kind = PillKind.Floating, icon = PhaseIcons.Back, height = 40.dp)
        Spacer(Modifier.weight(1f))
        OfflineMark()
        if (showAccount) AccountCircle(floating = true)
    }
}

class Destination(val route: String, val label: String)

/** The shelf itself. */
@Composable
fun Shelf(
    destinations: List<Destination>,
    current: String?,
    onNavigate: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shelf = LocalShelf.current
    val density = LocalDensity.current
    val player = LocalContext.current.graph.player
    val state by player.state.collectAsStateWithLifecycle()
    val message by Messages.current.collectAsStateWithLifecycle()
    val question = shelf.question
    val selection = shelf.selection?.second
    val navBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        delay(4_000)
        Messages.done(m.id)
    }
    BackHandler(enabled = question != null) {
        shelf.question = null
        question?.onDismiss?.invoke()
    }
    BackHandler(enabled = question == null && selection != null) { selection?.onClear?.invoke() }

    Column(
        modifier
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .fillMaxWidth()
            .onSizeChanged { shelf.reserve = with(density) { it.height.toDp() } + navBar + 24.dp }
            .clip(PhaseShape.Floating)
            .background(PhaseColor.Float)
            .border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating)
            .animateContentSize(tween(160)),
    ) {
        if (question != null) {
            QuestionRows(question) { shelf.question = null }
            return@Column
        }
        val item = state.current
        if (item != null) {
            PlayerRow(
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                art = item.mediaMetadata.artworkUri,
                playing = state.isPlaying,
                onOpen = onOpenPlayer,
                onToggle = player::togglePlay,
                onNext = player::next,
            )
            Seam(durationMs = state.durationMs, playing = state.isPlaying, key = item.mediaId) { player.positionMs() }
        }
        // A message sits above navigation for a few seconds, so you can always
        // get somewhere; tapping it puts it away sooner.
        message?.let { m ->
            StatusLine(
                m.text,
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Dismiss") { Messages.done(m.id) }
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
                signal = m.signal,
            )
        }
        if (selection != null) SelectionRows(selection) else NavRow(destinations, current, onNavigate)
    }
}

@Composable
private fun PlayerRow(
    title: String,
    artist: String,
    art: Any?,
    playing: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(PhaseShape.Square)
                .clickable(onClickLabel = "Open now playing", onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Art(art, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(title, color = PhaseColor.Text1, style = PhaseType.Name.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$artist · ", color = PhaseColor.Text2, style = PhaseType.Small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // The Device marker: once remote control exists, playback may be elsewhere (§6.6).
                    PhaseLabel("This phone", color = PhaseColor.Text2)
                }
            }
        }
        Pill(
            null, onToggle, kind = PillKind.Primary, icon = if (playing) PhaseIcons.Pause else PhaseIcons.Play,
            height = 40.dp, width = 56.dp, contentDescription = if (playing) "Pause" else "Play",
        )
        Pill(null, onNext, kind = PillKind.Quiet, icon = PhaseIcons.Next, height = 40.dp, width = 44.dp, contentDescription = "Next")
    }
}

/** The line between the player row and what's under it is the progress: Sine, growing. */
@Composable
private fun Seam(durationMs: Long, playing: Boolean, key: String, position: () -> Long) {
    var fraction by remember(key) { mutableFloatStateOf(0f) }
    LaunchedEffect(key, playing, durationMs) {
        while (true) {
            fraction = if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            if (!playing) break
            delay(500)
        }
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(PhaseColor.LineFloat)) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(PhaseColor.Sine))
    }
}

@Composable
private fun NavRow(destinations: List<Destination>, current: String?, onNavigate: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in destinations) {
            val here = d.route == current
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(PhaseShape.Pill)
                    .background(if (here) PhaseColor.Text1 else PhaseColor.Float)
                    .clickable(role = Role.Tab, onClick = { onNavigate(d.route) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    d.label, color = if (here) PhaseColor.Ink else PhaseColor.Text2,
                    style = PhaseType.Button.copy(fontWeight = if (here) FontWeight.SemiBold else FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun SelectionRows(selection: ShelfSelection) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(selection.title, color = PhaseColor.Text1, style = PhaseType.Name.copy(fontWeight = FontWeight.SemiBold))
            selection.note?.let { Text(it, color = PhaseColor.Text2, style = PhaseType.Small.copy(fontSize = 12.sp)) }
        }
        Pill("Clear", selection.onClear, kind = PillKind.Quiet, height = 36.dp)
    }
    val only = selection.actions.singleOrNull()
    if (only != null) {
        Pill(only.label, only.onClick, kind = only.kind, height = 48.dp, modifier = Modifier.fillMaxWidth().padding(8.dp))
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (a in selection.actions) Pill(a.label, a.onClick, kind = a.kind, height = 44.dp)
    }
}

@Composable
private fun QuestionRows(question: ShelfQuestion, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(question.title, color = PhaseColor.Text1, style = PhaseType.Name.copy(fontWeight = FontWeight.SemiBold))
            question.explanation?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = PhaseColor.Text2, style = PhaseType.Small)
            }
        }
        if (question.cancel) Pill("Cancel", { close(); question.onDismiss() }, kind = PillKind.Quiet, height = 36.dp)
    }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in question.actions) {
            Pill(a.label, { close(); a.onClick() }, kind = a.kind, height = 44.dp, modifier = Modifier.weight(1f))
        }
    }
}
