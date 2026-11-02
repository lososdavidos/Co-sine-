package app.sine.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sine.core.server.Capability
import app.sine.core.session.AccountSession
import app.sine.ui.phase.IconTone
import app.sine.ui.phase.Initial
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseIcon
import app.sine.ui.phase.PhaseIcons
import app.sine.ui.phase.PhaseLabel
import app.sine.ui.phase.PhaseShape
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine

val LocalNav = staticCompositionLocalOf { Nav() }

/** The window: destinations in a sidebar, the page, the queue beside it, the player docked along the bottom (§6.15). */
@Composable
fun Shell(session: AccountSession) {
    val graph = LocalGraph.current
    val say = LocalSay.current
    val nav = remember(session.account.id) { Nav() }
    var showQueue by remember { mutableStateOf(true) }
    val searchFocus = remember { FocusRequester() }
    val root = remember { FocusRequester() }
    val player = graph.player
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }

    CompositionLocalProvider(LocalNav provides nav, LocalSession provides session) {
        Column(
            Modifier
                .fillMaxSize()
                .background(PhaseColor.Ground)
                .focusRequester(root)
                .focusable()
                .onPreviewKeyEvent { e ->
                    // Shortcuts that hold everywhere, even while typing.
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val command = e.isCtrlPressed || e.isMetaPressed
                    when {
                        command && e.key == Key.K -> { runCatching { searchFocus.requestFocus() }; true }
                        command && e.key == Key.DirectionRight -> { player.next(); true }
                        command && e.key == Key.DirectionLeft -> { player.previous(); true }
                        command && e.key == Key.DirectionUp -> { player.setVolume(player.state.value.volume + 0.05f); true }
                        command && e.key == Key.DirectionDown -> { player.setVolume(player.state.value.volume - 0.05f); true }
                        e.isAltPressed && e.key == Key.DirectionLeft -> { nav.back(); true }
                        e.key == Key.MediaPlayPause -> { player.togglePlay(); true }
                        e.key == Key.MediaNext -> { player.next(); true }
                        e.key == Key.MediaPrevious -> { player.previous(); true }
                        else -> false
                    }
                }
                .onKeyEvent { e ->
                    // Space plays and pauses when nothing else took it, a field or a list.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Spacebar) { player.togglePlay(); true } else false
                },
        ) {
            // The server stopped taking the password kept here: changed elsewhere (§2.1).
            val refused by session.refused.collectAsState()
            var signingIn by remember { mutableStateOf(false) }
            if (refused) {
                Row(
                    Modifier.fillMaxWidth().background(PhaseColor.Raised).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatusLine("${session.account.displayName} no longer takes the password Sine kept.", Modifier.weight(1f), signal = Signal.Fault)
                    app.sine.ui.phase.Pill("Sign in again", { signingIn = true }, kind = app.sine.ui.phase.PillKind.Primary, height = 40.dp)
                }
            }
            if (signingIn) PasswordDialog(session, again = true, onDone = { signingIn = false })
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(session, nav, searchFocus, Modifier.width(248.dp).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Page(nav.current, session)
                    val line = say.line
                    if (line != null) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(16.dp)
                                .clip(PhaseShape.Floating).background(PhaseColor.Float)
                                .border(1.dp, PhaseColor.LineFloat, PhaseShape.Floating)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) { StatusLine(line.first, signal = line.second) }
                    }
                }
                if (showQueue) QueuePanel(session, Modifier.width(340.dp).fillMaxHeight())
            }
            PlayerBar(session, showQueue, { showQueue = !showQueue }, Modifier.fillMaxWidth())
        }
    }
}

/** The account the window shows. */
val LocalSession = staticCompositionLocalOf<AccountSession> { error("No session") }

