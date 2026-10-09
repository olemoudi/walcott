package dev.walcott.install

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.walcott.WalcottApplication
import kotlinx.coroutines.launch

/**
 * Invisible tap target for a parent-pushed install (notification or child-home card). The
 * install window opens when the command ARRIVES, but the child may only engage much later —
 * so re-open it here, at tap time, and only then forward to the app's Play page. Without
 * this, a tap after the window expired opens Play just for the install to be blocked.
 */
class InstallPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WalcottApplication
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            // Re-opens only while THIS app's approval is still pending — the one tapped, not
            // whichever was approved last; a stale tap after the install completed just lands on
            // the app's Play page ("Open"), with the block armed.
            app.syncManager.reopenInstallWindow(pkg)
            runCatching { startActivity(PlayIntents.storePage(this@InstallPromptActivity, pkg)) }
            finish()
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
