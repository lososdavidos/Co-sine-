package app.sine.ui

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.sine.ui.phase.PhaseColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Pages (§6.16): where you are, and how you get there. A page opens from the
 * right over the one you were on and goes back out the way it came; Home,
 * Search and Library stack in the bar's order; now playing comes up from the
 * bottom. A finger drives any of it, one to one, and the page underneath
 * blurs as it's covered. Each destination keeps its own pages, as before.
 */

/** A place you can be: a route, or a small form that lives only while it's open (§6.5). */
@Immutable
class Page internal constructor(val id: Long, val route: String, internal val form: (@Composable () -> Unit)? = null) {
    /** Now playing comes up from the bottom; everything else comes in from the right. */
    val isSheet: Boolean get() = route == Routes.NOW_PLAYING

    /** The shelf sits over the pages you browse; now playing, a form and adding an account have the screen to themselves. */
    internal val hasShelf: Boolean get() = form == null && !isSheet && route != Routes.ADD_ACCOUNT
}

/**
 * A page moving over another. [progress] is how far [over] is out of
 * place: 0 covers [under] entirely, 1 leaves all of it showing. Where it
 * settles says what happened: [onIn] at 0, [onOut] at 1.
 */
@Stable
class Move internal constructor(
    val under: Page,
    val over: Page,
    val vertical: Boolean,
    start: Float,
    internal val onIn: () -> Unit = {},
    internal val onOut: () -> Unit = {},
) {
    var progress by mutableFloatStateOf(start)
        internal set
    /** A finger has it; otherwise it's on its way to [target]. */
    var dragging by mutableStateOf(false)
        internal set
    internal var target = start

    /** How much of [under] is hidden: it's as blurred and dimmed as it's covered. */
    val cover: Float get() = 1f - progress
}

