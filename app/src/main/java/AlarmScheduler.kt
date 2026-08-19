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
private const val ACTIVE_INTERVAL_MS = 60_000L // 1 minute, while a notification window is active
private const val IDLE_INTERVAL_MS = 5 * 60_000L // 5 minutes otherwise — the widget's own
    // updatePeriodMillis backstop still repaints every 30 min regardless.
// Spreads server load: without jitter, devices that happen to boot/install
// around the same moment (e.g. after a mass OTA update) would otherwise
// stay in lockstep on the same cadence indefinitely, since each alarm
// simply reschedules itself N seconds later.
private const val JITTER_MS = 5_000L

/**
 * Schedules alarms whenever OD pairs exist, at a cadence that adapts to
 * whether a notification window is currently active: 60s while at least one
 * OD pair is inside its active window (matching WidgetPrefs.activeOdPairs()),
 * ~5 min otherwise. Uses setAndAllowWhileIdle (not the exact variant, so no
 * "Alarms & reminders" special access is needed) so updates are guaranteed to
 * eventually fire in Doze mode — but Doze still throttles how often a "while
 * idle" alarm may actually be delivered (down to roughly every ~9+ minutes
 * once the screen's been off a while), so this on-schedule cadence only holds
 * while the app is exempted from battery optimization. See
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
        val pairs = prefs.getOdPairs()
        if (pairs.isEmpty()) {
            Log.d(TAG, "No OD pairs configured — skipping alarm")
            return
        }

        val intervalMs = if (prefs.activeOdPairs().isNotEmpty()) ACTIVE_INTERVAL_MS else IDLE_INTERVAL_MS
        schedule(context, intervalMs)
    }

    fun schedule(context: Context, intervalMs: Long = ACTIVE_INTERVAL_MS) {
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
