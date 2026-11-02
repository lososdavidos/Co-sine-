package app.sine.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.sine.core.account.Account
import app.sine.core.server.ServerKind
import app.sine.core.subsonic.SubsonicCredentials
import app.sine.desktop.ui.SineDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop app, driven as you would: against a small Subsonic server, with no sound. */
@OptIn(ExperimentalTestApi::class)
class DesktopUiTest {
    private val server = FakeSubsonic()
    private val dir: File = Files.createTempDirectory("sine-ui").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngine()
    private val shots = File("build/screens").apply { mkdirs() }

    private fun graph(signedIn: Boolean = true) = DesktopGraph(File(dir, "app"), File(dir, "Music"), engine, scope, "Studio").also {
        if (signedIn) it.accounts.add(
            Account.create(server.url, SubsonicCredentials.fromPassword("me", "pw"), ServerKind.Subsonic("1.16.1", "navidrome"), 1L),
        )
    }

    @AfterTest
    fun done() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        Thread.sleep(600) // covers come over the network
        waitForIdle()
        ImageIO.write(scene().toAwtImage(), "png", File(shots, "$name.png"))
    }

    /** The whole window, menus and dialogs included. */
    private fun ComposeUiTest.scene() = (this as androidx.compose.ui.test.SkikoComposeUiTest).captureToImage()

    private fun ComposeUiTest.waitFor(text: String) = try {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        // What the window showed instead, to see why.
        runCatching { ImageIO.write(scene().toAwtImage(), "png", File(shots, "waiting-for-${text.filter { it.isLetterOrDigit() }}.png")) }
        throw AssertionError("Never showed \"$text\"", e)
    }

    @Test
    fun `signs in to a server`() = runDesktopComposeUiTest(1440, 900) {
        val g = graph(signedIn = false)
        setContent { SineDesktop(g) }
        onNodeWithContentDescription("Server").performTextInput(server.url)
        onNodeWithText("Find it").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Username").fetchSemanticsNodes().isNotEmpty() }
        shot("sign-in")
        onNodeWithContentDescription("Username").performTextInput("me")
        onNodeWithContentDescription("Password").performTextInput("pw")
        onNodeWithText("Log in").performClick()
        waitUntil(timeoutMillis = 10_000) { g.accounts.state.value.accounts.isNotEmpty() }
        waitFor("Home")
    }

    @Test
    fun `home, an album, a selection and its menu, and the queue`() = runDesktopComposeUiTest(1440, 900) {
        val g = graph()
        setContent { SineDesktop(g) }
        waitFor("Now playing")
        shot("home")

        onNodeWithText("Albums").performClick()
        waitFor("Goldberg Variations")
        shot("albums")
        onNodeWithText("Goldberg Variations").performClick()
        waitFor("Variation 8")

        // Play from a song: a double click.
        onNodeWithText("Variation 8").performMouseInput { click(); click() }
        waitUntil(timeoutMillis = 5_000) { g.player.state.value.current?.title == "Variation 8" }
        assertTrue("id=al-1-9" in engine.loaded.last())

        // Select two songs, Ctrl-click the second, and open their menu.
        onNodeWithText("Variation 3, Canone all'unisono").performMouseInput { click() }
        onNodeWithText("Variation 4").performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithText("Variation 4").performMouseInput { click() }
        onNodeWithText("Variation 4").performKeyInput { keyUp(Key.CtrlLeft) }
        shot("album-selected")
        onNodeWithText("Variation 4").performMouseInput { click(position = center, button = MouseButton.Secondary) }
        shot("album-right-clicked")
        waitFor("2 SELECTED")
        shot("album-menu")
        onNodeWithText("Add to queue").performClick()
        waitFor("QUEUED")
        val queued = g.player.state.value.queue.queuedAhead.map { it.second.track.title }
        assertEquals(listOf("Variation 3, Canone all'unisono", "Variation 4"), queued)
        shot("album-queued")
    }

    @Test
    fun `search, artists and playlists`() = runDesktopComposeUiTest(1440, 900) {
        val g = graph()
        setContent { SineDesktop(g) }
        waitFor("Home")
        onNodeWithContentDescription("Search").performTextInput("deb")
        waitFor("Suite bergamasque")
        shot("search")
        onNodeWithText("Artists").performClick()
        waitFor("Erik Satie")
        shot("artists")
        onNodeWithText("Playlists").performClick()
        waitFor("Late and quiet")
        onNodeWithText("Late and quiet").performClick()
        waitFor("Clair de lune")
        shot("playlist")
        onAllNodesWithText("me").onFirst().performClick()
        waitFor("Add an account")
        shot("settings")
    }

    @Test
    fun `changes the password, and signs in again when it changed elsewhere`() = runDesktopComposeUiTest(1440, 900) {
        server.password = "pw"
        val g = graph()
        setContent { SineDesktop(g) }
        waitFor("Home")
        onAllNodesWithText("me").onFirst().performClick()
        waitFor("Change password")
        onNodeWithText("Change password").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("New password").fetchSemanticsNodes().isNotEmpty() }
        val before = g.accounts.state.value.accounts.single().token
        onNodeWithContentDescription("New password").performTextInput("correct horse")
        onNodeWithContentDescription("Again").performTextInput("correct horse")
        shot("password-change")
        onNodeWithText("Change it").performClick()
        waitUntil(timeoutMillis = 10_000) { server.password == "correct horse" }
        waitUntil(timeoutMillis = 10_000) { g.accounts.state.value.accounts.single().token != before }

        // Changed somewhere else: the next request is refused, and Sine asks.
        server.password = "changed elsewhere"
        onNodeWithText("Albums").performClick()
        waitFor("Sign in again")
        shot("password-refused")
        onAllNodesWithText("Sign in again").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Password").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Password").performTextInput("changed elsewhere")
        onNodeWithText("Sign in").performClick()
        waitUntil(timeoutMillis = 10_000) { !g.sessionById(g.accounts.state.value.accounts.single().id)!!.refused.value }
        waitFor("Goldberg Variations")
    }
}
