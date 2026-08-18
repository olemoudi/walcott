package dev.walcott.ui.parent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.walcott.R
import dev.walcott.data.WindowDto
import dev.walcott.data.toTimeOfDayOrNull
import androidx.compose.material3.TextButton
import dev.walcott.rules.DayType
import dev.walcott.rules.RuleReview
import dev.walcott.rules.SpecialDays
import dev.walcott.ui.DAY_TYPES
import dev.walcott.ui.RULE_DAY_TYPES
import dev.walcott.ui.editableUnder
import dev.walcott.ui.components.CardPosition
import dev.walcott.ui.components.Stepper
import dev.walcott.ui.components.TimePickerDialog
import dev.walcott.ui.components.WalcottCard
import dev.walcott.ui.format.hhmm
import dev.walcott.ui.format.humanize
import dev.walcott.ui.labelRes
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import dev.walcott.ui.theme.Tokens

/**
 * Value-based policy editors, shared by the family editor (BudgetsScreen) and the
 * per-child override editor (ChildDetailScreen): they render a slice of PolicySettings
 * and report the whole new value through onChange.
 */

@Composable
internal fun BedtimeCard(
    bedtime: Map<String, WindowDto>,
    /** False renders the windows as configured but refuses every control — an inherited rule. */
    enabled: Boolean = true,
    position: CardPosition = CardPosition.Single,
    /** The family's single special-days switch: it decides whether the third row accepts edits. */
    specialDaysOwnRules: Boolean = false,
    onOpenSpecialDays: (() -> Unit)? = null,
    onSetSpecialDaysOwnRules: ((Boolean) -> Unit)? = null,
    onChange: (Map<String, WindowDto>) -> Unit,
) {
    val spacing = Tokens.spacing
    val defaultStart = LocalTime.of(21, 30)
    val defaultEnd = LocalTime.of(7, 30)
    // "There is a bedtime" is any day type having one; the master switch seeds or clears them all.
    val on = RULE_DAY_TYPES.any { bedtime[it.name] != null }
    var editing by remember { mutableStateOf<BedtimeEdit?>(null) }

    fun windowOf(dayType: DayType): Pair<LocalTime, LocalTime> {
        val w = bedtime[dayType.name]
        return (w?.startMinute.toTimeOfDayOrNull() ?: defaultStart) to (w?.endMinute.toTimeOfDayOrNull() ?: defaultEnd)
    }

    fun setAll(s: LocalTime?, e: LocalTime?) {
        onChange(
            if (s == null || e == null) emptyMap()
            else RULE_DAY_TYPES.associate { it.name to WindowDto(s.toMinute(), e.toMinute()) },
        )
    }

    /** Writes one day type only. The mirror pass folds WEEKEND onto HOLIDAY while it is off. */
    fun setOne(dayType: DayType, s: LocalTime, e: LocalTime) {
        onChange(bedtime + (dayType.name to WindowDto(s.toMinute(), e.toMinute())))
    }

    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bedtime_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = on,
                    enabled = enabled,
                    onCheckedChange = { want -> if (want) setAll(defaultStart, defaultEnd) else setAll(null, null) },
                )
            }
            if (on) {
                @Composable
                fun bedtimeRow(dayType: DayType) {
                    val (start, end) = windowOf(dayType)
                    Row(
                        Modifier.fillMaxWidth().padding(top = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(dayType.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        TimeButton(stringResource(R.string.from), start.hhmm(), enabled) {
                            editing = BedtimeEdit(dayType, isStart = true)
                        }
                        Spacer(Modifier.size(spacing.sm))
                        TimeButton(stringResource(R.string.to), end.hhmm(), enabled) {
                            editing = BedtimeEdit(dayType, isStart = false)
                        }
                    }
                }
                DAY_TYPES.forEach { bedtimeRow(it) }
                if (onSetSpecialDaysOwnRules != null && onOpenSpecialDays != null) {
                    SpecialDaysSection(
                        on = specialDaysOwnRules,
                        onOpenCalendar = onOpenSpecialDays,
                        enabled = enabled,
                        onChange = onSetSpecialDaysOwnRules,
                    ) { bedtimeRow(DayType.HOLIDAY) }
                }
            }
        }
    }

    editing?.let { which ->
        val (start, end) = windowOf(which.dayType)
        TimePickerDialog(
            initial = if (which.isStart) start else end,
            title = stringResource(if (which.isStart) R.string.bedtime_start_title else R.string.bedtime_end_title),
            onDismiss = { editing = null },
            onConfirm = { picked ->
                if (which.isStart) setOne(which.dayType, picked, end) else setOne(which.dayType, start, picked)
                editing = null
            },
        )
    }
}

