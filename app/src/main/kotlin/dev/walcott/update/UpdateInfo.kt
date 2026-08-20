package dev.walcott.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The latest release, as described by the CI-published version.json. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): UpdateInfo? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** Pure update decision: newer version code than what's installed, and it has an APK url. */
fun UpdateInfo.isNewerThan(installedVersionCode: Int): Boolean =
    versionCode > installedVersionCode && apk.isNotBlank()

/**
 * The canary gate, pure: a child holds off installing [targetVersionCode] until the parent
 * is already running it, so one bad build can't take down the whole fleet silently. A parent
 * that doesn't report its build (0 = legacy) gates nothing, and a forced update ("Update
 * now" from the parent) bypasses this at the call site.
 */
fun waitsForParent(targetVersionCode: Int, parentVersionCode: Int): Boolean =
    parentVersionCode > 0 && targetVersionCode > parentVersionCode

/** What an APK file on disk says it is. See [dev.walcott.update.Updater.apkIdentity]. */
data class ApkIdentity(val packageName: String, val versionCode: Int, val versionName: String)

/**
 * Whether an APK file on disk may be handed to the installer.
 *
 * Three questions, and every one of them has bitten: did it parse as an APK at all ([pkg] is
 * null when it did not — a captive portal's login page served with a 200, or a body cut short
 * when the connection dropped); is it THIS app (nothing else may be installed under our name);
 * and is it newer than the build already running (the installer refuses a downgrade anyway, so
 * retrying one forever is just how a device gets stuck re-downloading fifty megabytes).
 *
 * Deliberately "newer than installed" rather than "exactly what version.json promised": if the
 * release moved on mid-download, the newer APK that arrived is still progress, and refusing it
 * would strand the device on the older build for no reason.
 */
fun apkIsInstallable(
    pkg: String?,
    apkVersionCode: Int,
    ourPackage: String,
    installedVersionCode: Int,
): Boolean = pkg == ourPackage && apkVersionCode > installedVersionCode

/**
 * Whether the downloaded APK survives an install that did not succeed.
 *
 * ABORTED is somebody tapping "Cancel" — by reflex at least as often as on purpose — and
 * BLOCKED is a policy or another installer standing in the way. In both, the bytes are perfectly
 * good and the retry should cost nothing, which is the whole point: throwing the APK away turned
 * one mis-tap into another full download, and that download only happens on the next scheduled
 * check. Anything else means the file, the storage or the device is the problem, and keeping
 * fifty megabytes of it helps nobody.
 */
fun keepsApkAfterFailure(status: Int): Boolean =
    status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED ||
        status == android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED

/** What a check does next, once it knows everything it can learn without acting. */
enum class UpdateStep {
    NOTHING_TO_DO,
    WAIT_FOR_PARENT,
    /** An APK for this update is already on disk and checked: install it, download nothing. */
    INSTALL_STAGED,
    UNTRUSTED_URL,
    WAIT_FOR_WIFI,
    NEED_SPACE,
    DOWNLOAD,
}

/**
 * The order in which one update check makes its decisions, as a table rather than as the shape
 * of some function — because the order IS the behaviour, and every line of it was earned:
 *
 *  - the canary gate outranks everything below it, so a child never runs ahead of the parent
 *    even with an APK sitting ready;
 *  - but a staged APK outranks every network consideration under it, because bytes already on
 *    disk cost no data, need no Wi-Fi and need no url to be trusted — they were checked when
 *    they arrived. This is what makes "I cancelled the prompt by mistake" a one-tap fix
 *    instead of another fifty-megabyte download on the next scheduled check;
 *  - [force] — a parent's explicit "Update now", or the button somebody is standing in front
 *    of — overrides the two gates that are policy (the canary, Wi-Fi-only) and none of the
 *    three that are facts (an untrusted url, a full disk, a build that isn't newer).
 */
fun nextUpdateStep(
    isNewer: Boolean,
    parentIsBehind: Boolean,
    hasStagedApk: Boolean,
    trustedUrl: Boolean,
    metered: Boolean,
    enoughSpace: Boolean,
    force: Boolean,
): UpdateStep = when {
    !isNewer -> UpdateStep.NOTHING_TO_DO
    !force && parentIsBehind -> UpdateStep.WAIT_FOR_PARENT
    hasStagedApk -> UpdateStep.INSTALL_STAGED
    !trustedUrl -> UpdateStep.UNTRUSTED_URL
    !force && metered -> UpdateStep.WAIT_FOR_WIFI
    !enoughSpace -> UpdateStep.NEED_SPACE
    else -> UpdateStep.DOWNLOAD
}
