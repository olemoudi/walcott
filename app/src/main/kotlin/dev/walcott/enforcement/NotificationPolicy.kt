package dev.walcott.enforcement

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog

/**
 * Makes sure the child's phone can actually show what Walcott has to say, without anyone having
 * to agree to it first.
 *
 * On Android 13+ posting a notification needs a runtime permission, and until now the child's
 * device asked for it like any app would — a dialog someone taps through during setup, or
 * doesn't. Everything the child is ever told goes through that permission: the "one minute left"
 * banner, the answer to a request for more time, the notice that an emergency release was
 * refused. A child who declined the prompt (or a setup nobody finished) gets a phone that
 * enforces rules silently and explains none of them.
 *
 * A Device Owner does not have to ask. [DevicePolicyManager.setPermissionGrantState] settles it
 * the same way [dev.walcott.location.LocationPolicy] settles location, and for the same reason:
 * a parent in another building cannot fix a prompt that was dismissed months ago.
 *
 * The grant is handed back to DEFAULT immediately, which leaves the permission granted but drops
 * the admin's hold on it — the child can still turn notifications off in Settings if they really
 * want to, and the parent is told when they do ([ChildHealthCheck]). The point is that silence
 * has to be a decision somebody made, not the default state of a phone nobody finished setting
 * up. Anywhere that isn't a Device Owner — the parent's own phone, an accessibility-only child —
 * this does nothing and the normal prompt still applies.
 */
object NotificationPolicy {

    private const val TAG = "WalcottNotifications"

    /** Grants POST_NOTIFICATIONS on a Device Owner child. No-op everywhere else. */
    fun ensureGranted(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching {
            val admin = WalcottAdminReceiver.componentName(context)
            dpm.setPermissionGrantState(
                admin, context.packageName, permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            // Straight back to DEFAULT: granted, but no longer admin-held, so the child keeps
            // the ability to change their mind in Settings.
            dpm.setPermissionGrantState(
                admin, context.packageName, permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
            )
            DebugLog.i(TAG, "granted POST_NOTIFICATIONS as device owner")
        }.onFailure { DebugLog.w(TAG, "could not grant POST_NOTIFICATIONS", it) }
    }
}
