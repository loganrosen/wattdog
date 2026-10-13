package io.github.loganrosen.wattdog.alerts

import android.content.Intent
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind

/** Carries an in-app [SuggestedAction] through a notification's intent extras. */
object NotificationRoute {
    private const val ROUTE = "wattdog.route"
    private const val UID = "wattdog.uid"
    private const val TAG = "wattdog.tag"
    private const val KIND = "wattdog.kind"

    fun put(intent: Intent, action: SuggestedAction): Intent = intent.apply {
        when (action) {
            is SuggestedAction.ShowWakelocks -> {
                putExtra(ROUTE, "wakelocks")
                action.uid?.let { putExtra(UID, it.raw) }
                action.tag?.let { putExtra(TAG, it) }
                action.kind?.let { putExtra(KIND, it.name) }
            }

            is SuggestedAction.ShowApp -> {
                putExtra(ROUTE, "app")
                putExtra(UID, action.uid.raw)
            }

            SuggestedAction.ShowApps -> putExtra(ROUTE, "apps")

            SuggestedAction.ShowRadios -> putExtra(ROUTE, "radios")

            else -> Unit
        }
    }

    fun read(intent: Intent?): SuggestedAction? {
        val extras = intent?.extras ?: return null
        val uid = if (extras.containsKey(UID)) AppUid(extras.getInt(UID)) else null
        return when (extras.getString(ROUTE)) {
            "wakelocks" -> SuggestedAction.ShowWakelocks(
                uid,
                extras.getString(TAG),
                extras.getString(KIND)?.let { k -> WakelockKind.entries.firstOrNull { it.name == k } },
            )

            "app" -> uid?.let(SuggestedAction::ShowApp)

            "apps" -> SuggestedAction.ShowApps

            "radios" -> SuggestedAction.ShowRadios

            else -> null
        }
    }
}
