package dev.walcott.enforcement

import android.app.AppOpsManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The permission check that decides whether budgets can count down at all, read against the
 * real platform rather than a stub.
 *
 * It is deliberately split in two: the enforcement side treats "couldn't tell" as NOT granted
 * (so a quirk can never hand out unlimited time), and the reporting side treats it as granted
 * (so a quirk can never raise a false alarm on the parent's phone). Asserting that split needs
 * the actual AppOpsManager, because the whole point is what happens when the platform is odd.
 */
@RunWith(AndroidJUnit4::class)
class UsageAccessDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** What the platform really says, read the same way the product reads it. */
    private fun platformSaysGranted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Test
    fun both_sides_agree_with_the_platform_when_the_platform_answers() {
        // On any device that can answer — every device this app supports — the two views must
        // be the same. They only diverge on the unanswerable case below.
        val granted = platformSaysGranted()
        assertEquals(granted, UsageAccess.grantedForEnforcement(context))
        assertEquals(granted, UsageAccess.granted(context))
    }

    @Test
    fun the_enforcement_side_never_reads_more_permissive_than_the_reporting_side() {
        // The invariant that survives any platform quirk: enforcement may be stricter than what
        // is reported, never looser. Looser is the bypass — budgets that never count down while
        // the parent's screen says everything is fine.
        val forEnforcement = UsageAccess.grantedForEnforcement(context)
        val forReporting = UsageAccess.granted(context)
        assertTrue(
            "enforcement read the permission as granted while reporting did not",
            !forEnforcement || forReporting,
        )
    }
}
