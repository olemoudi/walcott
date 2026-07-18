package dev.walcott.sync

import android.content.Context
import dev.walcott.data.WalcottRepository
import dev.walcott.debug.DebugLog
import dev.walcott.enforcement.DeviceRestrictions
import dev.walcott.enforcement.EnforcementService
import dev.walcott.enforcement.UsageAccess
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
    private val openInstallForPush: suspend (pkg: String) -> Unit = {},
) {

    suspend fun run(command: RemoteCommand): CommandAck {
        DebugLog.i(TAG, "running remote command ${command.action} (${command.id})")
        val result = runCatching {
            when (command.action) {
                RemoteAction.UPDATE_NOW -> updateNow()
                RemoteAction.REAPPLY_POLICY -> reapplyPolicy()
                RemoteAction.REQUEST_PERMISSIONS -> requestPermissions()
                RemoteAction.INSTALL_APP -> installApp(command.arg)
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
        )
    }

    /**
     * Forces the self-update. Reports the outcome verbatim so a child stuck on an old build
     * is diagnosable from the parent's phone (network failure vs a rejected install).
     */
    private suspend fun updateNow(): Pair<Boolean, String> =
        // force=true: a parent explicitly asking to update now overrides the Wi-Fi-only policy.
        when (Updater(context).checkAndUpdate(force = true)) {
            UpdateCheckOutcome.UP_TO_DATE -> true to "up_to_date"
            UpdateCheckOutcome.INSTALL_STARTED -> true to "installing"
            UpdateCheckOutcome.TRANSIENT_FAILURE -> false to "download_failed"
            UpdateCheckOutcome.INSTALL_FAILURE -> false to "install_failed"
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
     * Assisted Play install: opens the tight install window and prompts the child to tap
     * Install in Play. Play can't be driven silently, so this is the honest ceiling — the
     * window slams shut the moment anything installs (see EnforcementService's package
     * receiver), keeping the opportunity to sneak in an alternative app minimal.
     */
    private suspend fun installApp(pkg: String): Pair<Boolean, String> {
        if (pkg.isBlank()) return false to "no_package"
        openInstallForPush(pkg)
        InstallPromptNotifications.notify(context, pkg)
        return true to "opened"
    }

    /**
     * Nudges the child through the permissions only they can grant. Reports which ones were
     * actually missing so the parent sees "nothing to fix" rather than a silent success.
     */
    private fun requestPermissions(): Pair<Boolean, String> {
        val missing = buildList {
            if (!UsageAccess.granted(context)) add(ChildFixNotifications.FIX_USAGE_ACCESS)
            if (!LocationSampler(context).networkProviderEnabled()) add(ChildFixNotifications.FIX_NETWORK_LOCATION)
            if (!LocationPolicy.hasFineLocation(context)) add(ChildFixNotifications.FIX_LOCATION_PERMISSION)
        }
        if (missing.isEmpty()) return true to "nothing_missing"
        missing.forEach { ChildFixNotifications.notify(context, it) }
        return true to missing.joinToString(",")
    }

    private companion object {
        private const val TAG = "WalcottSync"
    }
}
