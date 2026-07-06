package dev.walcott

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.walcott.enforcement.Enforcer
import dev.walcott.enforcement.EnforcementService
import dev.walcott.ui.WalcottApp
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.theme.WalcottTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val app = application as WalcottApplication
        EnforcementService.start(this)

        setContent {
            WalcottTheme {
                val vm: WalcottViewModel = viewModel(
                    factory = WalcottViewModel.Factory(app.repository, app.syncManager),
                )
                val deviceOwner = remember { Enforcer(this).isDeviceOwner() }
                WalcottApp(vm, deviceOwner)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
