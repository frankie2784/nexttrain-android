package com.nexttrain.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.nexttrain.prefs.WidgetPrefs

private const val TAG = "AlarmScheduler"
const val ACTION_ALARM_UPDATE = "com.nexttrain.ACTION_ALARM_UPDATE"

// Fetch-gating thresholds, not the alarm's own cadence (see REPAINT_INTERVAL_MS
// below) — TrainWidgetProvider.performRefresh compares WidgetPrefs.getLastFetchAttempt
// against one of these (depending on whether a notification window is active) to
// decide whether a given tick is allowed to hit the network, or must repaint from
// the existing cache instead. internal so that file can reference them directly.
internal const val ACTIVE_INTERVAL_MS = 60_000L // 1 minute, while a notification window is active
internal const val IDLE_INTERVAL_MS = 5 * 60_000L // 5 minutes otherwise

// The alarm itself always fires on this cadence — every tick repaints the
// widget/notification countdowns and "last updated" label from whatever data
// is already cached (see Departure.withCurrentCountdown), even on ticks that
// don't fetch. Repainting is cheap (no radio wake, no network I/O) compared to
// an actual fetch, so keeping this fast doesn't cost meaningfully more battery
// than the fetch cadence alone — only ACTIVE_INTERVAL_MS/IDLE_INTERVAL_MS above
// gate how often the expensive part (the network round-trip) actually happens.
private const val REPAINT_INTERVAL_MS = ACTIVE_INTERVAL_MS
// Spreads server load: without jitter, devices that happen to boot/install
// around the same moment (e.g. after a mass OTA update) would otherwise
// stay in lockstep on the same cadence indefinitely, since each alarm
// simply reschedules itself N seconds later.
private const val JITTER_MS = 5_000L

/**
 * Schedules alarms whenever OD pairs exist, always on a fixed ~60s repaint
 * cadence (see REPAINT_INTERVAL_MS) — every tick repaints from cache, and
 * TrainWidgetProvider.performRefresh separately decides per-tick whether to
 * also fetch, based on WidgetPrefs.getLastFetchAttempt and whichever of
 * ACTIVE_INTERVAL_MS/IDLE_INTERVAL_MS currently applies. Uses
 * setAndAllowWhileIdle (not the exact variant, so no "Alarms & reminders"
 * special access is needed) so updates are guaranteed to eventually fire in
 * Doze mode — but Doze still throttles how often a "while idle" alarm may
 * actually be delivered (down to roughly every ~9+ minutes once the screen's
 * been off a while), so this on-schedule cadence only holds while the app is
 * exempted from battery optimization. See
 * ConfigActivity.maybeShowBatteryOptimizationPrompt, which asks the user for
 * that exemption.
 *
 * Design:
 *  - Each alarm fires AlarmReceiver, which triggers a widget refresh
 *    and reschedules the next alarm (chaining pattern).
 *  - On boot or widget enable, call scheduleIfNeeded().
 *  - When no OD pairs are configured at all, alarms are not rescheduled.
 */
internal fun shouldPromptForBatteryExemption(
    hasNotificationEnabledPair: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    dismissed: Boolean,
): Boolean = hasNotificationEnabledPair && !isIgnoringBatteryOptimizations && !dismissed

object AlarmScheduler {

    fun scheduleIfNeeded(context: Context) {
        val prefs = WidgetPrefs(context)
        if (prefs.getOdPairs().isEmpty()) {
            Log.d(TAG, "No OD pairs configured — skipping alarm")
            return
        }

        schedule(context, REPAINT_INTERVAL_MS)
    }

    fun schedule(context: Context, intervalMs: Long = REPAINT_INTERVAL_MS) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        val jitter = (0 until JITTER_MS).random() - JITTER_MS / 2
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs + jitter

        // Not the exact variant: no special permission needed, and precision
        // isn't required for a 1-minute departure-time refresh — the jitter
        // above already absorbs small scheduling drift. Still fires in Doze
        // (see the class doc), which is the part that actually matters here.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pi
        )
        Log.d(TAG, "Scheduled next alarm in ${intervalMs}ms")
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
        Log.d(TAG, "Alarm cancelled")
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_UPDATE
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Receives the per-minute alarm, triggers a widget refresh,
 * then reschedules the next alarm if still within an active window.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_UPDATE) return
        Log.d(TAG, "Alarm fired — refreshing widget")

        // Trigger widget update
        sendWidgetRefreshBroadcast(context)

        // Reschedule next alarm only if still in (or approaching) an active window
        AlarmScheduler.scheduleIfNeeded(context)
    }
}
