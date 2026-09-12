# Release checklist

What "cutting a release" means for this app, in order. The device suites are not optional:
they are the only proof that a phone can be freed, and that is the one thing a release of a
parental-control app must never break.

1. `export JAVA_HOME=~/.jdks/jdk-17.0.19+10` (java is not on the PATH here).
2. `./gradlew test` — every JVM suite green.
3. Emulator up and awake, Device Owner provisioned, the **debug build signed with the release
   key and its lineage** installed (`scripts/sign-apk.sh`, see `parent-sim/README.md` and
   `docs/signing.md`). Do not rebuild while a suite is running.
4. `./gradlew :parent-sim:e2eTest` — green, and it says how many scenarios ran.
   Then `adb shell am broadcast -n dev.walcott/.debug.PolicySeedReceiver --es mode provisioning_checksum`
   and check the log says `match=true`: the enrollment QR's checksum is what Android's provisioning
   will compare against the published APK (see `DeviceOwnerProvisioning.PUBLISHED_SIGNATURE_CHECKSUM`).
   No scenario enrolls a phone from the QR, and 0.107 to 0.111 shipped a QR no phone could use.
   Whenever signing or provisioning changed, also enroll one factory-reset phone from the QR.
5. `./gradlew :parent-sim:e2eReleaseTest` — green, **twice in a row**. Each scenario gives up
   Device Owner and puts it back; a red one is the scenario's fault until proven otherwise (see
   the four questions in `parent-sim/README.md`).
6. Shut the emulator down (`adb -s <serial> emu kill`).
7. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; write the What's New entry.
8. Commit, tag `vX.Y.Z-beta`, push the tag. CI builds the APK, signs it with the key in the
   `SIGNING_*` secrets plus the lineage, and publishes it with its `sha256` in `version.json`.
