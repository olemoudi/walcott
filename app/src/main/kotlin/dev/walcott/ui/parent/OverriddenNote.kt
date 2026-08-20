package dev.walcott.ui.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.ChildEntry
import dev.walcott.data.FamilyRule
import dev.walcott.data.PolicySettings
import dev.walcott.data.RuleOverrides
import dev.walcott.ui.theme.SectionAccent
import dev.walcott.ui.theme.Tokens
import java.util.Locale

/**
 * Says, beside a family rule, who is not covered by it — and takes the parent to the rule that
 * is covering them instead.
 *
 * A family rule reads like a floor everyone stands on, and it is not one: a member who has
 * customized it replaces it outright, in whichever direction they went (see
 * [dev.walcott.data.RuleOverrides]). Without this line a parent moving the family bedtime an hour
 * earlier has no way to see that the one child they were moving it for is not listening — the
 * edit lands, the screen confirms it, and nothing happens on that phone.
 *
 * NAMING THE CHILD IS ONLY HALF THE HELP. A parent told that Ana is not listening wants the same
 * thing every time: to go and look at Ana's rule. Leaving them to find it means backing out to
 * the family list, opening Ana, finding the fold her rules live in and the row inside it — four
 * navigations to answer a question this line has already raised. Each name is therefore a button
 * that goes straight there, which is the pattern every settings UI with inheritance converges on:
 * state the override, then offer the way to it.
 *
 * Rendered in the FAMILY accent rather than the rules violet around it, on purpose: what this
 * announces is a PERSON standing outside the rule, and it has to be picked out of a screen whose
 * headings are already violet. Not the error red either — nothing here is broken.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverriddenNote(
    members: List<ChildEntry>,
    modifier: Modifier = Modifier,
    /**
     * Opens this member's own rules. Null renders the note as the bare sentence it used to be —
     * which is right on a screen that has no way to navigate, and wrong everywhere else, so
     * every family editor passes one.
     */
    onOpenMemberRules: ((String) -> Unit)? = null,
) {
    if (members.isEmpty()) return
    val spacing = Tokens.spacing
    val accent = Tokens.accent(SectionAccent.FAMILY)
    val names = members.map { it.name.trim() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.accentTint(SectionAccent.FAMILY))
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.Top) {
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
        if (onOpenMemberRules != null) {
            // FlowRow and one button per member rather than a single "go and look": with two
            // children ignoring a rule there are two different places to go, and a button that
            // has to ask which one afterwards is a worse version of naming them here.
            //
            // Indented to the sentence's text, not to the block, so the buttons read as its
            // answer rather than as a second unrelated thing in the same box.
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 18.dp + spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                members.forEach { member ->
                    MemberRuleButton(
                        name = member.name.trim(),
                        accent = accent,
                        onClick = { onOpenMemberRules(member.childId) },
                    )
                }
            }
        }
    }
}

/**
 * One member's name, as the way to their copy of the rule.
 *
 * A pill rather than a bare TextButton: it sits on a tinted block where an underline-free run of
 * accent-coloured text is exactly what the sentence above it already is, and nothing would say
 * which of the two can be pressed. The chevron is the other half of that — the same one every
 * row in the app that goes somewhere carries.
 */
@Composable
private fun MemberRuleButton(name: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        // Comfortably tappable without being a full-height button: this is a secondary way out
        // of a note, not the card's own action.
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Row(
            Modifier.padding(PaddingValues(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                // A name is one line. It is short by nature, and a member called something long
                // must not be what turns this row into two.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The same note, looked up for one rule — what every family editor actually calls. */
@Composable
fun OverriddenNote(
    settings: PolicySettings,
    rule: FamilyRule,
    modifier: Modifier = Modifier,
    onOpenMemberRules: ((String) -> Unit)? = null,
) {
    OverriddenNote(RuleOverrides.namedMembersOverriding(settings, rule), modifier, onOpenMemberRules)
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
