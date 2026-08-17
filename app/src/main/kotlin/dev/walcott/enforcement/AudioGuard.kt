package dev.walcott.enforcement

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog

/**
 * Keeps a device's ringer audible.
 *
 * The failure this exists for has nothing to do with rules: a phone whose ringer has been switched
 * to silent — by a pocket, by a volume key held a second too long, by a person who does not know
 * what the crossed-out bell means — is a phone nobody can reach. From the other end it is
 * indistinguishable from a phone that is off, lost, or worse, and its owner has no idea anything
 * is wrong. That is worth more to a family than any limit this app enforces.
 *
 * **Re-assertion, not prohibition.** Android does have a restriction that stops the user changing
 * the volume, and it is a trap: `DISALLOW_ADJUST_VOLUME` MUTES the device ("if set, the master
 * volume will be muted"). So instead this puts the ringer back — on the system's own
 * [AudioManager.RINGER_MODE_CHANGED_ACTION] broadcast, which arrives within a moment of the change,
 * and again on every watchdog pass in case a broadcast was missed while the process was dead.
 *
 * **What it cannot fix, it reports.** Do Not Disturb silences the ringer regardless of volume, and
 * turning DND off needs notification-policy access, which only the phone's owner can grant in
 * Settings. So [dndSilencing] answers "is something still muting this phone?" and the answer
 * travels to the parent instead of being swallowed — a guard that cannot say when it is losing is
 * worse than no guard, because the family stops checking.
 */
object AudioGuard {

    private const val TAG = "WalcottAudio"

    /** What this device's ringer is doing, for the snapshot and for the person supporting it. */
    data class State(
        /** True when a call would be heard: not silent, not vibrate-only, and volume above zero. */
        val audible: Boolean,
        /** True when Do Not Disturb is filtering calls and this app cannot turn it off. */
        val dndSilencing: Boolean,
    )

    fun read(context: Context): State {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return State(audible = true, dndSilencing = false)
        val mode = runCatching { audio.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_RING) }.getOrDefault(1)
        return State(
            audible = mode == AudioManager.RINGER_MODE_NORMAL && volume > 0,
            dndSilencing = dndSilencing(context),
        )
    }

    /**
     * Puts the ringer back if it has been silenced, and returns true when it had to.
     *
     * [minPercent] is the family's floor, as a percentage of the ring stream's maximum. Only ever
     * raises: a device already louder than the floor is left alone, because somebody deliberately
     * turning it up is not a problem to be corrected.
     */
    fun enforce(context: Context, minPercent: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        var restored = false

        // A Device Owner can undo a muted master volume outright; nothing else can.
        runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setMasterVolumeMuted(WalcottAdminReceiver.componentName(context), false)
            }
        }

        val mode = runCatching { audio.ringerMode }.getOrNull()
        if (mode != null && mode != AudioManager.RINGER_MODE_NORMAL) {
            // Silent and vibrate are the same failure to somebody waiting for a call to be
            // answered. Fails quietly when DND owns the ringer — reported rather than retried.
            runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                .onSuccess { restored = true }
                .onFailure { DebugLog.w(TAG, "could not leave silent mode", it) }
        }

        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_RING) }.getOrDefault(0)
        if (max > 0) {
            val floor = floorFor(max, minPercent)
            val current = runCatching { audio.getStreamVolume(AudioManager.STREAM_RING) }.getOrDefault(floor)
            if (current < floor) {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_RING, floor, 0) }
                    .onSuccess { restored = true }
                    .onFailure { DebugLog.w(TAG, "could not raise the ring volume", it) }
            }
        }
        if (restored) DebugLog.i(TAG, "ringer put back (floor $minPercent%)")
        return restored
    }

    /**
     * The lowest ring-stream step that still counts as meeting a [minPercent] floor, given a
     * device whose stream goes up to [max].
     *
     * Rounded UP, and never below 1. Rounding down would satisfy "keep it at 80%" with 5 steps out
     * of 7, and — worse — a small enough percentage on a coarse stream would round to zero, which
     * is silence: the exact state this whole guard exists to undo. Ring streams are short (7 steps
     * on most phones, 15 on some), so the rounding direction is not a rounding detail.
     *
     * Its own function because it is the only arithmetic here, and the only part testable without
     * a phone.
     */
    fun floorFor(max: Int, minPercent: Int): Int {
        if (max <= 0) return 0
        val wanted = (max * minPercent.coerceIn(1, 100) + 99) / 100
        return wanted.coerceIn(1, max)
    }

    /** True when DND is filtering calls AND this app has no way to lift it. */
    private fun dndSilencing(context: Context): Boolean = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val filtering = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        filtering && !nm.isNotificationPolicyAccessGranted
    }.getOrDefault(false)

    /**
     * Lifts Do Not Disturb when the phone's owner has granted notification-policy access. Silent
     * no-op otherwise, which is what [dndSilencing] then reports.
     */
    fun liftDoNotDisturb(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (!nm.isNotificationPolicyAccessGranted) return
            if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                DebugLog.i(TAG, "Do Not Disturb lifted")
            }
        }.onFailure { DebugLog.w(TAG, "could not lift Do Not Disturb", it) }
    }

    /**
     * Reacts to the system's own ringer-mode broadcast, so a phone put on silent is audible again
     * within a moment rather than at the next watchdog pass.
     *
     * Registered at runtime by [EnforcementService] and only while the rules ask for it: a receiver
     * declared in the manifest would wake the process on every volume change on every device,
     * including the ones that never turned this on.
     */
    class RingerReceiver(private val minPercent: () -> Int, private val onRestored: () -> Unit) :
        BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
            liftDoNotDisturb(context)
            if (enforce(context, minPercent())) onRestored()
        }

        companion object {
            val FILTER: android.content.IntentFilter =
                android.content.IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
    }
}
