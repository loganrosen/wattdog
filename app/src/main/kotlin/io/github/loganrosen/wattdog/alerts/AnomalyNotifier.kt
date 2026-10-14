package io.github.loganrosen.wattdog.alerts

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.loganrosen.wattdog.R
import io.github.loganrosen.wattdog.batterystats.analysis.Anomaly
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.ui.MainActivity
import io.github.loganrosen.wattdog.ui.label
import io.github.loganrosen.wattdog.ui.opensSettings
import io.github.loganrosen.wattdog.ui.settingsIntents
import io.github.loganrosen.wattdog.ui.toFocus
import kotlin.time.Duration

class AnomalyNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()

    fun ensureChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_FINDINGS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Battery findings")
                    .setDescription("New wakelock, background work, and radio problems found by background checks")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ACCESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Access problems")
                    .setDescription("Background checks can't read battery stats")
                    .build(),
            ),
        )
    }

    fun notifyFindings(anomalies: List<Anomaly>, analysis: Analysis, window: Duration) {
        if (!canPost()) return
        ensureChannels()
        anomalies.forEach { post(it, analysis, window) }
    }

    @Suppress("MissingPermission")
    private fun post(anomaly: Anomaly, analysis: Analysis, window: Duration) {
        val id = anomaly.key.hashCode()
        val inApp = anomaly.actions.firstOrNull { it.toFocus() != null }
        val settings = anomaly.actions.firstNotNullOfOrNull { action ->
            if (!action.opensSettings()) return@firstNotNullOfOrNull null
            action.settingsIntents(analysis)
                .firstOrNull { it.resolveActivity(context.packageManager) != null }
                ?.let { action to it }
        }
        val app = anomaly.uid?.let { analysis.label(it).label }
        val text = "Last ${window.formatShort()}: " + (anomaly.cost?.let { "${it.summary()}. " } ?: "") + anomaly.detail

        val builder = NotificationCompat.Builder(context, CHANNEL_FINDINGS)
            .setSmallIcon(R.drawable.ic_stat_wattdog)
            .setContentTitle(anomaly.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n${anomaly.advice}"))
            .setSubText(app)
            .setContentIntent(openApp(id, inApp))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        inApp?.let { builder.addAction(0, it.label(), openApp(id + 1, it)) }
        settings?.let { (action, intent) ->
            val pending = PendingIntent.getActivity(
                context,
                id + 2,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, action.label(), pending)
        }
        manager.notify(TAG_FINDING, id, builder.build())
    }

    @Suppress("MissingPermission")
    fun notifyAccessLost() {
        if (!canPost()) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_ACCESS)
            .setSmallIcon(R.drawable.ic_stat_wattdog)
            .setContentTitle("Background checks are failing")
            .setContentText("Wattdog can't read battery stats. Start Shizuku again, or grant DUMP over adb.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Wattdog can't read battery stats. Shizuku stops after a reboot unless it's set to start on " +
                        "boot; start it again, or grant DUMP over adb, which survives reboots. You can also turn " +
                        "background checks off in Settings.",
                ),
            )
            .setContentIntent(openApp(ACCESS_ID, null))
            .setAutoCancel(true)
            .build()
        manager.notify(ACCESS_ID, notification)
    }

    fun clearAccessLost() = manager.cancel(ACCESS_ID)

    private fun openApp(requestCode: Int, action: SuggestedAction?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        action?.let { NotificationRoute.put(intent, it) }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val CHANNEL_FINDINGS = "findings"
        const val CHANNEL_ACCESS = "access"
        const val TAG_FINDING = "finding"
        const val ACCESS_ID = 1
    }
}
