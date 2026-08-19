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
        Release(130, "0.77.0-beta", R.array.whats_new_0_77_0),
        Release(125, "0.72.0-beta", R.array.whats_new_0_72_0),
        Release(124, "0.71.0-beta", R.array.whats_new_0_71_0),
        Release(116, "0.63.0-beta", R.array.whats_new_0_63_0),
        Release(115, "0.62.0-beta", R.array.whats_new_0_62_0),
        Release(114, "0.61.0-beta", R.array.whats_new_0_61_0),
        Release(113, "0.60.0-beta", R.array.whats_new_0_60_0),
        Release(112, "0.59.0-beta", R.array.whats_new_0_59_0),
        Release(111, "0.58.0-beta", R.array.whats_new_0_58_0),
        Release(110, "0.57.0-beta", R.array.whats_new_0_57_0),
        Release(109, "0.56.0-beta", R.array.whats_new_0_56_0),
        Release(108, "0.55.0-beta", R.array.whats_new_0_55_0),
        Release(107, "0.54.0-beta", R.array.whats_new_0_54_0),
        Release(106, "0.53.0-beta", R.array.whats_new_0_53_0),
        Release(105, "0.52.0-beta", R.array.whats_new_0_52_0),
        Release(104, "0.51.0-beta", R.array.whats_new_0_51_0),
        Release(103, "0.50.0-beta", R.array.whats_new_0_50_0),
        Release(102, "0.49.0-beta", R.array.whats_new_0_49_0),
        Release(101, "0.48.0-beta", R.array.whats_new_0_48_0),
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
