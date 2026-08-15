plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.walcott"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.walcott"
        minSdk = 29
        targetSdk = 35
        versionCode = 110
        versionName = "0.57.0-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Stable key so in-place auto-updates chain across releases. Committed on purpose
        // (beta family app, no secrets). CI can override via SIGNING_* env if a secret is set.
        create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "../walcott-release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "walcott"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "walcott"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "walcott"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Coverage for the JVM unit tests: the policy layer (settings -> rules -> the set
            // the loop suspends) lives in this module, and it was the one part of the app's
            // brain the aggregated report couldn't see.
            enableUnitTestCoverage = true
            // The release key, on purpose. The instrumented tests exercise Device Owner
            // behaviour, and a Device Owner can only be replaced by a build with its own
            // signature — so a debug APK signed with the debug key cannot be installed over
            // the app it is meant to test. (The keystore is committed for this family beta;
            // see the release signingConfig above.)
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    // The exported Room schemas, so MigrationTestHelper can open a v1 database and walk it up
    // the real migration chain instead of trusting that the chain exists.
    sourceSets.getByName("androidTest") {
        assets.srcDir(files("$projectDir/schemas"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-rules"))
    implementation(project(":core-sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.osmdroid.android)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Instrumented tests: what only a real device can answer — Device Owner suspension, Room
    // migrations against a real SQLite file, DataStore surviving a real read.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.room.testing)
}
