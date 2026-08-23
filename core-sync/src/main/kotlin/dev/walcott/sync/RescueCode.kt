package dev.walcott.sync

import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A six-digit code that opens a child's phone for an hour with no network at all.
 *
 * The hole it fills is the worst one this product has. Everything else a parent can do arrives
 * over the relay; when there is no relay — no data, no Wi-Fi, a phone abroad, a server having a
 * bad day — the only vocabulary left is the parent's PIN, typed into the child's phone. That PIN
 * is not a password for one favour: it is the credential that releases the device for ever, and
 * typing it in front of the person it exists to keep out is spending it. Worse, a PIN the parent
 * has just changed is not the PIN the child's phone answers to until the new policy arrives,
 * which is exactly what a phone with no network has not got.
 *
 * And there is a case with no way out at all: a child failing CLOSED (usage access revoked, or a
 * clock the server disagrees with) is a phone where nothing opens, and if it also has no data,
 * nothing can reach it to say otherwise.
 *
 * So: HMAC-SHA256 over the family key — which both phones already hold, so there is no new
 * secret, nothing to enrol and nothing to lose — truncated to six digits the way an
 * authenticator app does it (RFC 4226), over a thirty-minute slot. The parent's screen shows the
 * code with a countdown; the child types it into their own home screen, with no PIN, and the
 * phone opens for as long as that code was worth.
 *
 * Three properties this has to have, and each is a line of code that looks optional:
 *
 *  - **A code is spent once.** The device remembers the newest slot it has accepted and refuses
 *    anything not newer, so a code overheard, photographed or remembered is worth nothing twice —
 *    including within the half hour it is otherwise still valid.
 *  - **A slow clock cannot mine it.** Slots are derived from time, so a child who moves the clock
 *    back to a slot they have already seen gets a code they have already spent (see above), and
 *    one who moves it forward is guessing at codes for a slot the parent has not read out.
 *  - **Guessing is worthless.** Six digits, three actions, three slots is about nine chances in a
 *    million per attempt, and attempts are rate-limited by the same escalating lockout every
 *    other code entry in this app uses.
 *
 * Pure and Android-free, so all of that is decided in tests rather than on a phone.
 */
object RescueCode {

    /**
     * How long one code is worth reading out for.
     *
     * Long enough to be said over a bad phone line and typed by somebody upset; short enough
     * that a code seen over a shoulder is not a standing key. The verifier accepts the
     * neighbouring slots too (see [verify]), so the real window is between thirty minutes and an
     * hour and a half — which is also what keeps two phones with slightly different clocks
     * talking to each other.
     */
    const val SLOT_MINUTES = 30

    private const val SLOT_MS = SLOT_MINUTES * 60 * 1000L

    /** Digits in a code. Six, like every other code a person is asked to read out loud. */
    const val DIGITS = 6

    /** Open everything for an hour — the answer to "I need my phone and you are not here". */
    const val ACTION_OPEN_1H = "open60"

    /** The same for an afternoon, for the trip, the hospital, the day it is not going to be fixed. */
    const val ACTION_OPEN_3H = "open180"

    /** Half an hour in which this phone may install something. */
    const val ACTION_INSTALL = "install30"

    /** Every action a code can carry, in the order a parent is offered them. */
    val ACTIONS = listOf(ACTION_OPEN_1H, ACTION_OPEN_3H, ACTION_INSTALL)

    /** How long [action] is worth, in minutes; 0 for an action this build does not know. */
    fun grantMinutes(action: String): Int = when (action) {
        ACTION_OPEN_1H -> 60
        ACTION_OPEN_3H -> 180
        ACTION_INSTALL -> 30
        else -> 0
    }

    /** Whether [action] opens the rules, as opposed to opening the door to an install. */
    fun opensRules(action: String): Boolean = action == ACTION_OPEN_1H || action == ACTION_OPEN_3H

    /** The slot [nowMs] falls in. Floor division, so it keeps counting the same way before 1970. */
    fun slotOf(nowMs: Long): Long = Math.floorDiv(nowMs, SLOT_MS)

    /** When [slot] stops being the current one, so a screen can count down to it. */
    fun slotEndsAtMs(slot: Long): Long = (slot + 1) * SLOT_MS

    /** The code for [action] in [slot], as six digits with the leading zeros kept. */
    fun codeFor(familyKey: SecretKey, action: String, slot: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(familyKey.encoded, "HmacSHA256"))
        val digest = mac.doFinal("$action|$slot".toByteArray(Charsets.UTF_8))
        // RFC 4226 dynamic truncation: the low nibble of the last byte picks four bytes, the top
        // bit of those is dropped so the number is positive on every platform's signed int.
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(DIGITS) { mod *= 10 }
        return (binary % mod).toString().padStart(DIGITS, '0')
    }

    /**
     * Whether a rescue that was granted until BOTH deadlines is still running.
     *
     * Two clocks, and each covers the other's blind spot. The monotonic one
     * ([android.os.SystemClock.elapsedRealtime]) cannot be edited, so winding the phone back
     * cannot stretch an hour into an evening. The wall clock cannot be reset by a reboot, which
     * is exactly what the monotonic one does — it restarts at zero, and a stored deadline would
     * look valid again on the other side of a restart. The rescue ends at whichever comes first,
     * so neither trick buys a minute.
     */
    fun isRunning(untilWallMs: Long, untilElapsedMs: Long, nowWallMs: Long, nowElapsedMs: Long): Boolean =
        untilWallMs > 0 && untilElapsedMs > 0 && nowWallMs < untilWallMs && nowElapsedMs < untilElapsedMs

    /** How much of a running rescue is left, by whichever clock has less of it. 0 when it is over. */
    fun remainingMs(untilWallMs: Long, untilElapsedMs: Long, nowWallMs: Long, nowElapsedMs: Long): Long {
        if (!isRunning(untilWallMs, untilElapsedMs, nowWallMs, nowElapsedMs)) return 0
        return minOf(untilWallMs - nowWallMs, untilElapsedMs - nowElapsedMs)
    }

    /** A code that was accepted: what it buys, and the slot it belongs to. */
    data class Accepted(val action: String, val slot: Long)

    /**
     * What [entered] buys at [nowMs], or null when it buys nothing.
     *
     * [lastUsedSlot] is the newest slot this device has already accepted a code from; anything
     * not newer is refused, which is what makes a code single-use. Pass Long.MIN_VALUE on a
     * device that has never accepted one.
     *
     * Whitespace is forgiven because this number is read out loud and typed by somebody who is
     * probably not having a good day. Nothing else is.
     */
    fun verify(
        familyKey: SecretKey,
        entered: String,
        nowMs: Long,
        lastUsedSlot: Long,
    ): Accepted? {
        val digits = entered.filter { it.isDigit() }
        if (digits.length != DIGITS) return null
        val current = slotOf(nowMs)
        // The neighbours as well as the current slot: a code read out at 10:29 is typed at 10:31,
        // and two phones never agree about the minute.
        for (slot in (current - 1)..(current + 1)) {
            if (slot <= lastUsedSlot) continue
            for (action in ACTIONS) {
                if (constantTimeEquals(codeFor(familyKey, action, slot), digits)) {
                    return Accepted(action, slot)
                }
            }
        }
        return null
    }

    /**
     * Compares without leaking WHERE two codes differ through how long the comparison took.
     *
     * Belt and braces on a phone rather than a network service — but the whole point of this
     * feature is that it runs on the device of the person it is keeping out, so "they cannot
     * measure it" is not an assumption worth making.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
