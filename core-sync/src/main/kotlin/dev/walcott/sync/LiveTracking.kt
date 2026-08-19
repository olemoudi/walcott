package dev.walcott.sync

/**
 * Close tracking: a bounded window during which a child device reports where it is every
 * [SAMPLE_INTERVAL_MS] instead of on the family's ordinary interval.
 *
 * It exists because the periodic interval is a *cadence*, not a *latency*. Fifteen minutes is
 * the right answer for the whole ordinary day and the wrong one for the twenty minutes in which
 * somebody is walking towards a moving phone, closing in on a lost one, or frightened. Rather
 * than making the ordinary interval finer for everyone — which would cost battery all day for a
 * need that lasts minutes — the parent buys the fine interval explicitly, for a stated length of
 * time, having been told what it costs.
 *
 * Everything here is bounded, and deliberately so: this is the one mode that keeps the CPU awake
 * and the GPS warm, so it must be impossible to leave running by accident.
 *
 * Pure so the arithmetic and the guard rails are unit-tested; the machinery lives in the app.
 */
object LiveTracking {

    /** Longest session a parent can ask for. Past this it stops being "right now". */
    const val MAX_MINUTES = 240

    /** Granularity of the duration picker. */
    const val STEP_MINUTES = 15

    /** What the dialog suggests: long enough to meet someone, short enough to cost little. */
    const val DEFAULT_MINUTES = 60

    /** How often a fix is taken while a session runs and the battery is comfortable. */
    const val SAMPLE_INTERVAL_MS = 60 * 1000L

    /**
     * Battery level below which a running session starts stretching its own interval.
     *
     * Between here and [BATTERY_FLOOR_PERCENT] the cadence degrades linearly with what is left,
     * rather than running flat out until it hits a cliff. The reasoning is the same one that put
     * the floor there in the first place: what a worried parent needs is to keep being told where
     * the phone is, and a session that spends the last third of the battery in twenty minutes
     * buys precision now at the price of knowing anything at all this evening. A slower session
     * that is still running is worth more than a fast one that killed the handset.
     */
    const val THROTTLE_FROM_PERCENT = 40

    /** The longest a throttled session waits between fixes, reached just above the floor. */
    const val MAX_SAMPLE_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * How often the trail is published while a session runs.
     *
     * Deliberately coarser than the sampling: each publish is a full snapshot over the relay,
     * and one per fix would be sixty messages an hour for four hours. Two minutes carries the
     * points in pairs, moves the parent's map just as smoothly, and costs a third of the
     * traffic — which matters most on the public relay a family shares with everyone else.
     */
    const val PUBLISH_INTERVAL_MS = 2 * 60 * 1000L

    /**
     * Battery level at which a running session gives up.
     *
     * The failure this prevents is the one that matters: a four-hour session that flattens the
     * phone leaves the parent with NO location at all, which is the exact opposite of what they
     * switched it on for. Better a coarse fix every half hour tonight than a dead handset.
     */
    const val BATTERY_FLOOR_PERCENT = 15

    /**
     * How long to wait between fixes at [batteryPercent].
     *
     * Full rate while charging, while the level is unknown, or above [THROTTLE_FROM_PERCENT];
     * from there it slides linearly to [MAX_SAMPLE_INTERVAL_MS] as the battery approaches
     * [BATTERY_FLOOR_PERCENT], where the session stops altogether. Continuous at both ends, so
     * there is no step for a parent to notice as a stutter on the map.
     */
    fun sampleIntervalMs(batteryPercent: Int, charging: Boolean): Long {
        if (charging || batteryPercent < 0 || batteryPercent >= THROTTLE_FROM_PERCENT) return SAMPLE_INTERVAL_MS
        val span = (THROTTLE_FROM_PERCENT - BATTERY_FLOOR_PERCENT).toFloat()
        val headroom = (batteryPercent - BATTERY_FLOOR_PERCENT).coerceAtLeast(0) / span
        val stretch = MAX_SAMPLE_INTERVAL_MS - SAMPLE_INTERVAL_MS
        return SAMPLE_INTERVAL_MS + ((1f - headroom) * stretch).toLong()
    }

