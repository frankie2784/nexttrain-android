package com.nexttrain.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "WidgetActionReceiver"

/**
 * Non-exported entry point for this app's own widget control broadcasts —
 * [ACTION_REFRESH] and [ACTION_CYCLE_ROUTE] (see AndroidManifest.xml).
 *
 * TrainWidgetProviderLight/Dark must stay `exported="true"` so the system can
 * deliver APPWIDGET_UPDATE/BOOT_COMPLETED, but an exported receiver can still
 * be sent an explicit intent by any other installed app regardless of its
 * manifest `<intent-filter>` — so these two app-private actions, which
 * trigger a real network fetch and can change the user's selected route,
 * are handled here instead: a receiver nothing outside this app can reach at
 * all, not merely one that isn't advertised via an implicit-intent filter.
 * See [BaseTrainWidgetProvider.onReceive]/[BaseTrainWidgetProvider.handleControlAction].
 *
 * Calls [BaseTrainWidgetProvider.handleControlAction] on manually-constructed
 * [TrainWidgetProviderLight]/[TrainWidgetProviderDark] instances rather than
 * re-dispatching another broadcast to them — those instances were never
 * themselves the target of a system broadcast, so [android.content.BroadcastReceiver.goAsync]
 * would not work on them (see handleControlAction's doc); this receiver owns
 * the one real goAsync()/finish() pair, since the system dispatched directly
 * to *it*.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val targets = when (intent.getStringExtra(EXTRA_TARGET_VARIANT)) {
            WidgetVariant.LIGHT.name -> listOf(TrainWidgetProviderLight())
            WidgetVariant.DARK.name -> listOf(TrainWidgetProviderDark())
            // General broadcast (alarm tick, app-triggered refresh) — no single
            // widget was tapped, so fan out to both, exactly as the implicit
            // ACTION_REFRESH broadcast this replaced used to (see AndroidManifest.xml).
            else -> listOf(TrainWidgetProviderLight(), TrainWidgetProviderDark())
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                targets.forEach { it.handleControlAction(context, intent) }
            } catch (e: Exception) {
                Log.e(TAG, "Widget control action failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
