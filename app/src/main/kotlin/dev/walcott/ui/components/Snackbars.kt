package dev.walcott.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Says that something happened, and — when it can be taken back — offers to take it back.
 *
 * The app used to answer every action with a toast, which is a message with no memory and no
 * handle: it says "sent" and floats off, and the actions that are NOT sends (deleting a domain, a
 * window, a special day) said nothing at all and could not be undone. A parent who mis-taps a row
 * has to reconstruct what was there, which for a schedule means remembering two times, five
 * weekdays and a special-days setting.
 *
 * One controller for the whole app rather than a host per screen: several of these actions are
 * taken from a sheet or a dialog that closes itself, so the confirmation has to outlive the thing
 * that started it.
 */
@Stable
class SnackbarController(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    /**
     * Shows [message]. With [undoLabel] and [onUndo], the message carries the way back and stays
     * up longer — an undo nobody has time to read is not an undo.
     */
    fun show(message: String, undoLabel: String? = null, onUndo: (() -> Unit)? = null) {
        scope.launch {
            // The newest action is the one being asked about: a queue of stale confirmations, each
            // offering to undo something two taps ago, is how an undo becomes dangerous.
            host.currentSnackbarData?.dismiss()
            val undoable = undoLabel != null && onUndo != null
            val result = host.showSnackbar(
                message = message,
                actionLabel = undoLabel.takeIf { undoable },
                duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo?.invoke()
        }
    }
}

/**
 * The app's controller. Provided once, around every screen (see `WalcottApp`), so reading it
 * outside that tree is a programming error rather than a message that vanishes silently.
 */
val LocalSnackbar = staticCompositionLocalOf<SnackbarController> {
    error("No SnackbarController in scope: the screen is not inside WalcottApp's host")
}

/** Builds the controller for [host], tied to the composition's scope. */
@Composable
fun rememberSnackbarController(host: SnackbarHostState): SnackbarController {
    val scope = rememberCoroutineScope()
    return remember(host, scope) { SnackbarController(host, scope) }
}
