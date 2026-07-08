package dev.walcott.enforcement

import android.content.Context
import dev.walcott.sync.EnforcementStatus

/** Which enforcement backend is actually active on this device, for reporting to the parent. */
object EnforcementBackends {
    fun status(context: Context): String = when {
        Enforcer(context).isDeviceOwner() -> EnforcementStatus.DEVICE_OWNER
        AppBlockerService.isEnabled(context) -> EnforcementStatus.ACCESSIBILITY
        else -> EnforcementStatus.NONE
    }
}
