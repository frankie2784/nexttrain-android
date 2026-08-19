package com.nexttrain.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.nexttrain.prefs.WidgetPrefs

/**
 * Broadcast receiver that handles dismissal of notifications.
 * When a user taps the "Dismiss" action on a notification, this receiver:
 * 1. Clears the notification
 * 2. Marks the notification as dismissed for the active route window
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null && intent?.action == "com.nexttrain.action.DISMISS_NOTIFICATION") {
            NotificationManagerCompat.from(context).cancel(1001)

            val pairId = intent.getStringExtra("pair_id")
            if (pairId != null) {
                val prefs = WidgetPrefs(context)
                prefs.setNotificationDismissedByUser(pairId, true)
                // Shift widget back to sparkline view after user dismisses.
                prefs.setSelectedRouteId(WidgetPrefs.SPARKLINE_ROUTE_ID)
            }

            // Trigger an immediate widget refresh so the view switches now.
            sendWidgetRefreshBroadcast(context)
        }
    }
}

