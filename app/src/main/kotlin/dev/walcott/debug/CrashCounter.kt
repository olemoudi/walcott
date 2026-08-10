package dev.walcott.debug

import java.io.File

/** A crash tally: how many uncaught exceptions this install has died of, and when the last was. */
data class CrashTally(val total: Int, val lastAtMs: Long) {
    companion object {
        val NONE = CrashTally(0, 0)
    }
}

/**
 * The crash tally's on-disk format, kept pure so the parse is unit-tested rather than only
 * exercised by an actual crash.
 *
 * One line, `<total> <lastAtMs>`. Anything unreadable reads as [CrashTally.NONE]: a corrupt
 * counter must not be able to invent crashes the parent would then be alerted about, and
 * losing the tally is far cheaper than lying about it.
 */
internal object CrashTallyFormat {

    fun format(tally: CrashTally): String = "${tally.total} ${tally.lastAtMs}"

    fun parse(text: String?): CrashTally {
        val parts = text?.trim()?.split(' ') ?: return CrashTally.NONE
        if (parts.size != 2) return CrashTally.NONE
        val total = parts[0].toIntOrNull() ?: return CrashTally.NONE
        val last = parts[1].toLongOrNull() ?: return CrashTally.NONE
        if (total < 0 || last < 0) return CrashTally.NONE
        return CrashTally(total, last)
    }

    /** The tally after one more crash at [atMs]. */
    fun plusCrash(previous: CrashTally, atMs: Long): CrashTally =
        CrashTally(previous.total + 1, atMs)
}

/**
 * How many times this install has died of an uncaught exception, persisted so the count
 * survives the process it is counting.
 *
 * Written from [DebugLog.crash] on the CALLING thread. Everything else in the debug log hands
 * its file write to an executor; a crash handler cannot, because the process is dead long
 * before that executor is next scheduled — which is exactly why crashes used to leave no trace
 * at all. A file (not the DataStore) for the same reason: a suspend write would never complete.
 *
 * Never reset. The parent alerts on growth between two snapshots (see [dev.walcott.sync]), so
 * there is no reset to race with, and a fresh parent that has never seen this child simply
 * starts from whatever the tally says rather than alerting about history it missed.
 */
object CrashCounter {

    private const val FILE_NAME = "crash-count.txt"

    @Volatile private var file: File? = null

    /** Call once from [android.app.Application.onCreate], beside [DebugLog.init]. */
    fun init(context: android.content.Context) {
        file = File(context.filesDir, FILE_NAME)
    }

    /** The tally as it stands. [CrashTally.NONE] before [init], or when nothing is recorded. */
    fun current(): CrashTally {
        val f = file ?: return CrashTally.NONE
        return runCatching { CrashTallyFormat.parse(f.takeIf { it.exists() }?.readText()) }
            .getOrDefault(CrashTally.NONE)
    }

    /** Records one crash at [atMs], synchronously. Best-effort: a failed write loses the count. */
    internal fun record(atMs: Long) {
        val f = file ?: return
        runCatching { f.writeText(CrashTallyFormat.format(CrashTallyFormat.plusCrash(current(), atMs))) }
    }
}
