package dev.walcott

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class WalcottAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, WalcottAdminReceiver::class.java)
    }
}
