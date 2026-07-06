package dev.walcott

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.walcott.enforcement.Enforcer
import dev.walcott.enforcement.EnforcementService
import dev.walcott.ui.WalcottApp
import dev.walcott.ui.WalcottViewModel
import dev.walcott.ui.theme.WalcottTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as WalcottApplication).repository
        EnforcementService.start(this)

        setContent {
            WalcottTheme {
                val vm: WalcottViewModel = viewModel(factory = WalcottViewModel.Factory(repository))
                val deviceOwner = remember { Enforcer(this).isDeviceOwner() }
                WalcottApp(vm, deviceOwner)
            }
        }
    }
}
