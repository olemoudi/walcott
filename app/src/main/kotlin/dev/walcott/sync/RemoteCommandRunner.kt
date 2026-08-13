package dev.walcott.sync

import android.content.Context
import dev.walcott.data.WalcottRepository
import dev.walcott.debug.DebugLog
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.enforcement.EnforcementService
import dev.walcott.enforcement.UsageAccess
import dev.walcott.install.PlayIntents
import dev.walcott.location.LocationPolicy
import dev.walcott.location.LocationSampler
import dev.walcott.update.UpdateCheckOutcome
import dev.walcott.update.Updater
import kotlinx.coroutines.flow.first

/**
 * Runs the parent's remote fixes on the child device.
 *
 * Only failures a parent can actually repair from a distance live here. Permissions that
 * require someone to tap through a system screen (usage access, network location) can't be
 * granted remotely at all, so [RemoteAction.REQUEST_PERMISSIONS] raises a guided notification
 * on the child instead of pretending to fix them.
 *
 * Every action returns a [CommandAck] rather than throwing: the parent needs to see that a
 * command failed just as much as that it succeeded.
 */
class RemoteCommandRunner(
    private val context: Context,
    private val repository: WalcottRepository,
    /** Opens the tight, self-closing install window for a parent-pushed install. */
    private val openInstallForPush: suspend (pkg: String, commandId: String, label: String) -> Unit =
        { _, _, _ -> },
    /** Publishes the health report a [RemoteAction.DIAGNOSE] asks for. */
    private val publishDiagnostics: suspend () -> Unit = {},
    /** Refuses the child's pending emergency release (see [RemoteAction.DENY_PANIC]). */
    private val denyPanic: suspend (requestId: String) -> Pair<Boolean, String> = { false to "unsupported" },
    /** Removes an app and keeps retrying until it is gone (see [RemoteAction.UNINSTALL_APP]). */
    private val removeApp: suspend (pkg: String) -> Boolean = { false },
    /** Lets a quarantined app stay (see [RemoteAction.ALLOW_APP]). */
    private val allowApp: suspend (pkg: String) -> Boolean = { false },
) {

    suspend fun run(command: RemoteCommand): CommandAck {
        DebugLog.i(TAG, "running remote command ${command.action} (${command.id})")
        val result = runCatching {
            when (command.action) {
                RemoteAction.UPDATE_NOW -> updateNow()
                RemoteAction.REAPPLY_POLICY -> reapplyPolicy()
                RemoteAction.REQUEST_PERMISSIONS -> requestPermissions()
                RemoteAction.INSTALL_APP -> installApp(command.arg, command.id, command.label)
                RemoteAction.UNINSTALL_APP -> uninstallApp(command.arg)
                RemoteAction.ALLOW_APP -> allowQuarantined(command.arg)
                RemoteAction.DIAGNOSE -> diagnose()
                RemoteAction.DENY_PANIC -> denyPanic(command.arg)
                // Forward compatibility: a newer parent may know actions this build doesn't.
                else -> false to "unsupported"
            }
        }.getOrElse { error ->
            DebugLog.e(TAG, "remote command ${command.action} threw", error)
            false to (error.javaClass.simpleName)
        }
        DebugLog.i(TAG, "remote command ${command.action} -> ok=${result.first} ${result.second}")
        return CommandAck(
            id = command.id,
            action = command.action,
            ok = result.first,
            detail = result.second,
            completedAtMs = System.currentTimeMillis(),
            arg = command.arg,
        )
    }

    /**
     * Forces the self-update. Reports the outcome verbatim so a child stuck on an old build
     * is diagnosable from the parent's phone (network failure vs a rejected install).
     */
    private suspend fun updateNow(): Pair<Boolean, String> =
        // force=true: a parent explicitly asking to update now overrides the Wi-Fi-only
        // policy AND the canary gate (so WAITING_FOR_PARENT can't actually occur here).
        when (Updater(context).checkAndUpdate(force = true)) {
            UpdateCheckOutcome.UP_TO_DATE -> true to "up_to_date"
            UpdateCheckOutcome.INSTALL_STARTED -> true to "installing"
            UpdateCheckOutcome.TRANSIENT_FAILURE -> false to "download_failed"
            UpdateCheckOutcome.INSTALL_FAILURE -> false to "install_failed"
            UpdateCheckOutcome.WAITING_FOR_PARENT -> true to "waiting_parent"
        }

    /**
     * Re-asserts everything enforcement depends on: the location grant, the Device Owner
     * restrictions, and the service itself. This is the fix for a child that stopped
     * enforcing after a crash, a forced stop, or an OEM battery-saver kill.
     */
    private suspend fun reapplyPolicy(): Pair<Boolean, String> {
        LocationPolicy.ensureEnforced(context)
        val restrictions = repository.settingsFlow.first().deviceRestrictions
        DeviceRestrictions.apply(context, restrictions, installExemptUntilMs = 0)
        EnforcementService.start(context)
        return true to "reapplied"
    }

    /**
     * Assisted Play install: opens the tight install window, puts the child in front of the
     * app's Play page and leaves one thing to do — tap Install.
     *
     * Play cannot be driven silently by anyone who isn't a managed-Play EMM, so that last tap
     * is the honest ceiling. What CAN be removed is the part where the child has to work out
     * that Play is where to go: the page opens by itself when the phone is in someone's hands
     * ([openPlayNow]), and the notification stays as the way back when it isn't.
     *
     * "opened" only means the prompt reached the device; a second "installed" ack follows from
     * [SyncManager.closeInstallWindow] when the package actually lands, and anything else that
     * lands instead is quarantined by [SyncManager.reconcileInstalls].
     */
    private suspend fun installApp(pkg: String, commandId: String, label: String): Pair<Boolean, String> {
        if (pkg.isBlank()) return false to "no_package"
        if (runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess) {
            return true to RemoteAction.DETAIL_ALREADY_INSTALLED
        }
        openInstallForPush(pkg, commandId, label)
        InstallPromptNotifications.notify(context, pkg, label)
        openPlayNow(pkg)
        return true to RemoteAction.DETAIL_INSTALL_OPENED
    }

    /**
     * Opens the app's Play page right now, if there is anyone there to see it.
     *
     * Only with the screen on and unlocked: an activity launched into a locked phone is a
     * window nobody saw, sitting on top of whatever the child opens next. As Device Owner the
     * background start is allowed; when it isn't, the notification and the home card are the
     * fallback and nothing is lost.
     */
    private fun openPlayNow(pkg: String) {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) return
        runCatching { context.startActivity(PlayIntents.storePage(context, pkg)) }
            .onFailure { DebugLog.w(TAG, "could not open Play for $pkg: ${it.javaClass.simpleName}") }
    }

    /**
     * Removes an app from this device. The removal is not confirmed here: a Device Owner
     * uninstall is silent but asynchronous, and it can be refused — so the app is quarantined
     * (suspended, hence unusable immediately) and retried until it is really gone.
     */
    private suspend fun uninstallApp(pkg: String): Pair<Boolean, String> {
        if (pkg.isBlank()) return false to "no_package"
        return if (removeApp(pkg)) true to RemoteAction.DETAIL_REMOVING
        else true to RemoteAction.DETAIL_NOT_INSTALLED
    }

    /** Lets a quarantined app stay: un-suspends it and closes its case. */
    private suspend fun allowQuarantined(pkg: String): Pair<Boolean, String> {
        if (pkg.isBlank()) return false to "no_package"
        return if (allowApp(pkg)) true to RemoteAction.DETAIL_ALLOWED else false to "not_quarantined"
    }

    /** Publishes the health report; the report itself travels as its own message kind. */
    private suspend fun diagnose(): Pair<Boolean, String> {
        publishDiagnostics()
        return true to "diag_sent"
    }

    /**
     * Nudges the child through the permissions only they can grant. Reports which ones were
     * actually missing so the parent sees "nothing to fix" rather than a silent success.
     */
    private suspend fun requestPermissions(): Pair<Boolean, String> {
        // The same list the child's own home screen and its periodic self-check use, so a parent
        // tapping "Ask to fix" can't be told about a different set of problems than the ones the
        // child is looking at. It used to check three of them, with its own idea of each.
        val missing = dev.walcott.setup.DeviceSetup.unmet(dev.walcott.setup.DeviceSetupProbe.read(context))
        if (missing.isEmpty()) return true to "nothing_missing"
        missing.forEach { ChildFixNotifications.notify(context, it) }
        return true to missing.joinToString(",") { it.key }
    }

    private companion object {
        private const val TAG = "WalcottSync"
    }
}
