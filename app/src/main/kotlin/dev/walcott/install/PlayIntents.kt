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
}
