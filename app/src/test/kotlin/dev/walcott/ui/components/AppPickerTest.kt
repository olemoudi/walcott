package dev.walcott.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a search in an app picker finds.
 *
 * Pure, and worth pinning down rather than eyeballing: every picker in the app now shares this
 * rule, so a change here changes the parent's web filter, the notification log, the bonus dialog
 * and the child's "ask for more time" sheet at once.
 */
class AppPickerTest {

    private val apps = listOf(
        PickableApp("com.google.android.youtube", "YouTube"),
        PickableApp("com.whatsapp", "WhatsApp"),
        PickableApp("com.example.banco", "Banco Example"),
        PickableApp("com.sneaky.notapproved", ""),
    )

    @Test
    fun `an empty query asks for everything`() {
        assertEquals(apps, matching(apps, ""))
        assertEquals(apps, matching(apps, "   "))
    }

    @Test
    fun `it matches the name, anywhere in it and in any case`() {
        assertEquals(listOf(apps[0]), matching(apps, "youtube"))
        assertEquals(listOf(apps[0]), matching(apps, "TUB"))
        assertEquals(listOf(apps[2]), matching(apps, "banco"))
    }

    @Test
    fun `it matches the package too`() {
        // The half a reader would not assume, and the one a parent uses: rules and reports print
        // package names, so somebody who has just read one types what they saw.
        assertEquals(listOf(apps[3]), matching(apps, "com.sneaky"))
        assertTrue(apps[1] in matching(apps, "whatsapp"))
    }

    @Test
    fun `a query nothing answers comes back empty rather than unfiltered`() {
        // The failure that would be worst here: a picker that quietly shows everything when a
        // search finds nothing reads as "these all match", and the wrong app gets tapped.
        assertEquals(emptyList<PickableApp>(), matching(apps, "spotify"))
    }

    @Test
    fun `surrounding spaces are the typist's, not the query's`() {
        assertEquals(listOf(apps[1]), matching(apps, "  whats  ".trimEnd()))
    }

    @Test
    fun `an app with no resolvable name is still findable, and shows its package`() {
        val nameless = apps[3]
        assertEquals(nameless.packageName, nameless.display)
        assertEquals(listOf(nameless), matching(apps, "notapproved"))
    }

    @Test
    fun `the generic form filters anything with a name and a package`() {
        data class Row(val pkg: String, val name: String)
        val rows = listOf(Row("com.a", "Alpha"), Row("com.b", "Beta"))
        assertEquals(
            listOf(rows[1]),
            matching(rows, "bet", label = { it.name }, packageName = { it.pkg }),
        )
    }
}
