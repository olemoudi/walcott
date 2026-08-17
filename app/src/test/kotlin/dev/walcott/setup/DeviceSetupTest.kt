package dev.walcott.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceSetupTest {

    /** A device with nothing wrong; each test breaks exactly what it is about. */
    private fun healthy(
        enforcingChild: Boolean = true,
        deviceOwner: Boolean = true,
    ) = DeviceFacts(
        enforcingChild = enforcingChild,
        deviceOwner = deviceOwner,
        notificationsEnabled = true,
        usageAccessGranted = true,
        accessibilityEnabled = true,
        locationPermissionGranted = true,
        locationServiceEnabled = true,
        ignoringBatteryOptimizations = true,
        locationWanted = false,
        webFilterWanted = false,
        webFilterRunning = true,
    )

    @Test
    fun `notification access is asked for only where a log was asked for`() {
        // Never implicit. A family that has not turned the log on must never be shown a card
        // offering to let this app read their messages — the surest way to have somebody grant it
        // for no reason is to put it in a list of things a phone "needs".
        assertFalse(
            DeviceRequirement.NOTIFICATION_ACCESS in
                DeviceSetup.applicable(healthy().copy(notificationAccessGranted = false)),
            "notification access was asked for on a device with no log",
        )
        assertTrue(
            DeviceRequirement.NOTIFICATION_ACCESS in
                DeviceSetup.unmet(
                    healthy().copy(notificationLogWanted = true, notificationAccessGranted = false),
                ),
            "the log was switched on and the permission it needs was never asked for",
        )
    }

    @Test
    fun `notification access is a child-side requirement, not a parent one`() {
        // The log lives on the phone that receives the notifications. A parent phone keeping one
        // would be recording the wrong person.
        val parent = healthy(enforcingChild = false, deviceOwner = false)
            .copy(notificationLogWanted = true, notificationAccessGranted = false)
        assertFalse(DeviceRequirement.NOTIFICATION_ACCESS in DeviceSetup.unmet(parent))
    }

    @Test
    fun `notification access is asked for even on a Device Owner`() {
        // The one permission a fully managed device cannot grant itself: notification listeners
        // are enabled by a human in Settings, full stop. So unlike battery optimisation, being
        // Device Owner is not a reason to skip it — it is the reason it has to be asked for.
        val owner = healthy(deviceOwner = true)
            .copy(notificationLogWanted = true, notificationAccessGranted = false)
        assertTrue(DeviceRequirement.NOTIFICATION_ACCESS in DeviceSetup.unmet(owner))
    }

    @Test
    fun `a healthy device asks for nothing`() {
        assertTrue(DeviceSetup.unmet(healthy()).isEmpty())
        assertTrue(DeviceSetup.unmet(healthy(enforcingChild = false, deviceOwner = false)).isEmpty())
    }

    @Test
    fun `notifications are asked for on any device, and come first`() {
        val parent = DeviceSetup.unmet(
            healthy(enforcingChild = false, deviceOwner = false).copy(notificationsEnabled = false),
        )
        assertEquals(listOf(DeviceRequirement.NOTIFICATIONS), parent)
        // First even when other things are also wrong: nothing else can be reported without it.
        val child = DeviceSetup.unmet(
            healthy().copy(notificationsEnabled = false, usageAccessGranted = false),
        )
        assertEquals(DeviceRequirement.NOTIFICATIONS, child.first())
    }

    @Test
    fun `enforcement requirements never appear on a parent phone`() {
        val parent = healthy(enforcingChild = false, deviceOwner = false).copy(
            usageAccessGranted = false,
            accessibilityEnabled = false,
            webFilterWanted = true,
            webFilterRunning = false,
            locationWanted = true,
            locationPermissionGranted = false,
            locationServiceEnabled = false,
        )
        // A parent phone enforces nothing, so none of that applies to it — only battery does.
        assertEquals(emptyList<DeviceRequirement>(), DeviceSetup.unmet(parent))
    }

    @Test
    fun `usage access is asked for on any enforcing child`() {
        assertTrue(DeviceRequirement.USAGE_ACCESS in DeviceSetup.unmet(healthy().copy(usageAccessGranted = false)))
        assertTrue(
            DeviceRequirement.USAGE_ACCESS in
                DeviceSetup.unmet(healthy(deviceOwner = false).copy(usageAccessGranted = false)),
        )
    }

    @Test
    fun `the accessibility blocker is only asked for where it is the one doing the blocking`() {
        // Device Owner blocks by suspending packages; the service would be redundant there.
        assertFalse(
            DeviceRequirement.ACCESSIBILITY in
                DeviceSetup.unmet(healthy(deviceOwner = true).copy(accessibilityEnabled = false)),
        )
        assertTrue(
            DeviceRequirement.ACCESSIBILITY in
                DeviceSetup.unmet(healthy(deviceOwner = false).copy(accessibilityEnabled = false)),
        )
    }

    @Test
    fun `location is only asked for by a family that turned tracking on`() {
        val off = healthy(deviceOwner = false).copy(
            locationWanted = false,
            locationPermissionGranted = false,
            locationServiceEnabled = false,
        )
        assertTrue(DeviceSetup.unmet(off).isEmpty())

        val on = off.copy(locationWanted = true)
        assertEquals(
            listOf(DeviceRequirement.LOCATION_PERMISSION, DeviceRequirement.LOCATION_SERVICE),
            DeviceSetup.unmet(on),
        )
    }

    @Test
    fun `a Device Owner is never asked for the location permission it force-grants`() {
        val facts = healthy(deviceOwner = true).copy(
            locationWanted = true,
            locationPermissionGranted = false,
            locationServiceEnabled = false,
        )
        // The system switch still needs a human; the permission does not.
        assertEquals(listOf(DeviceRequirement.LOCATION_SERVICE), DeviceSetup.unmet(facts))
    }

    @Test
    fun `the web filter is only asked for when the rules define one`() {
        assertTrue(DeviceSetup.unmet(healthy().copy(webFilterRunning = false)).isEmpty())
        assertEquals(
            listOf(DeviceRequirement.WEB_FILTER),
            DeviceSetup.unmet(healthy().copy(webFilterWanted = true, webFilterRunning = false)),
        )
    }

    @Test
    fun `critical requirements are listed before the merely degrading ones`() {
        val facts = healthy(deviceOwner = false).copy(
            ignoringBatteryOptimizations = false,
            usageAccessGranted = false,
        )
        val unmet = DeviceSetup.unmet(facts)
        assertEquals(DeviceRequirement.USAGE_ACCESS, unmet.first())
        assertEquals(DeviceRequirement.BATTERY_OPTIMIZATION, unmet.last())
    }

    @Test
    fun `dismissing hides a nudge from the home but not from the list`() {
        val unmet = DeviceSetup.unmet(
            healthy(deviceOwner = false).copy(usageAccessGranted = false, ignoringBatteryOptimizations = false),
        )
        assertEquals(2, unmet.size)
        val nag = DeviceSetup.toNag(unmet, setOf(DeviceRequirement.USAGE_ACCESS.key))
        assertEquals(listOf(DeviceRequirement.BATTERY_OPTIMIZATION), nag)
    }

    @Test
    fun `a dismissal dies with the outage it was about`() {
        val dismissed = setOf(DeviceRequirement.USAGE_ACCESS.key, DeviceRequirement.BATTERY_OPTIMIZATION.key)
        // Usage access is fixed; battery is still wrong.
        val unmet = DeviceSetup.unmet(healthy(deviceOwner = false).copy(ignoringBatteryOptimizations = false))
        val surviving = DeviceSetup.survivingDismissals(unmet, dismissed)
        assertEquals(setOf(DeviceRequirement.BATTERY_OPTIMIZATION.key), surviving)
        // So when usage access breaks again, it is nagged about rather than silently hidden.
        val later = DeviceSetup.unmet(
            healthy(deviceOwner = false).copy(usageAccessGranted = false, ignoringBatteryOptimizations = false),
        )
        assertEquals(listOf(DeviceRequirement.USAGE_ACCESS), DeviceSetup.toNag(later, surviving))
    }

    @Test
    fun `a device owner is not asked to change what the system will not let it change`() {
        // Settings shows "Battery optimization not available" for the app that owns the device:
        // the card sent the child to a screen with no switch on it.
        val owner = healthy(deviceOwner = true).copy(ignoringBatteryOptimizations = false)
        assertEquals(emptyList<DeviceRequirement>(), DeviceSetup.unmet(owner))
        // Everywhere the switch does exist — a parent's phone, an accessibility-only child —
        // it is still asked for, and that is where it matters most (the catch-up poll).
        val parent = healthy(enforcingChild = false, deviceOwner = false)
            .copy(ignoringBatteryOptimizations = false)
        assertEquals(listOf(DeviceRequirement.BATTERY_OPTIMIZATION), DeviceSetup.unmet(parent))
        val accessibilityChild = healthy(deviceOwner = false).copy(ignoringBatteryOptimizations = false)
        assertTrue(DeviceRequirement.BATTERY_OPTIMIZATION in DeviceSetup.unmet(accessibilityChild))
    }

    @Test
    fun `requirement keys are stable and distinct`() {
        val keys = DeviceRequirement.entries.map { it.key }
        assertEquals(keys.distinct(), keys)
        assertEquals("usage_access", DeviceRequirement.USAGE_ACCESS.key)
        assertEquals("battery_optimization", DeviceRequirement.BATTERY_OPTIMIZATION.key)
    }

    @Test
    fun `a key round-trips, and one from a newer build is simply unknown`() {
        DeviceRequirement.entries.forEach { assertEquals(it, DeviceRequirement.byKey(it.key)) }
        assertEquals(null, DeviceRequirement.byKey("something_a_later_version_added"))
    }

    // --- What the guided setup walks through ---

    @Test
    fun `everything that applies is listed whether or not it is satisfied`() {
        // A healthy Device Owner child still HAS requirements — it just meets them. The guided
        // setup needs that list to tell "this phone is ready" from "nobody has looked".
        val healthy = healthy(deviceOwner = true)
        assertEquals(
            listOf(DeviceRequirement.NOTIFICATIONS, DeviceRequirement.USAGE_ACCESS),
            DeviceSetup.applicable(healthy),
        )
        assertTrue(DeviceSetup.unmet(healthy).isEmpty())
    }

    @Test
    fun `applicability follows the same rules on a broken device as on a healthy one`() {
        // The property that matters: whether something is ASKED for cannot depend on whether it
        // happens to be granted, or the summary would list a different set each time.
        val states = listOf(
            healthy(),
            healthy(deviceOwner = false),
            healthy(enforcingChild = false, deviceOwner = false),
            healthy().copy(locationWanted = true, webFilterWanted = true),
            healthy(deviceOwner = false).copy(locationWanted = true, webFilterWanted = true),
        )
        states.forEach { facts ->
            val broken = facts.copy(
                notificationsEnabled = false,
                usageAccessGranted = false,
                accessibilityEnabled = false,
                locationPermissionGranted = false,
                locationServiceEnabled = false,
                ignoringBatteryOptimizations = false,
                webFilterRunning = false,
            )
            assertEquals(DeviceSetup.applicable(facts), DeviceSetup.applicable(broken))
            // And with everything broken, "unmet" is exactly "applicable".
            assertEquals(DeviceSetup.applicable(broken), DeviceSetup.unmet(broken))
        }
    }

    @Test
    fun `unmet is applicable minus what is satisfied`() {
        val facts = healthy(deviceOwner = false).copy(
            locationWanted = true,
            usageAccessGranted = false,
            ignoringBatteryOptimizations = false,
        )
        val applicable = DeviceSetup.applicable(facts)
        assertEquals(applicable.filterNot { DeviceSetup.satisfied(facts, it) }, DeviceSetup.unmet(facts))
        assertTrue(DeviceSetup.satisfied(facts, DeviceRequirement.NOTIFICATIONS))
        assertFalse(DeviceSetup.satisfied(facts, DeviceRequirement.USAGE_ACCESS))
    }
}
