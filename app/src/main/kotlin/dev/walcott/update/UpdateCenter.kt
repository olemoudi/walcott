package dev.walcott.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the self-update machinery is doing right now, for the settings UI. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersionCode: Int) : UpdateUiState
    data class Downloading(val target: UpdateInfo) : UpdateUiState
    /** Waiting for the user to accept the system install dialog (non-owner devices). */
    data class PendingConfirmation(val target: UpdateInfo?) : UpdateUiState
    data class Failed(val step: String) : UpdateUiState
}

/**
 * Process-wide update status. [Updater] and [InstallReceiver] write; the settings UI reads.
 * A plain singleton (no DI): update checks run from several entry points (launch worker,
 * periodic worker, enforcement service) and all should feed the same status line.
 */
object UpdateCenter {
    private val mutable = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutable

    internal fun report(state: UpdateUiState) {
        mutable.value = state
    }
}
