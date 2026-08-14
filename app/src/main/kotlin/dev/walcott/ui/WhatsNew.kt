package dev.walcott.ui

import dev.walcott.R

/**
 * What changed, per release, for the sheet an updated app shows once.
 *
 * Walcott updates itself silently — on a child device it cannot even be declined — so without
 * this a family's phones simply change behaviour one morning with no explanation. That is a poor
 * deal in a beta a real family is living in.
 *
 * The text is a `string-array` resource rather than literals, which is what makes it appear in
 * whatever language the app is running in: the same resource resolution as every other string,
 * so a phone set to Spanish (or one using Android's per-app language for Walcott) gets the
 * Spanish list without this code knowing anything about locales.
 *
 * Pure and Android-free apart from the resource ids, so [entriesFor] is unit-tested.
 */
object WhatsNew {

    /** One shipped release worth telling people about. */
    data class Release(val versionCode: Int, val name: String, val bulletsRes: Int)

    /**
     * Newest first. A release with nothing user-visible to say simply isn't listed — the sheet
     * then has nothing to show and doesn't appear.
     */
    val RELEASES: List<Release> = listOf(
        Release(100, "0.47.0-beta", R.array.whats_new_0_47_0),
        Release(99, "0.46.0-beta", R.array.whats_new_0_46_0),
        Release(98, "0.45.0-beta", R.array.whats_new_0_45_0),
        Release(97, "0.44.0-beta", R.array.whats_new_0_44_0),
        Release(96, "0.43.0-beta", R.array.whats_new_0_43_0),
        Release(95, "0.42.0-beta", R.array.whats_new_0_42_0),
        Release(94, "0.41.0-beta", R.array.whats_new_0_41_0),
        Release(93, "0.40.0-beta", R.array.whats_new_0_40_0),
        Release(92, "0.39.0-beta", R.array.whats_new_0_39_0),
        Release(91, "0.38.0-beta", R.array.whats_new_0_38_0),
    )

    /**
     * What to show on a device that last saw [lastSeenVersionCode] and now runs [currentVersionCode]:
     * every release newer than the one last seen, newest first.
     *
     * Empty on a fresh install ([lastSeenVersionCode] 0), because a first launch is not an
     * update and a list of changes from a version they never ran is noise at the worst moment.
     * Empty too when nothing is newer — including on a downgrade, where the honest answer is
     * that we have nothing to announce.
     */
    fun entriesFor(
        lastSeenVersionCode: Int,
        currentVersionCode: Int,
        releases: List<Release> = RELEASES,
    ): List<Release> {
        if (lastSeenVersionCode <= 0) return emptyList()
        return releases
            .filter { it.versionCode in (lastSeenVersionCode + 1)..currentVersionCode }
            .sortedByDescending { it.versionCode }
    }
}
