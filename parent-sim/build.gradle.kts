plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

/**
 * Test tooling: a programmatic parent and a local relay, so the child half — the half that has
 * to run on Android — can be exercised against a real family without a second phone.
 *
 * Nothing in `:app` depends on this module, and nothing here depends on `:app`: it talks the
 * wire format from `:core-sync` and nothing else, which is exactly why it can be a plain JVM
 * program. Keep it that way — the moment this needs an Android type, the parent it simulates
 * has stopped being pure logic and the premise is wrong.
 */
dependencies {
    implementation(project(":core-sync"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.walcott.sim.MainKt")
}

// Gradle hands a JavaExec an empty stdin unless told otherwise, so the CLI's whole command loop
// read end-of-input and exited immediately — it looked like it had simply done nothing.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

/**
 * The default test task is the hermetic half: relay and parent talking to each other on this
 * machine, no device involved. It runs in CI, so it must never need an emulator — the
 * device-driven scenarios are tagged "e2e" and excluded here.
 */
tasks.test {
    useJUnitPlatform { excludeTags("e2e") }
}

/**
 * The device half: `./gradlew :parent-sim:e2eTest` with an emulator attached and the debug APK
 * installed (see E2E_README.md). Deliberately not wired into `check` — CI has no device, and a
 * suite that is skipped by default is worse than one you have to ask for.
 */
tasks.register<Test>("e2eTest") {
    description = "Parent-sim end-to-end scenarios against a real child device (needs adb + emulator)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // "destructive" is excluded here rather than untagged, so it is impossible to run it by
    // accident: it ends with the device no longer being Device Owner, which every other scenario
    // needs. See e2eReleaseTest.
    useJUnitPlatform { includeTags("e2e"); excludeTags("destructive") }
    // Each scenario pairs a device and waits on real round trips; the default 'up-to-date'
    // shortcut would silently skip a suite whose whole point is that the device changed.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    // A suite whose every scenario skipped its preconditions passes the build while proving
    // nothing — the most expensive kind of green there is. Count what actually ran and refuse
    // to call it a success if that is zero.
    var executed = 0
    afterTest(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ _, result ->
            if (result.resultType != TestResult.ResultType.SKIPPED) executed++
        }),
    )
    doLast {
        if (executed == 0) {
            throw GradleException(
                "no scenario ran: every one skipped its preconditions (device attached? " +
                    "debug build installed? network up?). A skipped suite is not a passing suite.",
            )
        }
    }
}

/**
 * The scenarios that leave the device changed: today, the parent freeing a phone for good, which
 * gives up Device Owner. Run it LAST, on its own — it re-provisions afterwards and fails loudly
 * if it cannot, because a device that is no longer managed makes every other scenario skip.
 */
tasks.register<Test>("e2eReleaseTest") {
    description = "Device-changing parent-sim scenarios (gives up Device Owner; run last)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("destructive") }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
