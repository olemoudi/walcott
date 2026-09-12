package dev.walcott.net

import dev.walcott.net.UnderlyingNetworks.Candidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The ranking that decides where every lookup on the phone goes.
 *
 * Below Android 12 the network callback reports every network that matches, so without this the
 * last one to speak won — and a phone holding Wi-Fi and mobile data at once would ask whichever
 * had most recently changed.
 */
class UnderlyingNetworksTest {

    @Test
    fun `a network the platform vouches for beats one it does not, whatever carries it`() {
        assertEquals(
            "mobile",
            UnderlyingNetworks.best(
                listOf(
                    Candidate("wifi", validated = false, wifi = true),
                    Candidate("mobile", validated = true, cellular = true),
                ),
            ),
        )
    }

    @Test
    fun `with nothing else to go on it is a wire, then Wi-Fi, then mobile`() {
        val all = listOf(
            Candidate("mobile", validated = true, cellular = true),
            Candidate("wifi", validated = true, wifi = true),
            Candidate("wire", validated = true, ethernet = true),
        )
        assertEquals("wire", UnderlyingNetworks.best(all))
        assertEquals("wifi", UnderlyingNetworks.best(all - all.last()))
        assertEquals("mobile", UnderlyingNetworks.best(listOf(all.first())))
    }

    @Test
    fun `an unvalidated network is still better than no network at all`() {
        // The bug this pins: filtering candidates BY validation left a weak Wi-Fi the phone had
        // stopped vouching for with no answer, so the filter kept asking a network that was gone.
        assertEquals(
            "wifi",
            UnderlyingNetworks.best(listOf(Candidate("wifi", validated = false, wifi = true))),
        )
    }

    @Test
    fun `a transport this build has no opinion about is last, not excluded`() {
        assertEquals(
            "bluetooth",
            UnderlyingNetworks.best(listOf(Candidate("bluetooth", validated = true))),
        )
        assertEquals(
            "wifi",
            UnderlyingNetworks.best(
                listOf(
                    Candidate("bluetooth", validated = true),
                    Candidate("wifi", validated = true, wifi = true),
                ),
            ),
        )
    }

    @Test
    fun `no networks is no answer rather than a guess`() {
        assertNull(UnderlyingNetworks.best(emptyList<Candidate<String>>()))
    }
}