@Stable
class Pages internal constructor(
    val destinations: List<String>,
    internal var nextId: Long,
    here: String,
    restored: Map<String, List<Page>>,
) {
    private val stacks = destinations.associateWith { d ->
        mutableStateListOf<Page>().apply { addAll(restored[d].orEmpty().ifEmpty { listOf(Page(nextId++, d)) }) }
    }

    /** The destination you're in: the shelf's light pill. */
    var here by mutableStateOf(here)
        private set
    val stack: List<Page> get() = stacks.getValue(here)
    val top: Page get() = stack.last()

    /** The move under way, if any. */
    var move by mutableStateOf<Move?>(null)
        private set

    /** Side to side is off while something is selected or a question waits (§6.16). */
    var swipes by mutableStateOf(true)

    internal var size = IntSize(1, 1)
    /** How fast a flick has to be, in pixels a second, to decide a move on its own. */
    internal var flingPx = 1_000f
    internal lateinit var scope: CoroutineScope
    internal var holder: SaveableStateHolder? = null
    private var job: Job? = null

    /** The page back returns to from [page]. */
    fun below(page: Page): Page? =
        stacks.values.firstNotNullOfOrNull { s -> s.indexOf(page).takeIf { it > 0 }?.let { s[it - 1] } }

    /** Back has somewhere to go: a page to undo, or Home from another destination. */
    val canGoBack: Boolean get() = stack.size > 1 || here != destinations.first()

    fun navigate(route: String, singleTop: Boolean = false) {
        if (singleTop && top.route == route) return
        push(Page(nextId++, route))
    }

    /** Opens a small form as a page (§6.5): it slides in, and back cancels it. */
    fun form(content: @Composable () -> Unit) = push(Page(nextId++, FORM, content))

    /** Now playing, up from the bottom. */
    fun openPlayer() {
        if (!top.isSheet) push(sheet())
    }

    /** Undoes the page on top, if there's one to undo. */
    fun popBackStack(): Boolean {
        settleNow()
        val m = popMove() ?: return false
        run(m, 1f)
        return true
    }

    /** A word in the bar (§6.2). The destination you're in takes you back to its first page. */
    fun go(destination: String) {
        settleNow()
        if (destination == here) {
            val s = stacks.getValue(here)
            if (s.size < 2) return
            val over = s.last()
            run(Move(s.first(), over, over.isSheet, 0f, onOut = { while (s.size > 1) forget(s.removeAt(s.lastIndex)) }), 1f)
            return
        }
        val from = top
        val to = stacks.getValue(destination).last()
        val ahead = destinations.indexOf(destination) > destinations.indexOf(here)
        here = destination
        // Further along the bar slides in over; back along it uncovers.
        if (ahead) run(Move(from, to, false, 1f), 0f) else run(Move(to, from, false, 0f), 1f)
    }

    // ------------------------------------------------------------ moves

    // One now-playing sheet per stack: an older one beneath goes.
    private fun sheet(): Page {
        val s = stacks.getValue(here)
        s.filter { it.isSheet }.forEach { s.remove(it); forget(it) }
        return Page(nextId++, Routes.NOW_PLAYING)
    }

    private fun push(page: Page, dragging: Boolean = false) {
        settleNow()
        val s = stacks.getValue(here)
        val m = Move(s.last(), page, page.isSheet, 1f, onOut = { s.remove(page); forget(page) })
        s.add(page)
        if (dragging) hold(m) else run(m, 0f)
    }

    private fun popMove(): Move? {
        val s = stacks.getValue(here)
        if (s.size < 2) return null
        val over = s.last()
        return Move(s[s.size - 2], over, over.isSheet, 0f, onOut = { s.remove(over); forget(over) })
    }

    /** Back from another destination's first page is Home, uncovered. */
    private fun homeMove(): Move? {
        val home = destinations.first()
        if (here == home || stack.size > 1) return null
        return Move(stacks.getValue(home).last(), top, false, 0f, onOut = { here = home })
    }

    /** The destination next along the bar, sliding in over this one, or the one before, uncovered. */
    private fun neighbour(dx: Float): Move? {
        val i = destinations.indexOf(here)
        return if (dx < 0) {
            destinations.getOrNull(i + 1)?.let { d -> Move(top, stacks.getValue(d).last(), false, 1f, onIn = { here = d }) }
        } else {
            destinations.getOrNull(i - 1)?.let { d -> Move(stacks.getValue(d).last(), top, false, 0f, onOut = { here = d }) }
        }
    }

    // ------------------------------------------------------------ fingers

    /** A sideways drag on the page on top: back on a page, the next or previous destination on a first page. */
    internal fun dragSideways(dx: Float) {
        if (move == null) {
            if (!swipes || dx == 0f) return
            val m = if (stack.size > 1) popMove()?.takeIf { dx > 0 && !it.over.isSheet } else neighbour(dx)
            hold(m ?: return)
        }
        val m = move ?: return
        if (m.dragging && !m.vertical) m.progress = (m.progress + dx / size.width).coerceIn(0f, 1f)
    }

    /** A drag up from the player row brings now playing up under the finger. */
    internal fun dragPlayer(dy: Float) {
        if (move == null) {
            if (dy >= 0 || top.isSheet) return
            push(sheet(), dragging = true)
        }
        dragVertically(dy)
    }

    /** A drag down on now playing takes it back down. */
    internal fun dragSheet(dy: Float) {
        if (move == null) {
            if (dy <= 0 || !top.isSheet) return
            hold(popMove() ?: return)
        }
        dragVertically(dy)
    }

    private fun dragVertically(dy: Float) {
        val m = move ?: return
        if (m.dragging && m.vertical) m.progress = (m.progress + dy / size.height).coerceIn(0f, 1f)
    }

    /**
     * The finger let go at [velocity] pixels a second: a flick decides, else
     * whichever end is nearer. Either way the page carries on at the
     * finger's speed rather than starting again.
     */
    internal fun release(velocity: Float) {
        val m = move ?: return
        if (!m.dragging) return
        m.dragging = false
        val extent = (if (m.vertical) size.height else size.width).toFloat()
        val v = velocity / extent
        val fling = flingPx / extent
        val target = when {
            v > fling -> 1f
            v < -fling -> 0f
            m.progress > 0.5f -> 1f
            else -> 0f
        }
        run(m, target, v)
    }

    /** Android's back gesture (§6.16): from Android 14 it reports progress, and the same slide follows it. */
    internal fun beginBack(): Move? {
        settleNow()
        val m = popMove() ?: homeMove() ?: return null
        hold(m)
        return m
    }

    internal fun backProgress(m: Move, fraction: Float) {
        if (move === m && m.dragging) m.progress = fraction.coerceIn(0f, 1f)
    }

    internal fun endBack(m: Move, commit: Boolean) {
        if (move !== m) return
        m.dragging = false
        run(m, if (commit) 1f else 0f)
    }

    // ------------------------------------------------------------ running them

    private fun hold(m: Move) {
        job?.cancel()
        m.dragging = true
        move = m
    }

    private fun run(m: Move, target: Float, velocity: Float? = null) {
        job?.cancel()
        move = m
        m.target = target
        job = scope.launch {
            // Tapped, the slide is eased out; let go, it springs from the finger's speed and never overshoots.
            val spec: AnimationSpec<Float> =
                if (velocity == null) tween(SLIDE_MS, easing = SlideEasing) else spring(dampingRatio = 1f, stiffness = 400f)
            animate(m.progress, target, velocity ?: 0f, spec) { v, _ -> m.progress = v.coerceIn(0f, 1f) }
            finish(m)
        }
    }

    private fun finish(m: Move) {
        if (move !== m) return
        move = null
        if (m.target >= 1f) m.onOut() else m.onIn()
    }

    /** Ends a move in flight where it was going, so the next starts from rest. */
    private fun settleNow() {
        val m = move ?: return
        job?.cancel()
        if (m.dragging) {
            m.dragging = false
            m.target = if (m.progress > 0.5f) 1f else 0f
        }
        finish(m)
    }

    private fun forget(page: Page) {
        holder?.removeState(page.id)
    }

    companion object {
        internal const val FORM = "form"
        private const val SLIDE_MS = 300
        private val SlideEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** Pages survive the app being put away; forms don't, as they hold what they were opened with. */
        internal fun saver(destinations: List<String>) = Saver<Pages, List<String>>(
            save = { p ->
                listOf(p.here, p.nextId.toString()) + destinations.map { d ->
                    p.stacks.getValue(d).filter { it.form == null }.joinToString("\n") { "${it.id} ${it.route}" }
                }
            },
            restore = { s ->
                val stacks = destinations.withIndex().associate { (i, d) ->
                    d to s[i + 2].lines().filter { it.isNotBlank() }.map { Page(it.substringBefore(' ').toLong(), it.substringAfter(' ')) }
                }
                Pages(destinations, s[1].toLong(), s[0], stacks)
            },
        )
    }
}

