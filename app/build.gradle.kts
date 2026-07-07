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
        versionCode = 4
        versionName = "0.2.1"
    }

    signingConfigs {
        // Stable key so in-place auto-updates chain across releases. Committed on purpose
        // (alpha family app, no secrets). CI can override via SIGNING_* env if a secret is set.
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

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
