# Release checklist

What "cutting a release" means for this app, in order. The device suites are not optional:
they are the only proof that a phone can be freed, and that is the one thing a release of a
parental-control app must never break.

1. `export JAVA_HOME=~/.jdks/jdk-17.0.19+10` (java is not on the PATH here).
2. `./gradlew test` — every JVM suite green.
3. Emulator up and awake, Device Owner provisioned, the **debug build signed with the release
   key** installed (see `parent-sim/README.md`). Do not rebuild while a suite is running.
4. `./gradlew :parent-sim:e2eTest` — green, and it says how many scenarios ran.
5. `./gradlew :parent-sim:e2eReleaseTest` — green, **twice in a row**. Each scenario gives up
   Device Owner and puts it back; a red one is the scenario's fault until proven otherwise (see
   the four questions in `parent-sim/README.md`).
6. Shut the emulator down (`adb -s <serial> emu kill`).
7. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; write the What's New entry.
8. Commit, tag `vX.Y.Z-beta`, push the tag. CI builds and publishes the signed APK.
