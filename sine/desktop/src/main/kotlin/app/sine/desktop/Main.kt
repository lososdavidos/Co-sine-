package app.sine.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.sine.desktop.player.VlcEngine
import app.sine.desktop.ui.SineDesktop

fun main() {
    val graph = DesktopGraph(Folders.app, Folders.music, VlcEngine.create())
    graph.start()
    application {
        Window(
            onCloseRequest = { graph.player.release(); exitApplication() },
            title = "Sine",
            icon = BitmapPainter(useResource("sine/icon.png", ::loadImageBitmap)),
            state = rememberWindowState(size = DpSize(1440.dp, 900.dp)),
        ) {
            SineDesktop(graph)
        }
    }
}
