package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Which preinstalled apps a family has asked to manage (see [AppPolicy.manageSystemApp]).
 *
 * The set decides what a child's phone may suspend at all, so the interesting cases are the
 * ones where a parent has touched an app WITHOUT asking for this: a limit saved on the browser
 * is the commonest thing in this product, and until now it was also the emptiest.
 */
class ManagedSystemAppsTest {

    private val browser = "com.android.chrome"
    private val video = "com.google.android.youtube"
    private val game = "com.game"

    private fun config(policies: Map<String, AppPolicy>, essentials: Set<String> = emptySet()) =
        FamilyConfig(version = 1, perAppPolicies = policies, essentialPackages = essentials)

    @Test
    fun `only the apps the family asked for by name`() {
        val config = config(
            mapOf(
                browser to AppPolicy(manageSystemApp = true),
                // A limit, and nothing else: the parent set an hour on the video app and never
                // saw the switch. It is precisely this that must NOT start suspending anything.
                video to AppPolicy(dailyBudget = mapOf(DayType.SCHOOL to Duration.ofHours(1))),
                game to AppPolicy(unlimited = true),
            ),
        )
        assertEquals(setOf(browser), config.managedSystemPackages())
    }

    @Test
    fun `nothing at all is the default, and it is the empty set rather than everything`() {
        assertEquals(emptySet<String>(), config(emptyMap()).managedSystemPackages())
    }

    @Test
    fun `an essential app is never in it, whatever was saved against it`() {
        // The phone and contacts answer to no rule anywhere else in this engine, and a switch
        // saved against one — by an import, by an older build, by a mistake — must not be the
        // one place that reaches them.
        val config = config(
            mapOf(browser to AppPolicy(manageSystemApp = true)),
            essentials = setOf(browser),
        )
        assertEquals(emptySet<String>(), config.managedSystemPackages())
    }

    @Test
    fun `the switch does not by itself give the app a limit`() {
        // Opting in says "this phone MAY close it", not "close it". With no budget and no
        // window, the answer about the app is still that it has no limit — otherwise turning
        // the switch on would silently block the browser outright.
        val config = config(mapOf(browser to AppPolicy(manageSystemApp = true)))
        assertEquals(null, config.budgetFor(browser, DayType.SCHOOL))
    }
}
