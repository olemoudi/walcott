package dev.walcott

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import dev.walcott.enforcement.Enforcer
import dev.walcott.enforcement.EnforcementService
import dev.walcott.sync.SyncNotifications
import dev.walcott.ui.WalcottApp
import dev.walcott.ui.WalcottViewModel
import dev.walcott.update.UpdateWorker
import dev.walcott.ui.theme.WalcottTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        val app = application as WalcottApplication
        // Enforcement only runs on devices that enforce rules; a parent phone that was
        // already running the service (pre-mode versions) gets it stopped here.
        lifecycleScope.launch {
            if (app.identityStore.current().enforcesLocally) {
                EnforcementService.start(this@MainActivity)
                requestLocationPermissionIfNeeded()
            } else {
                EnforcementService.stop(this@MainActivity)
            }
        }

        setContent {
            WalcottTheme {
                val vm: WalcottViewModel = viewModel(
                    factory = WalcottViewModel.Factory(app.repository, app.syncManager),
                )
                val deviceOwner = remember { Enforcer(this).isDeviceOwner() }
                // Deep-link target from a notification tap (e.g. "new app" -> Apps screen).
                var dest by remember { mutableStateOf(intent?.getStringExtra(SyncNotifications.EXTRA_DEST)) }
                LaunchedEffect(newIntentDest) { newIntentDest?.let { dest = it } }
                WalcottApp(vm, deviceOwner, startDest = dest, onDestConsumed = { dest = null })
            }
        }
    }

    // Notification taps while the activity is alive arrive here (SINGLE_TOP), not via a new onCreate.
    private var newIntentDest by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newIntentDest = intent.getStringExtra(SyncNotifications.EXTRA_DEST)
    }

    override fun onResume() {
        super.onResume()
        // Catch up on updates whenever the app regains focus; throttled internally.
        UpdateWorker.runIfStale(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * On the child, Device Owner force-grants location ([dev.walcott.location.LocationPolicy]); the
     * non-owner fallback can't, so we must ask at runtime or the periodic location check-in silently
     * never starts. Re-asks each launch until granted; recovery after a hard denial is the in-app
     * nudge on the child screen.
     */
    private fun requestLocationPermissionIfNeeded() {
        if (Enforcer(this).isDeviceOwner()) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
