package dev.walcott.data

import dev.walcott.sync.ChildSnapshot

/**
 * Aggregates the app lists reported by every child device into the parent's classification
 * catalog, preserving WHO has each app installed — the classification screen shows that as
 * per-child tags and filters. Pure, so it stays unit-testable.
 */
object AppCatalog {

    /** A child that has an app installed: [id] is the registry childId (deviceId for legacy). */
    data class Owner(val id: String, val name: String)

    data class Entry(
        val packageName: String,
        val label: String,
        val owners: List<Owner>,
        /**
         * Whether this app ships with the phone. True if ANY child reporting it says so: the
         * same package is preinstalled on one phone and sideloaded on another, and the flag
         * decides whether the parent is offered the switch that makes a limit on it real.
         */
        val system: Boolean = false,
        /** Whether any phone reporting it calls it an app for reaching a person. */
        val reachOut: Boolean = false,
        /** Whether any phone reporting it runs on it and never limits it (a keyboard, home, an alarm). */
        val alwaysAvailable: Boolean = false,
    )

    /**
     * One entry per package across all children, alphabetical by label; [registryNames] maps
     * childId to the parent-given name. Devices without a registry entry (legacy/anonymous)
     * fall back to the name they report for themselves.
     */
    fun build(snapshots: List<ChildSnapshot>, registryNames: Map<String, String>): List<Entry> =
        snapshots
            .flatMap { snapshot -> snapshot.apps.map { app -> app to snapshot } }
            .groupBy { it.first.packageName }
            .map { (packageName, hits) ->
                Entry(
                    packageName = packageName,
                    label = hits.first().first.label,
                    system = hits.any { it.first.system },
                    reachOut = hits.any { it.first.reachOut },
                    alwaysAvailable = hits.any { it.first.alwaysAvailable },
                    owners = hits.map { (_, snapshot) ->
                        Owner(
                            id = snapshot.childId.ifBlank { snapshot.deviceId },
                            name = registryNames[snapshot.childId]
                                ?.takeIf { snapshot.childId.isNotBlank() }
                                ?: snapshot.displayName,
                        )
                    }.distinctBy { it.id }.sortedBy { it.name.lowercase() },
                )
            }
            .sortedBy { it.label.lowercase() }
}
