package dev.walcott.sync

import android.content.Context
import dev.walcott.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * What this phone has published today, by the relay's day (UTC): how many messages went out, how
 * many were refused, and until when the relay has said "no more today".
 *
 * The public relay counts messages per address — 250 a day for everyone behind one home router —
 * and refuses the rest until midnight UTC with a 429 that says so (ntfy's 42908). Nothing here
 * counted them, so nobody could say how close a family was, and a refusal looked like any other
 * failed publish: retried, dropped, and noticed by the parent only after three in a row in a
 * process that lived long enough to see them.
 */
data class LedgerDay(
    val utcDay: Long,
    val sent: Int,
    val refused: Int,
    val quotaUntilMs: Long,
    /** When this phone last got a message onto the relay (any day): what "published lately" asks. */
    val lastSentAtMs: Long = 0,
) {

    /** Whether the relay has refused this phone for the rest of its day at [nowMs]. */
    fun quotaExhausted(nowMs: Long): Boolean = quotaUntilMs > nowMs

    /** This day, or a fresh one if [nowMs] is a later day than it. */
    fun on(nowMs: Long): LedgerDay {
        val day = utcDayOf(nowMs)
        return if (day == utcDay) this else LedgerDay(day, 0, 0, quotaUntilMs.takeIf { it > nowMs } ?: 0, lastSentAtMs)
    }

    /** One more sent — and a relay that took a message is, whatever it said before, taking them again. */
    fun plusSent(nowMs: Long): LedgerDay = on(nowMs).let { it.copy(sent = it.sent + 1, quotaUntilMs = 0, lastSentAtMs = nowMs) }

    fun plusRefused(nowMs: Long, quota: Boolean): LedgerDay = on(nowMs).let {
        it.copy(refused = it.refused + 1, quotaUntilMs = if (quota) nextUtcMidnight(nowMs) else it.quotaUntilMs)
    }

    companion object {
        val NONE = LedgerDay(0, 0, 0, 0)

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        fun utcDayOf(nowMs: Long): Long = Math.floorDiv(nowMs, DAY_MS)

        /** When the relay's day ends: its quota is counted per UTC day. */
        fun nextUtcMidnight(nowMs: Long): Long = (utcDayOf(nowMs) + 1) * DAY_MS

        fun format(day: LedgerDay): String =
            "${day.utcDay} ${day.sent} ${day.refused} ${day.quotaUntilMs} ${day.lastSentAtMs}"

        /** Anything unreadable reads as [NONE]: a lost count costs a day's arithmetic, nothing more. */
        fun parse(text: String?): LedgerDay {
            val parts = text?.trim()?.split(' ') ?: return NONE
            if (parts.size != 5) return NONE
            val values = parts.map { it.toLongOrNull() ?: return NONE }
            if (values.any { it < 0 }) return NONE
            return LedgerDay(values[0], values[1].toInt(), values[2].toInt(), values[3], values[4])
        }

        /**
         * Whether a 429 is the relay's daily message quota rather than a burst limit. A burst is
         * worth retrying in a second; the quota is not worth retrying until tomorrow, and every
         * retry is one more request on an address that has already been told no.
         */
        fun isDailyQuota(code: Int, body: String): Boolean = code == PublishHealth.HTTP_TOO_MANY_REQUESTS &&
            QUOTA_CODE in body

        /** ntfy's error code for "limit reached: daily message quota reached" (server/errors.go). */
        private const val QUOTA_CODE = "42908"
    }
}

/** The persisted [LedgerDay] for this phone, across every family it holds (the quota is per address). */
object PublishLedger {

    private const val TAG = "WalcottSync"
    private const val FILE_NAME = "publish-ledger.txt"

    @Volatile private var file: File? = null
    private val mutable = MutableStateFlow(LedgerDay.NONE)

    /** Today's numbers as they stand. */
    val today: StateFlow<LedgerDay> = mutable.asStateFlow()

    /** Call once from [android.app.Application.onCreate]. */
    fun init(context: Context) {
        val f = File(context.filesDir, FILE_NAME)
        file = f
        mutable.value = runCatching { LedgerDay.parse(f.takeIf { it.exists() }?.readText()) }
            .getOrDefault(LedgerDay.NONE).on(System.currentTimeMillis())
    }

    /** Whether routine publishes should hold off: the relay has refused this phone until its midnight. */
    fun quotaExhausted(nowMs: Long = System.currentTimeMillis()): Boolean = mutable.value.quotaExhausted(nowMs)

    @Volatile private var lastProbeMs = 0L

    /**
     * Whether a routine publish should go out anyway to find out if the relay has started taking
     * them again — one every [PROBE_EVERY_MS]. The midnight worked out here is this phone's idea
     * of the relay's day; if the relay disagrees, or the refusal was something else wearing the
     * same code, a phone that trusted it blindly would sit silent for the rest of the day.
     */
    @Synchronized
    fun probeDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs - lastProbeMs < PROBE_EVERY_MS) return false
        lastProbeMs = nowMs
        return true
    }

    private const val PROBE_EVERY_MS = 15 * 60 * 1000L

    fun recordSent(nowMs: Long = System.currentTimeMillis()) {
        update { it.plusSent(nowMs) }
    }

    fun recordRefused(quota: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val (before, after) = update { it.plusRefused(nowMs, quota) }
        if (quota && !before.quotaExhausted(nowMs)) {
            DebugLog.e(TAG, "the relay's daily message quota is used up until ${after.quotaUntilMs}")
        }
    }

    /** One read-modify-write at a time: publishes finish on OkHttp's threads, several at once. */
    @Synchronized
    private fun update(transform: (LedgerDay) -> LedgerDay): Pair<LedgerDay, LedgerDay> {
        val before = mutable.value
        val after = transform(before)
        mutable.value = after
        runCatching { file?.writeText(LedgerDay.format(after)) }
        return before to after
    }
}
