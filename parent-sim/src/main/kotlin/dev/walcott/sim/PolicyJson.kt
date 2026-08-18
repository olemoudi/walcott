package dev.walcott.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The rules, as the string that actually travels.
 *
 * The parent's settings type lives in `:app` and is therefore Android-bound, but the wire only
 * ever carries `policyJson` — an opaque string the child decodes with `ignoreUnknownKeys`. So
 * the sim builds that JSON directly instead of depending on the app, which is not a shortcut:
 * it is the same forward-compatibility contract every released child already relies on, and
 * building policies here by hand is how a scenario proves an old child survives a field it has
 * never seen.
 *
 * Only the keys a scenario needs are set; everything else takes the child's own defaults.
 */
object PolicyJson {

    private val json = Json { prettyPrint = false }

    /**
     * The smallest policy a child will accept. [version] is the POLICY's own counter, distinct
     * from the snapshot version that gates replay — both exist and confusing them produces a
     * child that silently ignores rules.
     */
    fun minimal(version: Long = 1): String = build(version)

    /** The day types a budget can be keyed by, matching the rules engine's own enum. */
    const val SCHOOL = "SCHOOL"
    const val WEEKEND = "WEEKEND"
    const val HOLIDAY = "HOLIDAY"

    /**
     * @param restrictions device restriction keys (see DeviceRestrictions: "installs",
     *   "apps_control", "unknown_sources", "datetime", "vpn", …)
     * @param dailyMinutes package -> minutes allowed on EVERY day type; 0 blocks it outright
     * @param unlimited packages explicitly never limited
     * @param bedtime start/end minute-of-day of the family's bedtime, on every day type
     * @param screenFree family-wide screen-free windows (start/end minute-of-day), every day type
     * @param children the family's member entries (see [childEntry]) — the only place the
     *   per-member fields live, today's pause among them
     * @param extra further raw keys, for exercising fields this helper doesn't model
     *
     * `appPolicies` entries are shaped as the child's `AppPolicyDto` — budgets keyed by day
     * type, not a flat number. Getting that wrong is silent: the child decodes with
     * `ignoreUnknownKeys`, so a misspelt field is not an error, it is a rule that never applies.
     */
    fun build(
        version: Long = 1,
        familyName: String = "Sim Family",
        restrictions: Set<String> = emptySet(),
        dailyMinutes: Map<String, Int> = emptyMap(),
        unlimited: Set<String> = emptySet(),
        bedtime: Pair<Int, Int>? = null,
        screenFree: List<Pair<Int, Int>> = emptyList(),
        children: List<JsonObject> = emptyList(),
        newAppAlerts: Boolean = true,
        // Any JSON value, not only objects: the fields worth reaching for by hand are as often a
        // bare flag ("keepRingerAudible") as a nested structure.
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): String {
        val root = buildJsonObject {
            put("version", version)
            put("familyName", familyName)
            put("newAppAlerts", newAppAlerts)
            put("deviceRestrictions", JsonArray(restrictions.map { JsonPrimitive(it) }))
            put("hardeningSeeded", true)
            if (dailyMinutes.isNotEmpty() || unlimited.isNotEmpty()) {
                put(
                    "appPolicies",
                    JsonObject(
                        dailyMinutes.mapValues { (_, minutes) ->
                            buildJsonObject {
                                put(
                                    "budgets",
                                    buildJsonObject {
                                        put(SCHOOL, minutes)
                                        put(WEEKEND, minutes)
                                        put(HOLIDAY, minutes)
                                    },
                                )
                            }
                        } + unlimited.associateWith { buildJsonObject { put("unlimited", true) } },
                    ),
                )
            }
            bedtime?.let { (start, end) ->
                put("bedtime", JsonObject(dayTypes.associateWith { window(start, end) }))
            }
            if (screenFree.isNotEmpty()) {
                val windows = JsonArray(screenFree.map { (start, end) -> window(start, end) })
                put("allAppsBlockedWindows", JsonObject(dayTypes.associateWith { windows }))
            }
            if (children.isNotEmpty()) put("children", JsonArray(children))
            extra.forEach { (key, value) -> put(key, value) }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * One member of the family, as the child decodes it (`ChildEntry`).
     *
     * A child resolves the policy against its OWN entry (`resolveForChild`), so anything written
     * here reaches that device and no other — which is the whole point of the fields it carries:
     * a pause is about one phone, not about a household.
     */
    fun childEntry(
        childId: String,
        name: String = "Sim Child",
        overrides: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = buildJsonObject {
        put("childId", childId)
        put("name", name)
        put("overrides", overrides)
    }

    /**
     * Today's one-off change for one member (`TodayExceptionDto`): everything closed until
     * [pauseUntilMs], and tonight's bedtime moved back or lifted.
     *
     * [bedtimeNightEpochDay] dates the bedtime half, because a night is not a day: an exception
     * for the night that began yesterday still has to reach 02:00 this morning.
     */
    fun todayException(
        pauseUntilMs: Long = 0,
        bedtimeNightEpochDay: Long = 0,
        bedtimeDelayMinutes: Int = 0,
        bedtimeOff: Boolean = false,
    ): JsonObject = buildJsonObject {
        put(
            "todayException",
            buildJsonObject {
                put("pauseUntilMs", pauseUntilMs)
                put("bedtimeNightEpochDay", bedtimeNightEpochDay)
                put("bedtimeDelayMinutes", bedtimeDelayMinutes)
                put("bedtimeOff", bedtimeOff)
            },
        )
    }

    private val dayTypes = listOf(SCHOOL, WEEKEND, HOLIDAY)

    /**
     * One window as the child decodes it (`WindowDto`), in minutes since midnight. An empty
     * `days` — the default — is every day, and a window whose end is before its start crosses
     * midnight, which is what a bedtime normally does.
     */
    private fun window(startMinute: Int, endMinute: Int): JsonObject = buildJsonObject {
        put("startMinute", startMinute)
        put("endMinute", endMinute)
    }
}
