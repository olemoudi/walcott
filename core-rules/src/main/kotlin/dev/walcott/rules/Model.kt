package dev.walcott.rules

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class DayType { SCHOOL, WEEKEND, HOLIDAY }

/**
 * Keys for the extra-time map. Extra time is granted to a single app (its package name) or to
 * every app at once ([ExtraTime.ALL_APPS], a sentinel that is not a package name).
 */
object ExtraTime {
    const val ALL_APPS = "__all_apps__"

    /**
     * Minutes the child EARNED by leaving the phone alone (see [IdleEarnConfig]), as opposed to
     * minutes a parent handed out.
     *
     * Its own key because the two are answers to different things, and one rule tells them
     * apart: a blanket "everybody gets thirty minutes" must not blow past a budget somebody set
     * for one app on purpose, but earned time is not a blanket grant — it is a standing rule the
     * family wrote, whose entire promise is that putting the phone down buys screen time. Filed
     * under [ALL_APPS] it kept that promise only for apps running on the family default: a
     * family with per-app limits was shown "you earned 20 minutes" that bought nothing at all,
     * anywhere. Earned minutes therefore reach every app's budget, and the day's total with it.
     */
    const val EARNED = "__earned__"

    /** The keys that are not package names, for anything that has to tell them apart. */
    val SENTINELS = setOf(ALL_APPS, EARNED)
}

/**
 * Everything this phone has been used for today, added up — what a daily screen total spends.
 *
 * Derived from the counters rather than carried alongside them, so there is nothing for a caller
 * to forget to pass and no second number that can disagree with the first. It is the same sum
 * the child's own home screen prints as "screen time today".
 */
object ScreenTime {
    fun of(usageToday: Map<String, Duration>): Duration =
        usageToday.values.fold(Duration.ZERO, Duration::plus)
}

/** Parent-editable holidays, vacations and weekend edges; decides the day type. */
data class SchoolCalendar(
    val holidays: Set<LocalDate> = emptySet(),
    val vacations: List<ClosedRange<LocalDate>> = emptyList(),
    /**
     * Friday time from which the weekend rules already apply (school is out). Null — the
     * default — keeps the weekend starting at Saturday 00:00.
     */
    val weekendStartsFriday: LocalTime? = null,
    /**
     * Sunday time from which the weekday rules apply again (school night). Null — the
     * default — keeps the weekend running to Monday 00:00.
     */
    val weekendEndsSunday: LocalTime? = null,
) {
    /**
     * The day type in force at [now]. Deliberately NOT a function of the date alone: with a
     * weekend edge set, one date carries two day types (Friday is SCHOOL in the morning and
     * WEEKEND after the cut). Special days still win over both edges — a holiday is a holiday
     * all day long.
     *
     * The usage counter behind budgets is per calendar day, so it does NOT restart at the cut:
     * time already spent on Friday counts against the weekend budget the child moves into.
     */
    fun dayTypeOf(now: LocalDateTime): DayType {
        val date = now.toLocalDate()
        if (date in holidays || vacations.any { date in it }) return DayType.HOLIDAY
        val time = now.toLocalTime()
        return when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.WEEKEND
            DayOfWeek.SUNDAY ->
                if (weekendEndsSunday != null && time >= weekendEndsSunday) DayType.SCHOOL else DayType.WEEKEND
            DayOfWeek.FRIDAY ->
                if (weekendStartsFriday != null && time >= weekendStartsFriday) DayType.WEEKEND else DayType.SCHOOL
            else -> DayType.SCHOOL
        }
    }
}

/** Time window [start, end); may cross midnight (e.g. 21:30–07:30). */
/**
 * What a time window does when the calendar calls the day special (a holiday, a vacation day).
 *
 * The day-of-week picker on a rule and the calendar's special days are different questions —
 * "which weekdays" and "except when the day is not a normal one" — so they are answered
 * separately, on the same rule, instead of by filing the rule into one of several lists.
 */
enum class SpecialDays {
    /** The weekday filter is the whole answer; a holiday changes nothing. */
    ALWAYS,

    /** Stands down on special days: "no screens Mon–Fri 17:00–19:00 for homework", not on a
     *  bank-holiday Tuesday. */
    NEVER,

    /** Applies ONLY on special days — the rule a family wants for holidays and no other day. */
    ONLY,
    ;

    /** Whether a window carrying this applies on a day the calendar calls [special]. */
    fun appliesOn(special: Boolean): Boolean = when (this) {
        ALWAYS -> true
        NEVER -> !special
        ONLY -> special
    }
}

