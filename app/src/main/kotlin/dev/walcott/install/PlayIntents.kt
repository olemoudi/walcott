package dev.walcott.install

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Builds the intent for an app's Play Store page (shared by the install prompt paths). */
object PlayIntents {

    /** Prefers the Play app; falls back to the Play website if Play isn't installed. */
    fun storePage(context: Context, pkg: String): Intent {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            .setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (market.resolveActivity(context.packageManager) != null) return market
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$pkg"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    const val PLAY_STORE = "com.android.vending"

    /**
     * Google Play on its results for [query], or on its front page when there is nothing to search.
     *
     * For a child asking for an app: the page they land on is one tap from the app's own page,
     * which is where Play's Share button is — and sharing from there is what reaches the parent as
     * the exact app, the only kind of request a parent can act on (see ShareInstallActivity).
     * Falls back to the Play website, which shares the same way from a browser.
     */
    fun search(context: Context, query: String): Intent {
        val q = query.trim()
        if (q.isEmpty()) {
            context.packageManager.getLaunchIntentForPackage(PLAY_STORE)
                ?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val encoded = Uri.encode(q)
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded&c=apps"))
            .setPackage(PLAY_STORE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (market.resolveActivity(context.packageManager) != null) return market
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$encoded&c=apps"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