val LocalPages = staticCompositionLocalOf<Pages> { error("No pages here.") }

@Composable
fun rememberPages(destinations: List<String>): Pages {
    val pages = rememberSaveable(saver = Pages.saver(destinations)) { Pages(destinations, 1, destinations.first(), emptyMap()) }
    pages.scope = rememberCoroutineScope()
    return pages
}

/**
 * Draws where you are and the move under way: the page on top, and while
 * one moves the page beneath it too, as blurred and dimmed as it's covered.
 * The shelf keeps one place in the tree and changes only its depth: over
 * both pages when both have it, beneath the blur when the page coming in
 * hasn't, and moving with a page that brings it.
 */
@Composable
fun PageHost(pages: Pages, shelf: @Composable BoxScope.() -> Unit, content: @Composable (Page) -> Unit) {
    val holder = rememberSaveableStateHolder()
    pages.holder = holder
    pages.flingPx = with(LocalDensity.current) { FLING.toPx() }
    val m = pages.move
    val underLayer = rememberGraphicsLayer()
    var blur by remember { mutableStateOf<ImageBitmap?>(null) }
    // The page beneath is caught once, when the move starts, and blurred off the main thread (§6.16).
    LaunchedEffect(m) {
        blur = null
        if (m == null) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        blur = try {
            blurred(underLayer.toImageBitmap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // dimmed only
        }
    }
    PredictiveBackHandler(enabled = pages.canGoBack) { events ->
        val back = pages.beginBack()
        if (back == null) {
            events.collect {}
            return@PredictiveBackHandler
        }
        try {
            events.collect { pages.backProgress(back, it.progress) }
            pages.endBack(back, commit = true)
        } catch (e: CancellationException) {
            pages.endBack(back, commit = false)
            throw e
        }
    }
    val sideways = rememberDraggableState { pages.dragSideways(it) }
    val sheetScroll = remember(pages) { SheetScroll(pages) }

    Box(Modifier.fillMaxSize().onSizeChanged { pages.size = it }) {
        val visible = if (m == null) listOf(pages.top) else listOf(m.under, m.over)
        for (page in visible) key(page.id) {
            val under = m != null && page === m.under
            val over = m != null && page === m.over
            // The same modifiers whatever the page's part, so a page that
            // goes under mid-drag (the next destination coming in over it)
            // keeps the finger.
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (over) 2f else 0f)
                    .graphicsLayer {
                        if (over && m != null) {
                            if (m.vertical) translationY = m.progress * size.height else translationX = m.progress * size.width
                        }
                    }
                    .drawWithContent {
                        if (under) {
                            underLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(underLayer)
                        } else drawContent()
                    }
                    .nestedScroll(sheetScroll.takeIf { page.isSheet } ?: NoScroll)
                    .draggable(
                        sideways, Orientation.Horizontal,
                        enabled = !page.isSheet && pages.swipes && (m == null || m.dragging),
                        onDragStopped = { pages.release(it) },
                    ),
            ) {
                holder.SaveableStateProvider(page.id) { content(page) }
            }
        }
        if (m != null) {
            // The page beneath, drawn small and then large, over it and the
            // shelf when the shelf stays beneath. Nothing under it takes a touch.
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(1.5f)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                    .drawBehind {
                        val c = m.cover
                        blur?.let {
                            drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = c, filterQuality = FilterQuality.Low)
                        }
                        drawRect(PhaseColor.Ground, alpha = DIM * c)
                    },
            )
        }
        val shelfAbove = if (m == null) pages.top.hasShelf else m.over.hasShelf
        val shelfShown = if (m == null) pages.top.hasShelf else m.under.hasShelf || m.over.hasShelf
        val bringsShelf = m != null && m.over.hasShelf && !m.under.hasShelf
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(if (!shelfShown) -1f else if (shelfAbove) 3f else 1f)
                .graphicsLayer {
                    alpha = if (shelfShown) 1f else 0f
                    if (bringsShelf && m != null) {
                        if (m.vertical) translationY = m.progress * size.height else translationX = m.progress * size.width
                    }
                }
                .then(if (shelfShown) Modifier else Modifier.clearAndSetSemantics {}),
            content = shelf,
        )
    }
}

