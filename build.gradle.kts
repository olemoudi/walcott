plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    jacoco
}

// Aggregated coverage over the pure logic modules (the app's brain). UI/service code needs
// instrumented tests, so it's intentionally out of this metric.
val coverageModules = listOf(":core-rules", ":core-sync")

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    dependsOn(coverageModules.map { "$it:test" })
    val projects = coverageModules.map { project(it) }
    executionData.setFrom(projects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
    sourceDirectories.setFrom(projects.map { it.layout.projectDirectory.dir("src/main/kotlin") })
    classDirectories.setFrom(projects.map { it.layout.buildDirectory.dir("classes/kotlin/main") })
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