data class TimeWindow(
    val start: LocalTime,
    val end: LocalTime,
    /**
     * Days of the week this window applies on; empty — the default, and what every window
     * written before this field existed means — is every day.
     */
    val days: Set<DayOfWeek> = emptySet(),
    /**
     * How this window treats the calendar's special days (holidays, vacations).
     *
     * Three states rather than the boolean it replaced, because the editor used to express the
     * third by putting a whole separate LIST of windows under a "special days" section — one
     * more axis on a screen that already had two. [SpecialDays.ONLY] says the same thing on the
     * axis the parent is already using, so the section could go.
     */
    val specialDays: SpecialDays = SpecialDays.ALWAYS,
    /**
     * The apps this window LEAVES OPEN. Empty — the default, and what every window written
     * before this field existed means — closes everything non-essential.
     *
     * The rule a family actually has and could not write down. "Homework, 17:00 to 19:00" is
     * almost never "nothing at all": it is nothing except the dictionary, the calculator and
     * whatever they are listening to. Without this the choice was between a window that took the
     * homework tools away with everything else, or no window at all — and every family that met
     * that choice picked the second one.
     *
     * A list of what stays OPEN rather than what closes, because the open list is the short one
     * and the one a parent can hold in their head; the closed list is "everything else", which
     * is also what it must keep meaning as apps are installed.
     */
    val allowedPackages: Set<String> = emptySet(),
) {

    /** Whether this window leaves [packageName] open (see [allowedPackages]). */
    fun allows(packageName: String): Boolean = packageName in allowedPackages
    operator fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end
        else time >= start || time < end

    /**
     * Whether this window is in force at [at]: the time range AND the day filters. Callers that
     * only care about the clock (bedtime, earn windows) keep using [contains].
     *
     * [specialDay] is whether [at]'s own date is a holiday or vacation. For the post-midnight
     * tail of a window that crossed over, that is the date of the morning being blocked — the
     * day the parent is thinking about when they say "not on holidays".
     */
    fun appliesAt(at: LocalDateTime, specialDay: Boolean = false): Boolean {
        if (at.toLocalTime() !in this) return false
        if (!specialDays.appliesOn(specialDay)) return false
        if (days.isEmpty()) return true
        // A window that crosses midnight belongs to the day it STARTED on: at 01:00 inside a
        // 21:30–07:30 window, the day the parent picked is yesterday's.
        val startedYesterday = start > end && at.toLocalTime() < end
        return (if (startedYesterday) at.toLocalDate().minusDays(1).dayOfWeek else at.dayOfWeek) in days
    }
}

/**
 * The date the bedtime window covering [now] starts on — "which night is this".
 *
 * A bedtime normally crosses midnight, so 23:00 on Friday and 06:00 on Saturday are the same
 * night and a one-off change to it has to reach both halves. Anything else (including a window
 * that does not cross) belongs to the day it is read on.
 */
fun TimeWindow.nightOf(now: LocalDateTime): LocalDate =
    if (start > end && now.toLocalTime() < end) now.toLocalDate().minusDays(1) else now.toLocalDate()

/** How long this window lasts, wrapping past midnight when it crosses. */
fun TimeWindow.lengthMinutes(): Long {
    val from = start.toSecondOfDay() / 60L
    val to = end.toSecondOfDay() / 60L
    return if (to >= from) to - from else 24 * 60L - from + to
}

/**
 * A one-off change to today, on top of the rules — the thing a family needs constantly and the
 * rules cannot express, because a rule is a statement about every day of its kind.
 *
 * Two shapes, and both exist because the alternative was editing a standing rule and remembering
 * to put it back: **a pause** ("dinner is ready", "put it down and come here") that closes the
 * phone until a moment, and **tonight's bedtime** moved back or lifted, for the birthday, the
 * film, the night the grandparents are staying.
 *
 * Everything here dies on its own — a pause when its moment passes, a bedtime change when its
 * night does — so nothing has to be undone by hand and no exception can be forgotten into a
 * permanent rule. Essential apps are untouched by both: a paused phone still calls its parents.
 *
 * A pause is measured against the device's own clock, which a child could move. That is
 * deliberately not defended here: any family with a rule of any kind already fails closed on a
 * clock the sync server disagrees with (see [RuleEngine.requiresTrustedClock]), and a family with
 * no rules at all is not one where minutes are being fought over.
 */