/** Now playing goes down with a drag that its own list doesn't take: from the top, or anywhere it can't scroll. */
private class SheetScroll(private val pages: Pages) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val m = pages.move ?: return Offset.Zero
        // Partway down and pulled back up: the sheet goes first.
        if (source != NestedScrollSource.UserInput || !m.dragging || !m.vertical || available.y >= 0) return Offset.Zero
        val before = m.progress
        pages.dragSheet(available.y)
        return Offset(0f, (m.progress - before) * pages.size.height)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0) return Offset.Zero
        pages.dragSheet(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val m = pages.move ?: return Velocity.Zero
        if (!m.dragging || !m.vertical) return Velocity.Zero
        pages.release(available.y)
        return available
    }
}

private object NoScroll : NestedScrollConnection

/** Halved until it's a few dozen pixels across: each step averages, so drawn large again it's soft, not blocky. */
private suspend fun blurred(image: ImageBitmap): ImageBitmap = withContext(Dispatchers.Default) {
    var b = image.asAndroidBitmap()
    if (b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false)
    while (b.width / 2 >= 40) b = Bitmap.createScaledBitmap(b, b.width / 2, maxOf(1, b.height / 2), true)
    b.asImageBitmap()
}

private val FLING = 400.dp
/** How dark the page beneath gets when it's fully covered. */
private const val DIM = 0.4f
