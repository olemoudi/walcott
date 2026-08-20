package dev.walcott.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.FamilyRule
import dev.walcott.data.PolicySettings
import dev.walcott.data.RuleOverrides
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens
import java.util.Locale

/**
 * Says, beside a family rule, who is not covered by it.
 *
 * A family rule reads like a floor everyone stands on, and it is not one: a member who has
 * customized it replaces it outright, in whichever direction they went (see
 * [dev.walcott.data.RuleOverrides]). Without this line a parent moving the family bedtime an hour
 * earlier has no way to see that the one child they were moving it for is not listening — the
 * edit lands, the screen confirms it, and nothing happens on that phone.
 *
 * Rendered in the FAMILY accent rather than the rules violet around it, on purpose: what this
 * announces is a PERSON standing outside the rule, and it has to be picked out of a screen whose
 * headings are already violet. Not the error red either — nothing here is broken.
 */
@Composable
fun OverriddenNote(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    val spacing = Tokens.spacing
    val accent = Tokens.accent(SectionAccent.FAMILY)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.accentTint(SectionAccent.FAMILY))
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Face,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            pluralStringResource(R.plurals.override_ignores_this, names.size, joinNames(names)),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

/** The same note, looked up for one rule — what every family editor actually calls. */
@Composable
fun OverriddenNote(settings: PolicySettings, rule: FamilyRule, modifier: Modifier = Modifier) {
    OverriddenNote(RuleOverrides.namesOverriding(settings, rule), modifier)
}

/**
 * "Ana", "Ana and Leo", "Ana, Leo and Mar" — in the reader's language, and uncapped.
 *
 * Two patterns rather than one per length: the comma folds the head, the conjunction joins the
 * last. A count ("and 2 others") was the alternative and is worse here — the whole value of the
 * line is knowing WHICH child is not listening, and a family of four is not a crowd.
 *
 * The patterns are resolved before the fold because `stringResource` cannot be called from
 * inside `reduce`, whose lambda is not composable.
 */
@Composable
private fun joinNames(names: List<String>): String {
    if (names.size <= 1) return names.firstOrNull().orEmpty()
    val comma = stringResource(R.string.list_join_more)
    val conjunction = stringResource(R.string.list_join_two)
    val locale = Locale.getDefault()
    val head = names.dropLast(1).reduce { acc, name -> String.format(locale, comma, acc, name) }
    return String.format(locale, conjunction, head, names.last())
}