private data class BedtimeEdit(val dayType: DayType, val isStart: Boolean)

private fun LocalTime.toMinute() = hour * 60 + minute

private enum class WeekendEdge { START, END }

/** Sensible first guesses when a parent flips an edge on: school lets out, and school night. */
internal const val DEFAULT_WEEKEND_START_MINUTE = 14 * 60
internal const val DEFAULT_WEEKEND_END_MINUTE = 20 * 60

/**
 * The two optional weekend edges (minute-of-day, null = the edge stays at midnight). Both off
 * — the default — is the plain calendar weekend: all of Saturday and all of Sunday. Shared by
 * the special-days screen and the setup wizard's weekend step.
 */
@Composable
internal fun WeekendEdgesCard(startMinute: Int?, endMinute: Int?, onChange: (Int?, Int?) -> Unit) {
    val spacing = Tokens.spacing
    var picking by remember { mutableStateOf<WeekendEdge?>(null) }

    WalcottCard {
        Column(Modifier.padding(spacing.lg).animateContentSize()) {
            Text(stringResource(R.string.weekend_edges_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.weekend_edges_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(spacing.md))
            WeekendEdgeRow(
                title = stringResource(R.string.weekend_start_title),
                supporting = stringResource(
                    if (startMinute == null) R.string.weekend_start_off else R.string.weekend_start_on,
                ),
                dayLabel = stringResource(R.string.weekend_edge_friday),
                minute = startMinute,
                onToggle = { on -> onChange(if (on) DEFAULT_WEEKEND_START_MINUTE else null, endMinute) },
                onPick = { picking = WeekendEdge.START },
            )
            HorizontalDivider(Modifier.padding(vertical = spacing.sm))
            WeekendEdgeRow(
                title = stringResource(R.string.weekend_end_title),
                supporting = stringResource(
                    if (endMinute == null) R.string.weekend_end_off else R.string.weekend_end_on,
                ),
                dayLabel = stringResource(R.string.weekend_edge_sunday),
                minute = endMinute,
                onToggle = { on -> onChange(startMinute, if (on) DEFAULT_WEEKEND_END_MINUTE else null) },
                onPick = { picking = WeekendEdge.END },
            )
            if (startMinute != null || endMinute != null) {
                Spacer(Modifier.size(spacing.md))
                Text(
                    stringResource(R.string.weekend_edges_counter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    picking?.let { edge ->
        val start = edge == WeekendEdge.START
        val current = (if (start) startMinute else endMinute)
            ?: if (start) DEFAULT_WEEKEND_START_MINUTE else DEFAULT_WEEKEND_END_MINUTE
        TimePickerDialog(
            initial = LocalTime.ofSecondOfDay(current * 60L),
            title = stringResource(if (start) R.string.weekend_start_picker else R.string.weekend_end_picker),
            onDismiss = { picking = null },
            onConfirm = { picked ->
                val minute = picked.toMinute()
                if (start) onChange(minute, endMinute) else onChange(startMinute, minute)
                picking = null
            },
        )
    }
}

@Composable
private fun WeekendEdgeRow(
    title: String,
    supporting: String,
    dayLabel: String,
    minute: Int?,
    onToggle: (Boolean) -> Unit,
    onPick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (minute != null) {
            TimeButton(dayLabel, LocalTime.ofSecondOfDay(minute * 60L).hhmm(), onClick = onPick)
            Spacer(Modifier.size(Tokens.spacing.sm))
        }
        Switch(checked = minute != null, onCheckedChange = onToggle)
    }
}

/**
 * Multi-window block editor, shared by the family "screen-free times" card (all apps) and
 * the per-app hours editor. Like bedtime, the same list applies to every day type; the
 * caller maps the list into its per-day-type storage. [title] is null when the screen
 * already provides a section header.
 */
/**
 * Blocked-window editor, one list of windows per day type.
 *
 * The special-day group is always present. While the family's switch is off it is read-only and
 * shows the weekend's windows, which is what actually applies on those days; turning the switch on
 * gives it a list of its own, seeded from the weekend so nothing changes until the parent edits it.
 */
@Composable
internal fun BlockedWindowsCard(
    title: String?,
    hint: String,
    windowsByDay: Map<String, List<WindowDto>>,
    position: CardPosition = CardPosition.Single,
    /** False renders the windows as configured but refuses every control — an inherited rule. */
    enabled: Boolean = true,
    specialDaysOwnRules: Boolean = false,
    onOpenSpecialDays: (() -> Unit)? = null,
    onSetSpecialDaysOwnRules: ((Boolean) -> Unit)? = null,
    /**
     * Receives the WHOLE schedule, once per edit. It used to be called per day type, which meant
     * one edit became three separate writes racing each other through the store — and between
     * them the stored schedule genuinely disagreed with itself about which rules existed, which
     * is what made rules flicker in and out while a parent was changing one.
     */
    onChange: (List<WindowDto>) -> Unit,
) {
    val spacing = Tokens.spacing
    WalcottCard(position = position) {
        Column(Modifier.padding(spacing.lg).animateContentSize()) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // One list. Every rule already named its own days, so filing it into a weekday or
            // weekend section asked the same question a second time, in a coarser way — and the
            // special-days section asked a third. The rule now says all of it on the rule.
            val windows = windowsByDay[DayType.SCHOOL.name].orEmpty()
            WindowsForDay(
                windows = windows,
                editable = enabled,
                onChange = onChange,
            )
            if (enabled) ScheduleReview(windows, onChange)
            if (onOpenSpecialDays != null) {
                SpecialDaysLink(onOpen = onOpenSpecialDays, enabled = enabled)
            }
        }
    }
}

/**
 * What [RuleReview] found in this schedule, each with the one tap that acts on it.
 *
 * Quiet by construction: a healthy schedule renders nothing at all, so this can only ever appear
 * because there is something to say. Every suggestion is a rewrite of the parent's own rules, so
 * each says what it will do in the parent's terms and none of them applies itself.
 */
@Composable
private fun ScheduleReview(windows: List<WindowDto>, onChange: (List<WindowDto>) -> Unit) {
    val spacing = Tokens.spacing
    // The engine works in the rules' own language, not the wire's; anything that doesn't survive
    // the conversion is a malformed window nobody can act on anyway.
    val parsed = remember(windows) { windows.map { it.toTimeWindowOrNull() } }
    val findings = remember(parsed) {
        if (parsed.any { it == null }) emptyList() else RuleReview.review(parsed.filterNotNull())
    }
    if (findings.isEmpty()) return

    Column(Modifier.padding(top = spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.review_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = spacing.xs),
        )
        findings.forEach { finding ->
            val text = when (finding) {
                is RuleReview.Finding.NeverFires ->
                    stringResource(R.string.review_never_fires, ruleName(windows, finding.index))
                is RuleReview.Finding.Redundant -> stringResource(
                    R.string.review_redundant,
                    ruleName(windows, finding.index),
                    ruleName(windows, finding.coveredBy),
                )
                is RuleReview.Finding.Mergeable ->
                    pluralStringResource(R.plurals.review_mergeable, finding.indices.size, finding.indices.size)
            }
            val action = when (finding) {
                is RuleReview.Finding.Mergeable -> R.string.review_merge
                else -> R.string.review_delete
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                TextButton(onClick = { onChange(applied(windows, finding)) }) {
                    Text(stringResource(action))
                }
            }
        }
    }
}

/** How a rule is referred to in a finding: its hours, which is how the row above shows it. */
@Composable
private fun ruleName(windows: List<WindowDto>, index: Int): String {
    val window = windows.getOrNull(index) ?: return ""
    val start = window.startMinute.toTimeOfDayOrNull() ?: return ""
    val end = window.endMinute.toTimeOfDayOrNull() ?: return ""
    return stringResource(R.string.window_range, start.hhmm(), end.hhmm())
}

/**
 * The schedule with [finding] acted on: the offending rule gone, or the merged rules replaced by
 * the one that says what they said. The merged rule takes the position of the first it replaces,
 * so the list does not reshuffle under the parent who just tapped it.
 */
private fun applied(windows: List<WindowDto>, finding: RuleReview.Finding): List<WindowDto> = when (finding) {
    is RuleReview.Finding.NeverFires -> windows.filterIndexed { i, _ -> i != finding.index }
    is RuleReview.Finding.Redundant -> windows.filterIndexed { i, _ -> i != finding.index }
    is RuleReview.Finding.Mergeable -> {
        val keep = finding.indices.first()
        val merged = WindowDto(
            startMinute = finding.merged.start.hour * 60 + finding.merged.start.minute,
            endMinute = finding.merged.end.hour * 60 + finding.merged.end.minute,
            days = finding.merged.days.map { it.value }.sorted(),
            skipSpecialDays = finding.merged.specialDays == SpecialDays.NEVER,
            onlySpecialDays = finding.merged.specialDays == SpecialDays.ONLY,
        )
        windows.mapIndexedNotNull { i, w ->
            when (i) {
                keep -> merged
                in finding.indices -> null
                else -> w
            }
        }
    }
}

/** The schedule's rules. Read-only when [editable] is false — it is then inherited, not owned. */
@Composable
private fun WindowsForDay(
    windows: List<WindowDto>,
    editable: Boolean,
    onChange: (List<WindowDto>) -> Unit,
) {
    val spacing = Tokens.spacing
    var editing by remember { mutableStateOf<WindowEdit?>(null) }

    if (windows.isEmpty()) {
        Text(
            stringResource(R.string.windows_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
    windows.forEachIndexed { index, window ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = spacing.sm))
        val start = LocalTime.ofSecondOfDay(window.startMinute * 60L)
        val end = LocalTime.ofSecondOfDay(window.endMinute * 60L)
        if (!editable) {
            Text(
                stringResource(R.string.window_range, start.hhmm(), end.hhmm()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            return@forEachIndexed
        }
        Row(
            Modifier.fillMaxWidth().padding(top = if (index == 0) spacing.xs else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            TimeButton(stringResource(R.string.from), start.hhmm()) { editing = WindowEdit.Start(index) }
            TimeButton(stringResource(R.string.to), end.hhmm()) { editing = WindowEdit.End(index) }
            Spacer(Modifier.weight(1f))
            val snackbar = dev.walcott.ui.components.LocalSnackbar.current
            val undo = stringResource(R.string.action_undo)
            // Named as the editor names it ("Rule 2", "17:00–19:00"), so the sentence matches the
            // row that was just there.
            val removed = stringResource(R.string.snack_removed, ruleName(windows, index))
            IconButton(onClick = {
                val before = windows
                onChange(windows.filterIndexed { i, _ -> i != index })
                snackbar.show(removed, undo) { onChange(before) }
            }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.window_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DayPicker(
            selected = window.days,
            onToggle = { day ->
                onChange(windows.mapIndexed { i, w -> if (i == index) w.copy(days = w.days.toggledDay(day)) else w })
            },
        )
        SpecialDaysChoice(
            selected = window.specialDays,
            onSelect = { rule ->
                onChange(
                    windows.mapIndexed { i, w ->
                        if (i == index) WindowDto.withSpecialDays(w, rule) else w
                    },
                )
            },
        )
    }
    if (editable) {
        Spacer(Modifier.size(spacing.sm))
        BudgetPreset(stringResource(R.string.window_add)) { editing = WindowEdit.NewStart }
    }

    when (val edit = editing) {
        null -> {}
        is WindowEdit.Start -> WindowTimePicker(windows[edit.index].startMinute, R.string.from) { picked ->
            if (picked != null) {
                onChange(windows.mapIndexed { i, w -> if (i == edit.index) w.copy(startMinute = picked.toMinute()) else w })
            }
            editing = null
        }
        is WindowEdit.End -> WindowTimePicker(windows[edit.index].endMinute, R.string.to) { picked ->
            if (picked != null) {
                onChange(windows.mapIndexed { i, w -> if (i == edit.index) w.copy(endMinute = picked.toMinute()) else w })
            }
            editing = null
        }
        // Adding chains two pickers: start first, then end, then the window lands at once.
        WindowEdit.NewStart -> WindowTimePicker(15 * 60, R.string.from) { picked ->
            editing = if (picked == null) null else WindowEdit.NewEnd(picked)
        }
        is WindowEdit.NewEnd -> WindowTimePicker(17 * 60, R.string.to) { picked ->
            if (picked != null) onChange(windows + WindowDto(edit.start.toMinute(), picked.toMinute()))
            editing = null
        }
    }
}

/**
 * What one rule does on the calendar's special days, on the same card as its weekdays.
 *
 * The third state is why the special-days SECTION could go: "only on holidays" used to be
 * expressed by putting a whole separate list of rules behind a switch, which is a lot of screen
 * to say something a rule can say about itself.
 */
@Composable
private fun SpecialDaysChoice(selected: SpecialDays, onSelect: (SpecialDays) -> Unit) {
    val spacing = Tokens.spacing
    Column(Modifier.padding(top = spacing.sm)) {
        Text(
            stringResource(R.string.window_special_days_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Equal thirds rather than each chip sizing to its own word: the three are one choice,
        // and a row that reflows differently per language (or when a label is one word longer)
        // reads as three unrelated buttons. Weighting also keeps the longest label on one line
        // instead of wrapping inside a chip half the width of its neighbours.
        Row(
            Modifier.fillMaxWidth().padding(top = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SpecialDays.entries.forEach { rule ->
                dev.walcott.ui.components.ChoiceChip(
                    modifier = Modifier.weight(1f),
                    selected = selected == rule,
                    onClick = { onSelect(rule) },
                    label = stringResource(
                        when (rule) {
                            SpecialDays.ALWAYS -> R.string.window_special_always
                            SpecialDays.NEVER -> R.string.window_special_never
                            SpecialDays.ONLY -> R.string.window_special_only
                        },
                    ),
                )
            }
        }
    }
}

/** Way through to the calendar that decides which days are special, without owning any rules. */
@Composable
private fun SpecialDaysLink(onOpen: () -> Unit, enabled: Boolean) {
    val spacing = Tokens.spacing
    TextButton(onClick = onOpen, enabled = enabled, modifier = Modifier.padding(top = spacing.sm)) {
        Text(stringResource(R.string.special_days_note_open))
    }
}

@Composable
private fun DayPicker(selected: List<Int>, onToggle: (DayOfWeek) -> Unit) {
    val locale = Locale.getDefault()
    val week = remember(locale) {
        val first = WeekFields.of(locale).firstDayOfWeek
        List(7) { first.plus(it.toLong()) }
    }
    val all = selected.isEmpty()
    Row(
        Modifier.fillMaxWidth().padding(top = Tokens.spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        week.forEach { day ->
            val on = all || day.value in selected
            Surface(
                onClick = { onToggle(day) },
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        day.getDisplayName(TextStyle.NARROW, locale).uppercase(locale),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * This day set with [day] flipped, in the storage convention: empty = every day, and a full
 * week collapses back to empty. Deselecting the last remaining day is refused — a window that
 * applies on no day at all is a rule the parent can see but that never fires.
 */
internal fun List<Int>.toggledDay(day: DayOfWeek): List<Int> {
    val current = if (isEmpty()) DayOfWeek.entries.map { it.value } else this
    val next = if (day.value in current) current - day.value else current + day.value
    return when {
        next.isEmpty() -> this
        next.size == DayOfWeek.entries.size -> emptyList()
        else -> next.sorted()
    }
}

private sealed interface WindowEdit {
    data class Start(val index: Int) : WindowEdit
    data class End(val index: Int) : WindowEdit
    data object NewStart : WindowEdit
    data class NewEnd(val start: LocalTime) : WindowEdit
}

/** One time picker step; reports null on dismiss. */
@Composable
private fun WindowTimePicker(initialMinute: Int, titleRes: Int, onDone: (LocalTime?) -> Unit) {
    TimePickerDialog(
        initial = LocalTime.ofSecondOfDay(initialMinute * 60L),
        title = stringResource(titleRes),
        onDismiss = { onDone(null) },
        onConfirm = { onDone(it) },
    )
}

@Composable
internal fun TimeButton(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    // Disabled has to LOOK disabled: Surface(enabled = false) only stops the tap, and an
    // explicit container colour keeps it looking live. A row that reads as editable and
    // silently isn't is worse than one that admits it is a mirror.
    val container = MaterialTheme.colorScheme.surfaceVariant.let { if (enabled) it else it.copy(alpha = 0.4f) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.let { if (enabled) it else it.copy(alpha = 0.5f) }
    val valueColor = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.5f) }
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), color = container) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
        }
    }
}

@Composable
private fun BudgetPreset(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(dev.walcott.ui.components.ComfortableChipPadding),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DailyBudgetCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Schedule,
    perDay: Map<String, Int>,
    /** False still expands to show the numbers; it only refuses to change them. */
    enabled: Boolean = true,
    position: CardPosition = CardPosition.Single,
    /** The family's single special-days switch: it decides whether the third row accepts edits. */
    specialDaysOwnRules: Boolean = false,
    /** Opens the screen where special days themselves are chosen; null hides the shortcut. */
    onOpenSpecialDays: (() -> Unit)? = null,
    /** Null hides the switch — the per-child editor edits one child, the switch is family-wide. */
    onSetSpecialDaysOwnRules: ((Boolean) -> Unit)? = null,
    onSetBudget: (DayType, Int?) -> Unit,
) {
    val spacing = Tokens.spacing
    var expanded by remember { mutableStateOf(false) }
    // Only rows the family can actually set count towards the summary: with special days
    // mirroring the weekend, counting three would claim a limit the parent never chose.
    val dayTypes = RULE_DAY_TYPES
    val editableDays = dayTypes.filter { it.editableUnder(specialDaysOwnRules) }
    val limitedDays = editableDays.count { perDay[it.name] != null }
    val summary = if (limitedDays == 0) stringResource(R.string.no_limit)
    else pluralStringResource(R.plurals.days_with_limit, limitedDays, limitedDays)

    WalcottCard(position = position) {
        Column(Modifier.animateContentSize().clickable { expanded = !expanded }.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle?.let { "$it · $summary" } ?: summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.size(spacing.md))
                HorizontalDivider()
                // Quick presets applied to every day type at once — the common case, far fewer
                // taps than stepping each of the three rows up from "no limit".
                Text(
                    stringResource(R.string.budget_apply_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(top = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    BudgetPreset(stringResource(R.string.no_limit), enabled) { editableDays.forEach { onSetBudget(it, null) } }
                    BudgetPreset("1h", enabled) { editableDays.forEach { onSetBudget(it, 60) } }
                    BudgetPreset("2h", enabled) { editableDays.forEach { onSetBudget(it, 120) } }
                    var customAll by remember { mutableStateOf(false) }
                    BudgetPreset(stringResource(R.string.custom_value), enabled) { customAll = true }
                    if (customAll) {
                        dev.walcott.ui.components.MinutesPickerDialog(
                            title = stringResource(R.string.custom_minutes_title),
                            initial = 60,
                            onDismiss = { customAll = false },
                            onConfirm = { m -> editableDays.forEach { onSetBudget(it, m) }; customAll = false },
                        )
                    }
                }
                @Composable
                fun budgetRow(dayType: DayType) {
                    val minutes = perDay[dayType.name]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(dayType.labelRes()), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Stepper(
                            valueLabel = minutes?.let { Duration.ofMinutes(it.toLong()).humanize() }
                                ?: stringResource(R.string.no_limit),
                            decrementEnabled = minutes != null,
                            onDecrement = {
                                val next = (minutes ?: 0) - 15
                                onSetBudget(dayType, if (next < 15) null else next)
                            },
                            onIncrement = { onSetBudget(dayType, (minutes ?: 0) + 15) },
                            enabled = enabled,
                        )
                    }
                }
                DAY_TYPES.forEach { budgetRow(it) }
                if (onSetSpecialDaysOwnRules != null && onOpenSpecialDays != null) {
                    SpecialDaysSection(
                        on = specialDaysOwnRules,
                        onOpenCalendar = onOpenSpecialDays,
                        enabled = enabled,
                        onChange = onSetSpecialDaysOwnRules,
                    ) { budgetRow(DayType.HOLIDAY) }
                }
            }
        }
    }
}
