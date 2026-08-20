package dev.walcott.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the self-update machinery is doing right now, for the settings UI. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val installedVersionCode: Int) : UpdateUiState
    data class Downloading(val target: UpdateInfo) : UpdateUiState
    /** The bytes are here and checked; the install session is being committed. */
    data class Installing(val target: UpdateInfo) : UpdateUiState
    /** Waiting for the user to accept the system install dialog (non-owner devices). */
    data class PendingConfirmation(val target: UpdateInfo?) : UpdateUiState
    /** Canary gate: [target] exists but this child waits until the parent runs it. */
    data class WaitingForParent(val target: UpdateInfo) : UpdateUiState
    /** Wi-Fi-only policy on a metered connection: [target] is known, the download is what waits. */
    data class WaitingForWifi(val target: UpdateInfo) : UpdateUiState
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

    /**
     * The build the current attempt is aiming at. [InstallReceiver] is told a status and nothing
     * else — not even which session it belongs to — so without this the "waiting for you to
     * confirm" line could not name the version the person is being asked about.
     */
    @Volatile
    private var target: UpdateInfo? = null

    internal fun report(state: UpdateUiState) {
        targetOf(state)?.let { target = it }
        mutable.value = state
    }

    internal fun lastTarget(): UpdateInfo? = target

    private fun targetOf(state: UpdateUiState): UpdateInfo? = when (state) {
        is UpdateUiState.Downloading -> state.target
        is UpdateUiState.Installing -> state.target
        is UpdateUiState.WaitingForParent -> state.target
        is UpdateUiState.WaitingForWifi -> state.target
        is UpdateUiState.PendingConfirmation -> state.target
        else -> null
    }
}
