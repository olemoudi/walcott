pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "walcott"
include(":app")
include(":core-rules")
include(":core-sync")
// Test tooling only: a programmatic parent + a local relay, so the child can be exercised
// against a real family without a second phone. Nothing depends on it (see its build file).
include(":parent-sim")
