plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    jacoco
}

// Aggregated coverage over the app's brain: the pure rule/sync modules, plus the parts of
// :app that JVM unit tests can reach — the policy mapping, the parent-write path and the
// suspension planner. UI and service code needs instrumented tests and stays out of the metric
// (androidTestSources below is what keeps :app's Compose code from diluting it).
val coverageModules = listOf(":core-rules", ":core-sync")

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    dependsOn(coverageModules.map { "$it:test" } + ":app:testDebugUnitTest")
    val projects = coverageModules.map { project(it) }
    val app = project(":app")
    executionData.setFrom(
        projects.map { it.layout.buildDirectory.file("jacoco/test.exec") } +
            app.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    )
    sourceDirectories.setFrom(
        projects.map { it.layout.projectDirectory.dir("src/main/kotlin") } +
            app.layout.projectDirectory.dir("src/main/kotlin"),
    )
    classDirectories.setFrom(
        projects.map { it.layout.buildDirectory.dir("classes/kotlin/main") } +
            // Only the classes a JVM test can actually execute: everything Compose-shaped is
            // unreachable here, and counting it would make the number meaningless.
            app.layout.buildDirectory.dir("tmp/kotlin-classes/debug").map { dir ->
                dir.asFileTree.matching {
                    include(
                        "dev/walcott/data/PolicySettings*", "dev/walcott/data/SetupPresets*",
                        "dev/walcott/data/ChildStats*", "dev/walcott/data/Pin*",
                        "dev/walcott/data/AppCatalog*",
                        // The suspension planner, not the Enforcer instance around it: the
                        // instance is device-policy calls, which only an instrumented test can
                        // reach, and counting them would drown the logic this metric is for.
                        "dev/walcott/enforcement/Enforcer\$Companion*",
                        "dev/walcott/enforcement/SuspensionPlan*",
                    )
                }
            },
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
