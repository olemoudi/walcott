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