data class TodayException(
    /** Everything non-essential is closed until this moment; null = no pause running. */
    val pauseUntil: LocalDateTime? = null,
    /**
     * The night the bedtime change below applies to (see [nightOf]); null = tonight's bedtime is
     * whatever the rules say. Dated rather than a flag so an exception cannot outlive its night:
     * a phone that was off all evening reads yesterday's exception as spent, not as tonight's.
     */
    val bedtimeNight: LocalDate? = null,
    /**
     * Minutes tonight's bedtime starts later than usual — or EARLIER, when negative.
     *
     * Both directions, because a parent has both answers. "Half an hour more, it's a Friday" was
     * the only one this could say; "bed half an hour early, you were up all night" had to be
     * done by editing the standing rule and remembering to put it back, which is exactly what
     * every other one-off here exists to avoid.
     */
    val bedtimeDelayMinutes: Int = 0,
    /** No bedtime at all on [bedtimeNight]. */
    val bedtimeOff: Boolean = false,
) {
    /** Whether the phone is closed by a pause at [now]. */
    fun pausedAt(now: LocalDateTime): Boolean = pauseUntil != null && now.isBefore(pauseUntil)

    /** Nothing set at all — the ordinary state of a day. */
    val isEmpty: Boolean get() = pauseUntil == null && bedtimeNight == null
}

/**
 * What one app is allowed, whether it was set for that app or inherited from the family's
 * default. Every limit in this engine is now per app: sorting apps into categories asked the
 * parent to do a filing job before they could set a single rule, and the rules they actually
 * want ("Roblox, 45 minutes") never needed it.
 */
data class AppPolicy(
    /** Daily budget per day type; no entry = no time limit that day. */
    val dailyBudget: Map<DayType, Duration> = emptyMap(),
    /** Full-block windows per day type (e.g. school hours). */
    val blockedWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
    /**
     * This app answers to no daily budget, not even the family default. The third state a
     * per-app entry needs: "nothing set" inherits the default, a budget overrides it, and this
     * opts out of it — the app the parent never wants to cut off (a bus timetable, a chat with
     * a parent) without having to turn the default off for everybody.
     */
    val unlimited: Boolean = false,
    /**
     * Manage this app even though it SHIPS WITH THE PHONE — opt-in, one app at a time.
     *
     * A phone only ever manages what the family installed on it, because a device owner
     * suspending system packages is how a phone stops working: the launcher, the keyboard, the
     * thing that grants permissions. But the app a day actually disappears into is usually
     * preinstalled — the browser, the video app, the gallery — so the rule a parent cared most
     * about was the one rule this product could not keep, and it could be saved anyway.
     *
     * Deliberately per app rather than a mode. There is no list of "safe" system apps that is
     * true of every phone, so the honest form is a parent naming one and being told whether the
     * phone allowed it (see `Enforcer.recentSuspendFailures`).
     *
     * Meaningless for an app the family installed: those are managed regardless, and the flag
     * on one is simply ignored.
     */
    val manageSystemApp: Boolean = false,
)

