package app.sine.data

import android.net.Uri
import java.io.File

/*
 * An account's session lives in core, the same on Android and the desktop
 * (§6.15); these keep the names the app has always used.
 */
typealias AccountSession = app.sine.core.session.AccountSession
typealias Sourced<T> = app.sine.core.session.Sourced<T>
typealias NotAvailableOffline = app.sine.core.session.NotAvailableOffline
typealias PlaylistGone = app.sine.core.session.PlaylistGone
typealias PlaylistChanged = app.sine.core.session.PlaylistChanged

/** A cover as Android hands it to the player and the system: a file here, else the server's. */
fun AccountSession.artworkUri(coverArt: String?): Uri? = when (val a = artwork(coverArt)) {
    is File -> Uri.fromFile(a)
    is String -> Uri.parse(a)
    else -> null
}