@Composable
private fun Page(dest: Dest, session: AccountSession) {
    when (dest) {
        Dest.Home -> HomePage(session)
        is Dest.Search -> SearchPage(session, dest.query)
        Dest.Artists -> ArtistsPage(session)
        Dest.Albums -> AlbumsPage(session)
        Dest.Playlists -> PlaylistsPage(session)
        Dest.Downloads -> DownloadsPage(session)
        Dest.Jam -> JamPage(session)
        Dest.Devices -> DevicesPage(session)
        Dest.Settings -> SettingsPage(session)
        is Dest.Artist -> ArtistPage(session, dest)
        is Dest.Album -> AlbumPage(session, dest)
        is Dest.Playlist -> PlaylistPage(session, dest)
    }
}

/** Destinations, down the left (§6.15): a Float surface with a hairline edge, staying put while pages move. */
@Composable
private fun Sidebar(session: AccountSession, nav: Nav, searchFocus: FocusRequester, modifier: Modifier) {
    val here = nav.current.root()
    var query by remember { mutableStateOf("") }
    val account by session.accountFlow.collectAsState()
    val reachable by session.reachable.collectAsState()
    Row(modifier) {
        Column(
            Modifier.weight(1f).fillMaxHeight().background(PhaseColor.Float).padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhaseIcon(PhaseIcons.Wave, null, size = 28.dp, tone = IconTone.Sine)
                Text("Sine", color = PhaseColor.Text1, style = PhaseType.display(30.sp))
            }
            PhaseField(
                query,
                {
                    query = it
                    nav.search(it)
                },
                Modifier.fillMaxWidth().padding(bottom = 12.dp).focusRequester(searchFocus),
                placeholder = "Search",
                leading = PhaseIcons.Search,
                onClear = if (query.isEmpty()) null else ({ query = ""; nav.search("") }),
            )
            Destination("Home", PhaseIcons.Home, here == Dest.Home) { nav.go(Dest.Home) }
            PhaseLabel("Library", Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp))
            Destination("Artists", PhaseIcons.Artist, here == Dest.Artists) { nav.go(Dest.Artists) }
            Destination("Albums", PhaseIcons.Album, here == Dest.Albums) { nav.go(Dest.Albums) }
            Destination("Playlists", PhaseIcons.Playlist, here == Dest.Playlists) { nav.go(Dest.Playlists) }
            Destination("Liked", PhaseIcons.Liked, here == Dest.Liked) { nav.go(Dest.Liked) }
            Destination("On this computer", PhaseIcons.OnPhone, here == Dest.Downloads) { nav.go(Dest.Downloads) }
            if (session.cosine != null) {
                PhaseLabel("Together", Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp))
                if (session.can(Capability.JAM)) Destination("Jam", PhaseIcons.Jam, here == Dest.Jam) { nav.go(Dest.Jam) }
                if (session.can(Capability.SESSIONS)) Destination("Devices", PhaseIcons.Device, here == Dest.Devices) { nav.go(Dest.Devices) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).background(if (reachable) PhaseColor.Cosine else PhaseColor.Fault, PhaseShape.Square))
                Text(
                    if (!reachable) "Can't reach the server" else if (session.cosine != null) "Cosine" else account.kind.label,
                    color = PhaseColor.Text2, style = PhaseType.Small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(PhaseShape.Pill)
                    .background(if (here == Dest.Settings) PhaseColor.Text1 else if (hovered) PhaseColor.Lift else PhaseColor.Float)
                    .hoverable(hover).clickable { nav.go(Dest.Settings) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Initial(account.username, size = 32.dp)
                Text(
                    account.username, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (here == Dest.Settings) PhaseColor.Ink else PhaseColor.Text1, style = PhaseType.Name,
                )
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(PhaseColor.LineFloat))
    }
    LaunchedEffect(nav.current) { if (nav.current !is Dest.Search) query = "" }
}

/** One place in the sidebar. Where you are is the light pill, ink on it (the phone's Chip). */
@Composable
private fun Destination(name: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(PhaseShape.Pill)
            .background(if (selected) PhaseColor.Text1 else if (hovered) PhaseColor.Lift else PhaseColor.Float)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhaseIcon(icon, null, size = 22.dp, tone = if (selected) IconTone.OnLight else IconTone.Dim)
        Text(
            name, color = if (selected) PhaseColor.Ink else PhaseColor.Text1,
            style = PhaseType.Name.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
