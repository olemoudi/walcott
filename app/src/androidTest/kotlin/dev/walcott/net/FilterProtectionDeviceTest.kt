package dev.walcott.net

import android.app.admin.DevicePolicyManager
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walcott.WalcottAdminReceiver
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two things that decide whether the DNS filter — and with it the bedtime curfew, which is
 * the same filter (see [dev.walcott.rules.Curfew]) — can be walked around from Settings.
 *
 * Neither can be answered by a JVM test: both are questions about what the operating system does
 * when a Device Owner asks. And a filter that is running, reports itself healthy and sees nothing
 * at all is the worst failure this feature has, because every screen says it is fine.
 *
 * Needs Walcott to be Device Owner; skips cleanly otherwise.
 */
@RunWith(AndroidJUnit4::class)
class FilterProtectionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = WalcottAdminReceiver.componentName(context)
    private val users = context.getSystemService(UserManager::class.java)

    @Before
    fun onlyOnADeviceThatCanAnswer() {
        assumeTrue("not Device Owner on this device", dpm.isDeviceOwnerApp(context.packageName))
    }

    @After
    fun leaveTheDeviceAsItWas() {
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS) }
        runCatching { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        runCatching { VpnController.apply(context, false) }
    }

    @Test
    fun theSystemAcceptsLockingPrivateDns() {
        // The restriction the "Protect the web filter" switch now carries. If the platform ever
        // stops honouring it, the switch keeps saying it is on and the setting stays reachable.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        assertTrue(
            "the system did not take DISALLOW_CONFIG_PRIVATE_DNS from a Device Owner",
            users.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS),
        )
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
    }

    @Test
    fun bringingTheFilterUpTakesThePhoneOffAPrivateResolver() {
        // The bypass, set up exactly as a child would leave it: Private DNS pointing at a
        // resolver of their own, which sends every lookup out over TLS on port 853 — past the
        // tun, past the filter, past bedtime. Bringing the filter up has to undo it, or the
        // filter is decoration.
        val set = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, PRIVATE_RESOLVER)
        assumeTrue(
            "this device would not accept a strict private DNS host ($set), so there is nothing to undo",
            set == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR,
        )
        assumeTrue(
            "the host did not stick",
            dpm.getGlobalPrivateDnsMode(admin) == DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
        )

        VpnController.apply(context, true)

        assertNotEquals(
            "the filter came up with the phone still resolving through its own DoT server",
            DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
            dpm.getGlobalPrivateDnsMode(admin),
        )
    }

    private companion object {
        /** A DoT resolver that exists, so the platform's own validation cannot be what fails. */
        const val PRIVATE_RESOLVER = "dns.google"
    }
}
