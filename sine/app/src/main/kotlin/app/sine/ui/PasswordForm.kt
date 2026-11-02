package app.sine.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.sine.core.subsonic.SubsonicException
import app.sine.data.AccountSession
import app.sine.graph
import app.sine.ui.phase.PhaseColor
import app.sine.ui.phase.PhaseField
import app.sine.ui.phase.PhaseType
import app.sine.ui.phase.Signal
import app.sine.ui.phase.StatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * An account's password (§2.1). [again]: the server stopped taking the one
 * kept here, changed elsewhere, so this asks for it; otherwise it changes it
 * on the server. Either way the account stays signed in with its downloads,
 * and only a token and salt are kept, never the password.
 */
@Composable
internal fun PasswordForm(session: AccountSession, again: Boolean) {
    val graph = LocalContext.current.graph
    val pages = LocalPages.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val account = session.account
    val ready = password.isNotEmpty() && (again || password == repeat) && !busy

    FormPage(
        if (again) "Sign in again" else "Change password",
        if (again) "Sign in" else "Change it",
        enabled = ready,
        onAction = {
            busy = true
            problem = null
            scope.launch {
                try {
                    val updated = if (again) session.signInAgain(password) else session.changePassword(password)
                    graph.accounts.update(updated)
                    say(if (again) "Signed in again." else "Password changed.")
                    pages.popBackStack()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubsonicException) {
                    problem = when {
                        e.isAuthFailure && again -> "That isn't it. The server still says no."
                        e.code == 50 -> "This server lets only an admin change passwords."
                        e.code == 0 && !again -> "This server can't change passwords from an app. Change it on the server."
                        else -> e.message
                    }
                } catch (e: IOException) {
                    problem = "Can't reach ${account.serverUrl.substringAfter("://")}."
                } finally {
                    busy = false
                }
            }
        },
    ) {
        Text(
            if (again) "${account.displayName} no longer takes the password Sine kept: it was changed somewhere else. Its downloads stay."
            else "For ${account.displayName}. Your other apps and devices will ask for the new one.",
            color = PhaseColor.Text2, style = PhaseType.Body,
        )
        PhaseField(
            password, { password = it; problem = null },
            label = if (again) "Password" else "New password",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (!again) {
            PhaseField(
                repeat, { repeat = it; problem = null }, label = "Again",
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (repeat.isNotEmpty() && repeat != password) StatusLine("The two aren't the same yet.", color = PhaseColor.Text2)
        }
        problem?.let { StatusLine(it, signal = Signal.Fault) }
    }
}
