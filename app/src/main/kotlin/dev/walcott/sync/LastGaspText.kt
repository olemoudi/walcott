package dev.walcott.sync

import android.content.Context
import android.text.format.DateUtils
import dev.walcott.R

/**
 * The sentence a [LastGasp] becomes wherever the parent reads it: the silence alert, the
 * member's row, the map, the wall. One place, so "its battery ran out at 17:42" is the same
 * words on all four.
 */
object LastGaspText {

    /** Null for a kind this build does not know — a newer child may say things this one cannot. */
    fun describe(context: Context, gasp: LastGasp): String? {
        val today = DateUtils.isToday(gasp.atMs)
        val stamp = if (today) {
            DateUtils.formatDateTime(context, gasp.atMs, DateUtils.FORMAT_SHOW_TIME)
        } else {
            DateUtils.formatDateTime(
                context, gasp.atMs,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
        val res = when (gasp.kind) {
            LastGasp.KIND_BATTERY -> if (today) R.string.last_gasp_battery_at else R.string.last_gasp_battery_on
            LastGasp.KIND_SHUTDOWN -> if (today) R.string.last_gasp_shutdown_at else R.string.last_gasp_shutdown_on
            else -> return null
        }
        return context.getString(res, stamp)
    }
}