data class FamilyConfig(
    /** Monotonic version of the writer; sync uses last-write-wins on it. */
    val version: Long,
    /**
     * The daily budget an app gets when nothing was set for it, per day type. Empty — the
     * default — means an app nobody has touched has no time limit at all, which is the whole
     * point: a newly installed app must not silently arrive already restricted.
     *
     * Each app counts against this budget SEPARATELY: it is a per-app allowance, not a shared
     * pot, so an hour of one app does not eat another app's hour.
     */
    val defaultAppBudget: Map<DayType, Duration> = emptyMap(),
    /**
     * How long the phone may be used for IN TOTAL on a day of each kind. Empty — the default —
     * means there is no total at all.
     *
     * The rule every family expects to exist and this one did not have. Limits are per app and
     * each spends its own clock, which is right for "an hour of games" but adds up to a phone
     * with no ceiling: ten apps at an hour each is a ten-hour day, and the parent who set those
     * ten hours believed they had set one. This is the ceiling, and it is deliberately a
     * SEPARATE knob rather than a change to how per-app budgets work — a family that wants ten
     * clocks keeps them, and a family that wants one number sets one number.
     *
     * What spends it is every minute the phone counts ([ScreenTime]), including apps with no
     * limit of their own. What does not answer to it: essential apps, which answer to nothing,
     * and apps marked [AppPolicy.unlimited], whose whole meaning is "never cut this one off".
     */
    val dailyScreenBudget: Map<DayType, Duration> = emptyMap(),
    /**
     * package -> the rules set for that app specifically. A budget here replaces
     * [defaultAppBudget] for that app (tighter or looser); [AppPolicy.unlimited] removes it;
     * blocked windows are added on top of the family-wide ones.
     */
    val perAppPolicies: Map<String, AppPolicy> = emptyMap(),
    /** Bedtime window per day type: blocks everything non-essential. */
    val bedtime: Map<DayType, TimeWindow> = emptyMap(),
    /**
     * Family-wide full-block windows per day type (homework, meals…): like bedtime they
     * block every non-essential app, but there can be any number of them per day.
     */
    val blockedWindows: Map<DayType, List<TimeWindow>> = emptyMap(),
    /** Never blocked: phone, contacts, the app itself… */
    val essentialPackages: Set<String> = emptySet(),
    /**
     * The apps a child reaches a PERSON with beyond the phone and contacts — the messaging app.
     *
     * Exempt from the two limits nobody wrote about them: the family default budget ("every app
     * gets an hour") and the day's total. Both are rules about apps in general, and an app in
     * general is not what these are: a limit that stops a child texting their parent is one
     * nobody chose and nobody would have chosen, arrived at by a number typed on another screen.
     *
     * Deliberately NOT exempt from everything, which is what [essentialPackages] is for. A
     * budget or a window set on one of these BY NAME applies, and so do bedtime and a pause —
     * those are somebody deciding about this phone tonight, not a default catching an app it was
     * never asked about. The screen that sets such a limit says what it will do.
     */
    val reachOutPackages: Set<String> = emptySet(),
    val calendar: SchoolCalendar = SchoolCalendar(),
    /** Today's one-off change to the two rules above, if the parent made one. */
    val todayException: TodayException = TodayException(),
) {
    /**
     * Tonight's bedtime at [now]: the configured window, after whatever [todayException] says
     * about this night. Null when there is no bedtime — configured or left.
     *
     * Every reader of a bedtime goes through here, for the reason [allowanceFor] exists: the
     * engine, the child's screen and the parent's "what is stopping them" list all draw the same
     * window, and a one-off change that reached only one of them would be a screen disagreeing
     * with the phone it describes.
     */
    /**
     * Tonight's bedtime as the RULES have it, before any exception — which is how you ask "which
     * night is this?" of a night whose bedtime has just been lifted.
     *
     * The distinction is not academic. [bedtimeAt] answers null for a lifted night, and a caller
     * deriving the night from that answer falls back to today's date — so at half past midnight
     * it looks at the night that has not started yet, decides the exception it is holding belongs
     * to some other night, and offers the parent no way to put back what they just lifted.
     */
    fun scheduledBedtimeAt(now: LocalDateTime): TimeWindow? = bedtime[calendar.dayTypeOf(now)]

    fun bedtimeAt(now: LocalDateTime): TimeWindow? {
        val window = scheduledBedtimeAt(now) ?: return null
        if (todayException.bedtimeNight != window.nightOf(now)) return window
        if (todayException.bedtimeOff) return null
        val delay = todayException.bedtimeDelayMinutes
        if (delay == 0) return window
        // A delay longer than the night itself leaves no bedtime at all, rather than a window
        // that has crawled past its own end and blocks the whole of the next day. Only ever a
        // question for a LATER bedtime: moving the start backwards lengthens the night, it
        // cannot swallow it.
        if (delay >= window.lengthMinutes()) return null
        return window.copy(start = window.start.plusMinutes(delay.toLong()))
    }

    /**
     * The budget [packageName] answers to on [dayType], or null when it has none: its own if it
     * was given one, otherwise the family default — unless it was explicitly set free.
     *
     * An essential app (the phone, Walcott itself) never has one. Stated here and not only in
     * [RuleEngine.evaluate] so that everything reading a budget agrees: a child's screen must
     * not draw "Phone · 20 min left" over an app that will keep working regardless.
     */
    fun budgetFor(packageName: String, dayType: DayType): Duration? {
        if (packageName in essentialPackages) return null
        val own = perAppPolicies[packageName]
        if (own?.unlimited == true) return null
        // The family default is a statement about apps in general, and the app a child texts
        // their parent from is not one: it answers to a budget somebody set for IT, and to
        // nothing else (see reachOutPackages).
        if (packageName in reachOutPackages) return own?.dailyBudget?.get(dayType)
        return own?.dailyBudget?.get(dayType) ?: defaultAppBudget[dayType]
    }

    /**
     * The preinstalled apps this family asked to manage anyway (see [AppPolicy.manageSystemApp]).
     *
     * The set, not the question, because the device asks it once per inventory refresh and the
     * answer decides what it may suspend at all.
     */
    fun managedSystemPackages(): Set<String> =
        perAppPolicies.filterValues { it.manageSystemApp }.keys - essentialPackages

    /**
     * The phone's whole allowance for a day of this kind — the total plus whatever extra time
     * widens it — or null when the family has set no total.
     *
     * Both kinds of extra reach it, and for the same reason they reach an app on the family
     * default: "everyone gets another half hour" and "you earned twenty minutes" are both
     * statements about how long the phone may be used for today, which is exactly what this is.
     */
    fun screenAllowanceAt(dayType: DayType, extraTime: Map<String, Duration> = emptyMap()): Duration? {
        val budget = dailyScreenBudget[dayType] ?: return null
        return budget +
            (extraTime[ExtraTime.ALL_APPS] ?: Duration.ZERO) +
            (extraTime[ExtraTime.EARNED] ?: Duration.ZERO)
    }

    /**
     * How much of the day's total is left at [screenToday] — negative or zero when it is spent,
     * null when there is no total to spend.
     */
    fun screenTimeLeftAt(
        dayType: DayType,
        screenToday: Duration,
        extraTime: Map<String, Duration> = emptyMap(),
    ): Duration? = screenAllowanceAt(dayType, extraTime)?.minus(screenToday)

    /** Whether [packageName] is running on the family default rather than a budget of its own. */
    fun usesDefaultBudget(packageName: String): Boolean {
        val own = perAppPolicies[packageName] ?: return true
        return !own.unlimited && own.dailyBudget.isEmpty()
    }

    /**
     * Everything [packageName] may spend today: its budget plus whatever extra time reaches it.
     * Null when it has no budget at all, which is not the same as zero.
     *
     * The widening rule lives here so that every reader of an allowance agrees on it — a grant
     * to this app always counts, an "all apps" grant only reaches apps running on the family
     * default (see [RuleEngine.evaluate]). It was written out twice before, once in the engine
     * and once in the screen's [appStatus], which is exactly how a screen comes to disagree with
     * the phone it is describing.
     */
    fun allowanceFor(
        packageName: String,
        dayType: DayType,
        extraTime: Map<String, Duration> = emptyMap(),
    ): Duration? {
        val budget = budgetFor(packageName, dayType) ?: return null
        val own = extraTime[packageName] ?: Duration.ZERO
        val shared =
            if (usesDefaultBudget(packageName)) extraTime[ExtraTime.ALL_APPS] ?: Duration.ZERO
            else Duration.ZERO
        // Earned time reaches every budget, including one set for this app on purpose — see
        // [ExtraTime.EARNED] for why it is not filed with the blanket grants.
        val earned = extraTime[ExtraTime.EARNED] ?: Duration.ZERO
        return budget + own + shared + earned
    }
}

