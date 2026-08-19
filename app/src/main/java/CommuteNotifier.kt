package com.nexttrain.widget

import android.content.Context
import com.nexttrain.data.DeparturesEntry
import com.nexttrain.data.OdPair
import com.nexttrain.prefs.WidgetPrefs

/**
 * Owns the commute notification and the active-window state machine behind it.
 *
 * This logic used to live inside `TrainWidgetProvider.updateWidget()`, which
 * only ever runs once per *placed widget*. With no widget on a home screen the
 * update loop had nothing to iterate, so the notification was never posted even
 * though the alarm chain kept firing every minute. Keeping it here means the
 * notification depends only on the alarm tick, never on widget placement.
 */
object CommuteNotifier {

    /**
     * Advance the active-window state machine and return the pair whose window
     * is open right now, or null when none is.
     *
     * Must run exactly once per refresh tick — *not* once per widget — because
     * it writes the "last active route" marker that its own next run reads. The
     * old per-widget placement meant a second placed widget observed state the
     * first had already advanced.
     */
    fun syncActiveWindow(context: Context, prefs: WidgetPrefs): OdPair? {
        val activePairNow = prefs.activeOdPairs().firstOrNull()
        val currentActiveRouteId = activePairNow?.id
        val lastActiveRouteId = prefs.getLastActiveRouteId()

        // When a new active window opens, reset selection so the active route shows by default.
        if (currentActiveRouteId != null && currentActiveRouteId != lastActiveRouteId) {
            prefs.clearNotificationDismissal()
            prefs.setSelectedRouteId(null)
        }

        // When an active window ends (no new route is active), clear dismissal and
        // shift the widget back to the sparkline. If a different window immediately
        // becomes active, the new-window block above has already reset to null.
        if (lastActiveRouteId != null && currentActiveRouteId == null) {
            val lastPair = prefs.getOdPairs().find { it.id == lastActiveRouteId }
            if (lastPair != null && !lastPair.isActiveNow()) {
                prefs.clearNotificationDismissal(lastActiveRouteId)
                prefs.setSelectedRouteId(WidgetPrefs.SPARKLINE_ROUTE_ID)
            }
        } else if (lastActiveRouteId != null && currentActiveRouteId != lastActiveRouteId) {
            // Different route window opened — just clean up the old dismissal.
            val lastPair = prefs.getOdPairs().find { it.id == lastActiveRouteId }
            if (lastPair != null && !lastPair.isActiveNow()) {
                prefs.clearNotificationDismissal(lastActiveRouteId)
            }
        }

        prefs.setLastActiveRouteId(currentActiveRouteId)
        return activePairNow
    }

    /**
     * Post the commute notification for [activePair] using [entry] — already
     * fetched this tick by [com.nexttrain.data.DeparturesRepository], shared
     * with the widget paint and any open app screen — or clear it when no
     * window is open.
     */
    fun refresh(context: Context, activePair: OdPair?, entry: DeparturesEntry?) {
        if (activePair == null) {
            CommuteNotificationManager.clear(context)
            return
        }

        // entry == null (no fetch has happened yet for this pair) is treated
        // the same as unreachable: there's nothing fresher to show either way.
        // .upcoming drops anything more than a few seconds past due (see
        // Departure.hasDeparted) so a departed train doesn't sit pinned as
        // "Now" in the notification until the next alarm tick replaces it.
        CommuteNotificationManager.showDepartures(
            context,
            activePair,
            departures = entry?.upcoming ?: emptyList(),
            offline = entry == null || entry.unreachable,
        )
    }
}
