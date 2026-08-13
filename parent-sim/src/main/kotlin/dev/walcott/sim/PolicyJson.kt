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
        newAppAlerts: Boolean = true,
        extra: Map<String, JsonObject> = emptyMap(),
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
            extra.forEach { (key, value) -> put(key, value) }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }
}