sealed interface Verdict {
    /** Allowed with no applicable time limit right now. */
    data object Allowed : Verdict

    /** Allowed; this app has this much time left today. */
    data class AllowedWithBudget(val remaining: Duration) : Verdict

    data class Blocked(val reason: BlockReason) : Verdict
}

enum class BlockReason {
    BEDTIME,
    BLOCKED_WINDOW,
    BUDGET_EXHAUSTED,

    /**
     * The phone's own time for today is spent (see [FamilyConfig.dailyScreenBudget]).
     *
     * Its own reason rather than a spent budget, because the child is owed the true sentence and
     * the two have different answers: an app out of time is answered by minutes for that app, a
     * phone out of time by minutes for the phone. Told as a budget, a child would go looking for
     * the app that ran out and find every one of them at zero.
     */
    SCREEN_BUDGET,

    /**
     * A parent paused this phone until a moment they picked (see [TodayException]).
     *
     * Its own reason rather than a screen-free window, because the child is owed the true
     * sentence: a window is a standing rule they can learn, and this is a person, just now,
     * asking for the phone to be put down. It also ends by itself, at a time worth printing.
     */
    PAUSED,

    /**
     * Blocked because the device can't be trusted to apply the rules right now — the usage
     * counter is unavailable, or the clock is provably wrong. See
     * [RuleEngine.blockedPackages]'s fail-closed branches.
     */
    FAIL_CLOSED,
}