    /**
     * How long to wait between publishes for a session sampling every [sampleIntervalMs].
     *
     * Always at least a couple of fixes' worth: the radio wake, not the payload, is what a
     * publish costs, so a throttled session that kept publishing every two minutes would have
     * given back only half of what the throttle was for.
     */
    fun publishIntervalMs(sampleIntervalMs: Long): Long =
        maxOf(PUBLISH_INTERVAL_MS, sampleIntervalMs * 2)

    /** Session lengths offered as one tap, in minutes. Anything else comes from the stepper. */
    val PRESET_MINUTES = listOf(15, 30, 60, 120)

    /** The first child build that understands a live-tracking command. */
    const val MIN_CHILD_VERSION = 126

    /** Whether a child reporting [childAppVersionCode] can be asked to track closely at all. */
    fun isSupported(childAppVersionCode: Int): Boolean = childAppVersionCode >= MIN_CHILD_VERSION

    /** [minutes] rounded to a whole [STEP_MINUTES] and held inside the offered range. */
    fun clampMinutes(minutes: Int): Int {
        if (minutes <= 0) return 0
        val stepped = (minutes + STEP_MINUTES / 2) / STEP_MINUTES * STEP_MINUTES
        return stepped.coerceIn(STEP_MINUTES, MAX_MINUTES)
    }

    /** How much longer one tap on "extend" buys a running session. */
    const val EXTEND_MINUTES = 30

    /**
     * The length to ask for so a session with [remainingMs] left runs [EXTEND_MINUTES] longer.
     *
     * A session is set, never added to: the child writes a deadline of "now plus what was
     * asked for", so extending means asking for what is LEFT plus the extension. Reading the
     * remainder from the parent's copy is honest to within one publish (two minutes while a
     * session runs), and erring by that much on a bounded mode is the right direction.
     *
     * Rounded UP to a whole [STEP_MINUTES], unlike [clampMinutes], which rounds to the nearest:
     * a parent who taps "+30" and is handed 23 more minutes has been quietly short-changed by
     * the arithmetic. Capped at [MAX_MINUTES] like everything else here — past that the answer
     * is a new session, not a longer one.
     */
    fun extendedMinutes(remainingMs: Long): Int {
        val left = ((remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
        val wanted = left + EXTEND_MINUTES
        val stepped = (wanted + STEP_MINUTES - 1) / STEP_MINUTES * STEP_MINUTES
        return stepped.coerceAtMost(MAX_MINUTES)
    }

    /**
     * Whether a session that should end at [untilElapsedMs] is still running at [nowElapsedMs].
     *
     * Both are [android.os.SystemClock.elapsedRealtime] values, NOT wall clock, and that is the
     * point: the child's clock is something the child can change, and this app already knows it
     * (see [ChildSnapshot.clockSkewMs]). A wall-clock deadline would let a session be ended by
     * winding the phone forward — and would end one early by itself the first time the network
     * corrected a drifting clock.
     */
    fun isRunning(untilElapsedMs: Long, nowElapsedMs: Long): Boolean =
        untilElapsedMs > 0 && nowElapsedMs < untilElapsedMs

    /** Milliseconds left of a running session, or 0 when it is over. */
    fun remainingMs(untilElapsedMs: Long, nowElapsedMs: Long): Long =
        (untilElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    /**
     * Whether [batteryPercent] is too low to keep a session running. An unknown level (-1, a
     * platform that would not say) does not stop anything: refusing to track because a number
     * was missing would be its own failure.
     */
    fun batteryTooLow(batteryPercent: Int, charging: Boolean): Boolean =
        !charging && batteryPercent in 0 until BATTERY_FLOOR_PERCENT

    /** [CommandAck.detail] outcomes. */
    const val DETAIL_STARTED = "live_started"
    const val DETAIL_STOPPED = "live_stopped"
    const val DETAIL_BATTERY_LOW = "live_battery_low"
}
