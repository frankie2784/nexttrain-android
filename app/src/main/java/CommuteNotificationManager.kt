package com.nexttrain.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.SpannableStringBuilder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nexttrain.R
import com.nexttrain.config.ConfigActivity
import com.nexttrain.data.Departure
import com.nexttrain.data.OdPair
import com.nexttrain.prefs.WidgetPrefs
import com.nexttrain.ui.Formatting

private const val CHANNEL_ID = "next_train_departures"
private const val CHANNEL_NAME = "Train departures"
private const val CHANNEL_DESC = "Live departures for lock screen notification"
private const val NOTIFICATION_ID = 1001
private const val ACTION_DISMISS_NOTIFICATION = "com.nexttrain.action.DISMISS_NOTIFICATION"

object CommuteNotificationManager {

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * @param departures the departures to show — either freshly fetched, or
     * (when [offline]) the last-known-good cached ones, so a commuter
     * mid-journey still sees usable times rather than a blank error state.
     * @param offline true when the server couldn't be confirmed reachable
     * this tick; [departures] may still be non-empty (stale cache) or empty
     * (nothing cached yet).
     */
    fun showDepartures(
        context: Context,
        pair: OdPair,
        departures: List<Departure>,
        offline: Boolean = false
    ) {
        val prefs = WidgetPrefs(context)

        // Only show notification if it's within the active time/day window for this route
        if (!pair.isActiveNow() || !pair.notificationsEnabled) {
            clear(context)
            return
        }

        // Don't show if user has dismissed this notification during the active window
        if (prefs.isNotificationDismissedByUser(pair.id)) {
            clear(context)
            return
        }

        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConfigActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISS_NOTIFICATION
                putExtra("pair_id", pair.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "${pair.originName} → ${pair.destinationName}"

        val content: String
        val lines: List<CharSequence>
        when {
            departures.isEmpty() && offline -> {
                content = "Can't reach server"
                lines = listOf(SpannableStringBuilder(content))
            }
            departures.isEmpty() -> {
                content = "No upcoming trains"
                lines = listOf(SpannableStringBuilder(content))
            }
            else -> {
                val summary = departures.take(3).joinToString(" • ") { dep -> formatNotificationSummary(prefs.use24HourFormat, dep) }
                content = if (offline) "Offline · $summary" else summary
                lines = departures.take(3).map { dep -> formatNotificationLine(prefs.use24HourFormat, dep) }
            }
        }

        val bigText = SpannableStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("\n")
                append(line)
            }
        }

        val style = NotificationCompat.BigTextStyle().bigText(bigText)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.nt_primary))
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(0, "Dismiss", dismissIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission may not be granted yet.
        }
    }

    private fun formatNotificationLine(use24Hour: Boolean, dep: Departure): CharSequence {
        val mins = when {
            dep.minutesUntilDeparture <= 0 -> "Now"
            dep.minutesUntilDeparture == 1L -> "1m"
            dep.minutesUntilDeparture > 120 -> "${dep.minutesUntilDeparture / 60}h"
            else -> "${dep.minutesUntilDeparture}m"
        }

        val delaySuffix = if (dep.delayMinutes > 0) " (+${dep.delayMinutes})" else ""
        val expectedTime = Formatting.formatTime(use24Hour, dep.expectedTime)
        val line = SpannableStringBuilder("$mins · $expectedTime")

        dep.destinationDisplayTime?.let {
            val destTime = Formatting.formatTime(use24Hour, it)
            line.append(" → $destTime")
        }

        line.append(delaySuffix)

        return line
    }

    private fun formatNotificationSummary(use24Hour: Boolean, dep: Departure): String {
        val mins = when {
            dep.minutesUntilDeparture <= 0 -> "Now"
            dep.minutesUntilDeparture == 1L -> "1m"
            dep.minutesUntilDeparture > 120 -> "${dep.minutesUntilDeparture / 60}h"
            else -> "${dep.minutesUntilDeparture}m"
        }

        val expectedTime = Formatting.formatTimeCompact(use24Hour, dep.expectedTime)
        return "$mins $expectedTime"
    }

    fun showStatus(context: Context, title: String, message: String) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConfigActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.nt_primary))
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission may not be granted yet.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
