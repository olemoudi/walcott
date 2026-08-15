package dev.walcott.ui.child

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import dev.walcott.R
import dev.walcott.data.Insight
import dev.walcott.data.Yardstick
import dev.walcott.ui.format.humanize
import java.time.format.TextStyle
import java.util.Locale

/**
 * An [Insight] as the sentence the child reads.
 *
 * The phrasings live in arrays and one is picked by [rotation] — the same number that chose the
 * insight — so a fact that comes round again does not come round in the same words. Splitting it
 * this way is the point: what is true is decided in [dev.walcott.data.Insights] where it can be
 * tested, and how it sounds is decided here where it can be translated.
 */
@Composable
fun insightText(insight: Insight, label: (String) -> String, rotation: Int): String {
    // A second turn of the same crank: the phrasing moves independently of the fact, so two
    // days that land on the same insight rarely land on the same sentence.
    @Composable
    fun variant(arrayRes: Int): String {
        val options = stringArrayResource(arrayRes)
        return options[((rotation / 3) % options.size + options.size) % options.size]
    }
    return when (insight) {
        is Insight.TopAppWeek ->
            variant(R.array.insight_top_app_week).format(label(insight.packageName), insight.time.humanize())

        is Insight.WeekDelta ->
            variant(if (insight.down) R.array.insight_week_down else R.array.insight_week_up)
                .format(insight.difference.humanize())

        is Insight.BusiestDay ->
            variant(R.array.insight_busiest_day).format(
                insight.day.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                insight.time.humanize(),
            )

        is Insight.OneAppShare ->
            variant(R.array.insight_one_app_share).format(insight.percent, label(insight.packageName))

        is Insight.MonthYardstick -> {
            val plural = when (insight.unit) {
                Yardstick.NIGHT_OF_SLEEP -> R.plurals.insight_month_sleep
                Yardstick.FILM -> R.plurals.insight_month_film
                Yardstick.FOOTBALL_MATCH -> R.plurals.insight_month_match
                Yardstick.ALBUM -> R.plurals.insight_month_album
            }
            pluralStringResource(
                plural,
                insight.count,
                insight.time.humanize(),
                label(insight.packageName),
                insight.count,
            )
        }
    }
}
