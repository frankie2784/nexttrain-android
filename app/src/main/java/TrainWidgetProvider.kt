package com.nexttrain.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.nexttrain.R
import com.nexttrain.api.NextTrainApiClient
import com.nexttrain.config.ConfigActivity
import com.nexttrain.data.DelayPoint
import com.nexttrain.data.Departure
import com.nexttrain.data.DeparturesEntry
import com.nexttrain.data.DeparturesRepository
import com.nexttrain.data.OdPair
import com.nexttrain.prefs.WidgetPrefs
import com.nexttrain.ui.Formatting
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val TAG = "NextTrain"
const val ACTION_REFRESH = "com.nexttrain.ACTION_REFRESH"
const val ACTION_CYCLE_ROUTE = "com.nexttrain.ACTION_CYCLE_ROUTE"

// Kept (combined, across every OD pair fetched this tick) under the ~10s a
// BroadcastReceiver gets after goAsync(), so the batch either finishes or
// gives up before the system reclaims the receiver.
private const val FETCH_TIMEOUT_MS = 6_000L
private const val REACHABILITY_TIMEOUT_MS = 3_000L

/** Marks a refresh broadcast as following a fetch that already covered every
 *  configured pair (a manual pull-to-refresh in the app), so the widget can
 *  paint from [DeparturesRepository]'s current snapshot instead of paying for
 *  a second network round-trip for the same route(s). Absent (or false) for
 *  the periodic alarm tick, which is the widget's only fetch for that pair. */
const val EXTRA_ALREADY_FETCHED = "com.nexttrain.EXTRA_ALREADY_FETCHED"

/** Marks a refresh broadcast as a direct user interaction (tapping the
 *  widget's own refresh button), as opposed to the unattended periodic alarm
 *  tick — both fire the same [ACTION_REFRESH] action. An interactive refresh
 *  must bypass [NextTrainApiClient]'s short response cache so a tap that
 *  lands within that window still does a real fetch instead of silently
 *  re-showing what's already on screen. */
const val EXTRA_FORCE_REFRESH = "com.nexttrain.EXTRA_FORCE_REFRESH"

/** Restricts a tap-triggered refresh/cycle (see [BaseTrainWidgetProvider.applyTapActions])
 *  to the single variant whose widget was actually tapped, rather than [WidgetActionReceiver]'s
 *  default of refreshing both. Absent for the general broadcast (alarm tick, app-triggered
 *  "already fetched" refresh — see [sendWidgetRefreshBroadcast]), which still fans out to both
 *  light and dark, exactly as the implicit broadcast this replaced used to. */
const val EXTRA_TARGET_VARIANT = "com.nexttrain.EXTRA_TARGET_VARIANT"

/**
 * Broadcasts a refresh to whichever widget(s) — light, dark, or both — the user
 * has actually placed. Sent as an implicit intent scoped to our own package
 * (rather than an explicit component) so it reaches every registered provider's
 * manifest intent-filter without the caller needing to know which variants exist.
 */
fun sendWidgetRefreshBroadcast(context: Context, alreadyFetched: Boolean = false) {
    context.sendBroadcast(
        Intent(ACTION_REFRESH).setPackage(context.packageName).putExtra(EXTRA_ALREADY_FETCHED, alreadyFetched)
    )
}

abstract class BaseTrainWidgetProvider(private val variant: WidgetVariant) : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate: ${appWidgetIds.size} widget(s)")
        refreshTick(context, appWidgetIds)
    }

    /**
     * ACTION_REFRESH and ACTION_CYCLE_ROUTE are deliberately NOT handled here
     * (see [handleControlAction]/[WidgetActionReceiver]) even though this
     * class must stay `exported="true"` for the system to deliver
     * APPWIDGET_UPDATE/BOOT_COMPLETED: an exported component's onReceive can
     * still be invoked by any other installed app via an explicit intent
     * naming this class directly, regardless of what's declared in its
     * manifest `<intent-filter>` — manifest filters only gate *implicit*
     * intent resolution. Those two actions trigger a real network fetch and
     * can change the user's selected route, so they're handled exclusively
     * by [WidgetActionReceiver], a genuinely non-exported receiver that
     * other apps cannot target at all (implicitly or explicitly).
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Received boot intent")
            refreshTick(context, placedWidgetIds(context))
        }
    }

    /**
     * Invoked only by [WidgetActionReceiver] for our own app-private control
     * broadcasts. A suspend function, not [refreshTick], because this runs on
     * a [BaseTrainWidgetProvider] instance [WidgetActionReceiver] constructed
     * itself (see its class doc) — the system never dispatched a broadcast to
     * *this* instance, so it has no primed [android.content.BroadcastReceiver.PendingResult]
     * and calling [goAsync] on it (as [refreshTick] does) would return null and
     * crash on `pending.finish()`. [WidgetActionReceiver] owns the one real
     * goAsync()/finish() pair — for the actually-dispatched receiver — around
     * its call(s) into this.
     */
    internal suspend fun handleControlAction(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CYCLE_ROUTE -> {
                Log.d(TAG, "Received cycle route intent")
                val prefs = WidgetPrefs(context)
                val activePairNow = prefs.activeOdPairs().firstOrNull()
                prefs.cycleToNextRoute(activePairNow)
                performRefresh(context, placedWidgetIds(context), alreadyFetched = false, forceRefresh = true)
            }
            ACTION_REFRESH -> {
                Log.d(TAG, "Received refresh intent")
                val alreadyFetched = intent.getBooleanExtra(EXTRA_ALREADY_FETCHED, false)
                val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)
                performRefresh(context, placedWidgetIds(context), alreadyFetched, forceRefresh)
            }
        }
        AlarmScheduler.scheduleIfNeeded(context)
    }

    private fun placedWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, javaClass))

    /**
     * One refresh tick: advance the active-window state, fetch through
     * [DeparturesRepository] — the single source of truth also read by the
     * app's own screens — only the route the widget is actually showing plus
     * the route the notification is actually showing, then repaint from that
     * fetch.
     *
     * This tick runs continuously in the background (driven by the alarm,
     * regardless of whether the app is open), so it deliberately fetches only
     * what's visible without opening the app rather than every saved route —
     * a user with many saved routes shouldn't pay for background requests to
     * routes nothing is currently displaying. The app's own screens fetch
     * every route themselves (see ConfigActivity/RouteDeparturesActivity)
     * while they're open, and still land in this same repository.
     *
     * When [alreadyFetched] is set (the app just ran its own refresh across
     * every configured pair) this skips fetching again and paints from
     * [DeparturesRepository]'s current snapshot instead — the app's fetch
     * already covers whatever this tick needs, so a second network
     * round-trip would only add redundant latency before the widget catches
     * up with what the app is already showing.
     *
     * When [forceRefresh] is set (the user tapped the widget's own refresh
     * or cycle button) the fetch bypasses the short response cache, so a tap
     * that lands within that cache window still does a real fetch instead of
     * silently re-showing stale data. The unattended alarm tick leaves it
     * unset, keeping the cache's original job of de-duping the widget and
     * notification when they want the same stop within one tick.
     */
    private fun refreshTick(
        context: Context,
        appWidgetIds: IntArray,
        alreadyFetched: Boolean = false,
        forceRefresh: Boolean = false,
    ) {
        // Hold the receiver alive across the fetch. With no widget placed there
        // is nothing else keeping this process around, so a bare scope.launch
        // would often be killed before the notification was posted. Safe to call
        // here (unlike in handleControlAction) because the system just dispatched
        // this exact broadcast/onUpdate to this exact instance — see onReceive/onUpdate.
        val pending = goAsync()
        scope.launch {
            try {
                performRefresh(context, appWidgetIds, alreadyFetched, forceRefresh)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh tick failed", e)
            } finally {
                pending.finish()
            }
        }

        AlarmScheduler.scheduleIfNeeded(context)
    }

    /**
     * The actual fetch-and-paint work shared by [refreshTick] (system-dispatched
     * onUpdate/boot path) and [handleControlAction] ([WidgetActionReceiver]'s
     * app-private control-action path) — each caller owns its own process-keep-alive
     * lifecycle around this (goAsync()/finish() for refreshTick; WidgetActionReceiver's
     * own goAsync()/finish() for handleControlAction), so this itself must not call
     * either.
     */
    private suspend fun performRefresh(
        context: Context,
        appWidgetIds: IntArray,
        alreadyFetched: Boolean,
        forceRefresh: Boolean,
    ) {
        val prefs = WidgetPrefs(context)
        val activePairNow = CommuteNotifier.syncActiveWindow(context, prefs)
        val widgetPair = currentWidgetPair(prefs, activePairNow)
        val backgroundPairs = listOfNotNull(widgetPair, activePairNow).distinctBy { it.id }

        val entries = if (alreadyFetched) {
            DeparturesRepository.entries.value
        } else {
            DeparturesRepository.refreshAll(
                context = context,
                prefs = prefs,
                pairs = backgroundPairs,
                fetchTimeoutMs = FETCH_TIMEOUT_MS,
                reachabilityTimeoutMs = REACHABILITY_TIMEOUT_MS,
                forceRefresh = forceRefresh,
            )
        }
        CommuteNotifier.refresh(context, activePairNow, activePairNow?.let { entries[it.id] })

        if (appWidgetIds.isNotEmpty()) {
            val manager = AppWidgetManager.getInstance(context)
            appWidgetIds.forEach { updateWidget(context, manager, it, activePairNow, entries) }
        }
    }

    /**
     * The single OD pair the widget is currently showing (or about to show),
     * or null when it's showing the sparkline / the empty-state "open the
     * app" prompt instead — mirrors the early-exit branches at the top of
     * [updateWidget] so the background tick fetches exactly the route that
     * will actually be painted, no more.
     */
    private fun currentWidgetPair(prefs: WidgetPrefs, activePairNow: OdPair?): OdPair? {
        val widgetPairs = prefs.widgetOdPairs()
        if (widgetPairs.isEmpty()) return null

        val sparklineRegion = prefs.selectedRegion
        val selectedRouteId = prefs.getSelectedRouteId()
        val showSparkline = WidgetPrefs.SPARKLINE_ENABLED && sparklineRegion.hasRealtime && (
            selectedRouteId == WidgetPrefs.SPARKLINE_ROUTE_ID ||
                (selectedRouteId == null && activePairNow == null)
            )
        if (showSparkline) return null

        return resolveSelectedPair(prefs, activePairNow)
    }

    override fun onEnabled(context: Context) {
        AlarmScheduler.scheduleIfNeeded(context)
    }

    override fun onDisabled(context: Context) {
        AlarmScheduler.cancel(context)
    }

    // ── Update logic ──────────────────────────────────────────────────────

    /**
     * Paint a single widget instance. Active-window bookkeeping and the
     * notification are handled once per tick by [CommuteNotifier] (see
     * [refreshTick]); [activePairNow] is that tick's result, and [entries] is
     * this tick's already-fetched [DeparturesRepository] snapshot for every
     * configured pair — painting from it (rather than fetching again here)
     * is what keeps the widget showing the same numbers as the notification
     * and the app.
     */
    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        activePairNow: OdPair?,
        entries: Map<String, DeparturesEntry>
    ) {
        val prefs = WidgetPrefs(context)
        val widgetPairs = prefs.widgetOdPairs()

        // No single route is selected while the sparkline shows, so use the
        // user's chosen default region (Settings) as the region whose
        // network-wide delay history to display.
        val sparklineRegion = prefs.selectedRegion
        val selectedRouteId = prefs.getSelectedRouteId()

        // The "open the app" prompt must never appear on top of a sparkline the
        // widget could otherwise show — it's reachable only by explicitly cycling
        // past the sparkline (see WidgetPrefs.cycleToNextRoute), never automatically.
        if (widgetPairs.isEmpty() && selectedRouteId == WidgetPrefs.NO_ROUTE_PROMPT_ID) {
            showErrorState(
                context = context,
                manager = manager,
                widgetId = widgetId,
                message = "Open the app to add a route"
            )
            return
        }

        // Show sparkline when explicitly selected by cycling, as the default idle view, or
        // when every configured route has been toggled off the widget cycle — but only for
        // regions with a GTFS-RT feed wired up (see Region.hasRealtime). Regions without one
        // never have delay data, so the sparkline is omitted entirely rather than showing an
        // empty/neutral graph or a misleading "can't reach server" error.
        //
        // Hidden entirely for now across all regions (see WidgetPrefs.SPARKLINE_ENABLED) —
        // drop the extra guard once it's re-enabled.
        val showSparkline = WidgetPrefs.SPARKLINE_ENABLED && sparklineRegion.hasRealtime && (
            selectedRouteId == WidgetPrefs.SPARKLINE_ROUTE_ID ||
                widgetPairs.isEmpty() ||
                (selectedRouteId == null && activePairNow == null)
            )
        if (showSparkline) {
            val cachedHistory = prefs.getCachedDelayHistory(sparklineRegion, 5 * 60 * 1000L)
            if (cachedHistory.isNotEmpty()) {
                showIdleState(context, manager, widgetId, cachedHistory)
            }

            scope.launch {
                val client = NextTrainApiClient()
                val history = withTimeoutOrNull(15_000L) {
                    client.getDelayHistory(prefs.serverUrl, sparklineRegion)
                } ?: emptyList()

                if (history.isNotEmpty()) {
                    prefs.saveCachedDelayHistory(sparklineRegion, history)
                    showIdleState(context, manager, widgetId, history)
                } else if (cachedHistory.isEmpty()) {
                    showErrorState(
                        context = context,
                        manager = manager,
                        widgetId = widgetId,
                        message = "Can't reach server."
                    )
                }
            }
            return
        }

        // No routes configured and the sparkline is unavailable for this region
        // (see showSparkline above) — nothing left to show.
        if (widgetPairs.isEmpty()) {
            showErrorState(
                context = context,
                manager = manager,
                widgetId = widgetId,
                message = "Open the app to add a route"
            )
            return
        }

        val activePair = resolveSelectedPair(prefs, activePairNow) ?: return

        // entries is populated by DeparturesRepository.refreshAll() for every
        // configured pair before refreshTick calls updateWidget, so the fetch
        // for this pair has already happened this tick — paint straight from it.
        val entry = entries[activePair.id]
        // .upcoming drops anything more than a few seconds past due (see
        // Departure.hasDeparted) — without it a departed train could sit
        // pinned as "Now" until the next alarm tick actually replaces it.
        val upcoming = entry?.upcoming.orEmpty()
        if (entry == null || (entry.unreachable && upcoming.isEmpty())) {
            showErrorState(
                context = context,
                manager = manager,
                widgetId = widgetId,
                message = "Can't reach server."
            )
        } else {
            // A commuter mid-journey is better served by stale times with an
            // offline hint than a blank error screen when the server drops
            // out but a last-known-good fetch is still cached (see
            // DeparturesRepository.unreachableEntry).
            renderDepartures(
                context = context,
                manager = manager,
                widgetId = widgetId,
                pair = activePair,
                departures = upcoming,
                offline = entry.unreachable,
            )
        }
    }

    // ── RemoteViews builders ──────────────────────────────────────────────

    private fun showErrorState(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        message: String
    ) {
        val views = RemoteViews(context.packageName, variant.layoutRes)
        views.setViewVisibility(R.id.layout_normal, View.GONE)
        views.setViewVisibility(R.id.layout_idle, View.GONE)
        views.setViewVisibility(R.id.layout_error, View.VISIBLE)
        views.setTextViewText(R.id.tv_error_message, message)

        applyTapActions(context, views, widgetId, openAppOnTap = true)
        manager.updateAppWidget(widgetId, views)

        // Deliberately does not touch the notification: CommuteNotifier owns its
        // lifecycle now, and a widget-side fetch failure is not a reason to drop
        // a notification that was posted successfully.
    }

    private fun showIdleState(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        history: List<DelayPoint>
    ) {
        val views = RemoteViews(context.packageName, variant.layoutRes)
        views.setViewVisibility(R.id.layout_normal, View.GONE)
        views.setViewVisibility(R.id.layout_idle, View.VISIBLE)
        views.setViewVisibility(R.id.layout_error, View.GONE)

        val smoothedHistory = getSmoothedDelayHistory(history)
        val tickColour = ContextCompat.getColor(context, variant.colorFgSubtle)
        val labelColour = ContextCompat.getColor(context, variant.colorFgFaint)
        val bmp = drawSparkline(smoothedHistory, tickColour = tickColour, labelColour = labelColour)
        views.setImageViewBitmap(R.id.iv_sparkline, bmp)

        val latestDelay = smoothedHistory.lastOrNull()?.totalDelayMinutes
        views.setTextViewText(R.id.tv_delay_minutes, if (latestDelay != null) "${latestDelay.roundToInt()}m" else "--")
        val fallbackColour = ContextCompat.getColor(context, variant.colorFg)
        views.setTextColor(R.id.tv_delay_minutes, if (latestDelay != null) delayColour(latestDelay) else fallbackColour)

        applyTapActions(context, views, widgetId)
        manager.updateAppWidget(widgetId, views)
    }

    private fun renderDepartures(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        pair: OdPair,
        departures: List<Departure>,
        offline: Boolean = false
    ) {
        // Cache read/write for this pair is already handled by DeparturesRepository
        // (see refreshTick) — this just paints what it fetched.
        val use24Hour = WidgetPrefs(context).use24HourFormat
        val views = RemoteViews(context.packageName, variant.layoutRes)
        views.setViewVisibility(R.id.layout_normal, View.VISIBLE)
        views.setViewVisibility(R.id.layout_idle, View.GONE)
        views.setViewVisibility(R.id.layout_error, View.GONE)
        views.setTextViewText(R.id.tv_route_label, "${pair.originName} ➝ ${pair.destinationName}")
        val nowUnformatted = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val now = Formatting.formatTime(use24Hour, nowUnformatted)
        // When offline, these are stale times from the last successful fetch,
        // not "just updated now" — say so rather than implying they're live.
        views.setTextViewText(R.id.tv_last_updated, if (offline) "⚠ offline · last $now" else "↻ $now")

        if (departures.isEmpty()) {
            views.setTextViewText(R.id.tv_primary_minutes, "--")
            views.setTextViewText(R.id.tv_primary_time, "--:--")
            views.setViewVisibility(R.id.tv_secondary_1, View.GONE)
            views.setViewVisibility(R.id.tv_secondary_separator, View.GONE)
            views.setViewVisibility(R.id.tv_secondary_2, View.GONE)
            views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
            views.setTextViewText(R.id.tv_no_trains, "No trains found")
        } else {
            val primary = departures[0]
            views.setTextViewText(R.id.tv_primary_minutes, departureMinutesText(primary))
            views.setTextViewText(R.id.tv_primary_time, "(${Formatting.formatTimeCompact(use24Hour, primary.expectedTime)})")

            val second = departures.getOrNull(1)
            if (second != null) {
                views.setTextViewText(R.id.tv_secondary_1, compactDepartureText(use24Hour, second))
                views.setViewVisibility(R.id.tv_secondary_1, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_secondary_1, View.INVISIBLE)
            }

            val third = departures.getOrNull(2)
            if (third != null) {
                views.setTextViewText(R.id.tv_secondary_2, compactDepartureText(use24Hour, third))
                views.setViewVisibility(R.id.tv_secondary_2, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_secondary_2, View.INVISIBLE)
            }

            views.setViewVisibility(
                R.id.tv_secondary_separator,
                if (second != null && third != null) View.VISIBLE else View.INVISIBLE
            )

            views.setViewVisibility(R.id.tv_no_trains, View.GONE)
        }

        applyTapActions(context, views, widgetId)
        manager.updateAppWidget(widgetId, views)
    }

    private fun resolveSelectedPair(prefs: WidgetPrefs, activePairNow: OdPair?): OdPair? {
        val configuredPairs = prefs.widgetOdPairs()
        if (configuredPairs.isEmpty()) {
            prefs.setSelectedRouteId(null)
            return null
        }

        val selected = prefs.getSelectedRouteId()?.let { id ->
            configuredPairs.firstOrNull { it.id == id }
        }
        if (selected != null) return selected

        val fallback = (activePairNow?.takeIf { it.includeOnWidget }) ?: configuredPairs.first()
        prefs.setSelectedRouteId(fallback.id)
        return fallback
    }

    private fun applyTapActions(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        openAppOnTap: Boolean = false
    ) {
        if (openAppOnTap) {
            val openAppPi = PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, ConfigActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tap_refresh_zone, openAppPi)
            views.setOnClickPendingIntent(R.id.tap_cycle_zone, openAppPi)
            return
        }

        // Targets WidgetActionReceiver (non-exported) rather than this class directly —
        // see the doc on onReceive/handleControlAction above. EXTRA_TARGET_VARIANT keeps
        // a tap scoped to the tapped widget's own variant, matching the pre-refactor
        // behaviour where this explicit intent only ever reached one variant's onReceive.
        val refreshIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = ACTION_REFRESH
            putExtra(EXTRA_FORCE_REFRESH, true)
            putExtra(EXTRA_TARGET_VARIANT, variant.name)
        }
        val refreshPi = PendingIntent.getBroadcast(
            context,
            widgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tap_refresh_zone, refreshPi)

        val cycleIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = ACTION_CYCLE_ROUTE
            putExtra(EXTRA_TARGET_VARIANT, variant.name)
        }
        val cyclePi = PendingIntent.getBroadcast(
            context,
            widgetId + 10_000,
            cycleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tap_cycle_zone, cyclePi)
    }

    private fun departureMinutesText(dep: Departure): String = when {
        dep.minutesUntilDeparture <= 0 -> "Now"
        dep.minutesUntilDeparture == 1L -> "1m"
        dep.minutesUntilDeparture > 120 -> "${dep.minutesUntilDeparture / 60}h"
        else -> "${dep.minutesUntilDeparture}m"
    }

    private fun compactDepartureText(use24Hour: Boolean, dep: Departure): CharSequence {
        val out = SpannableStringBuilder()
        val mins = departureMinutesText(dep)
        out.append(mins)
        out.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val timeStart = out.length
        val formattedTime = Formatting.formatTimeCompact(use24Hour, dep.expectedTime)
        out.append(" ($formattedTime)")
        out.setSpan(
            RelativeSizeSpan(0.88f),
            timeStart,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return out
    }

    // ── Sparkline helpers ─────────────────────────────────────────────────

    // Time: O(n) | Space: O(n)
    private fun drawSparkline(
        points: List<DelayPoint>,
        widthPx: Int = 600,
        heightPx: Int = 80,
        tickColour: Int = Color.argb(115, 255, 255, 255),
        labelColour: Int = Color.argb(180, 255, 255, 255)
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val pad = 4f
        val drawH = heightPx - 2 * pad

        // Reserve right margin based on label character count. The bitmap is stretched
        // to widget width via fitXY so bitmap-space text measurement is unreliable;
        // character count gives a stable, predictable result.
        val latestDelay = points.minByOrNull { it.secondsAgo }?.totalDelayMinutes
        val labelText = if (latestDelay != null) "${latestDelay.roundToInt()}m" else "--"
        val rightPad = widthPx * when (labelText.length) {
            1, 2 -> 0.2f   // "0m"–"9m"
            3    -> 0.2f   // "10m"–"99m"
            else -> 0.24f   // "100m" and above
        }
        val drawW = widthPx - rightPad

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        if (points.size < 2) {
            paint.color = delayColour(0f)
            canvas.drawLine(0f, heightPx - pad, drawW, heightPx - pad, paint)
            return bmp
        }

        // Already-smoothed history is expected from the caller.
        val sorted = points.sortedByDescending { it.secondsAgo }
        val n = sorted.size
        val maxDelay = sorted.maxOf { it.totalDelayMinutes }

        // All values are zero — draw flat line at bottom and return early.
        if (maxDelay <= 0f) {
            paint.color = delayColour(0f)
            canvas.drawLine(0f, heightPx - pad, drawW, heightPx - pad, paint)
            return bmp
        }

        fun xFor(i: Int): Float = i.toFloat() / (n - 1) * drawW
        fun yFor(d: Float): Float = pad + drawH * (1f - d.coerceAtLeast(0f) / maxDelay)

        for (i in 0 until n - 1) {
            val x1 = xFor(i);  val y1 = yFor(sorted[i].totalDelayMinutes)
            val x2 = xFor(i + 1); val y2 = yFor(sorted[i + 1].totalDelayMinutes)
            paint.color = delayColour((sorted[i].totalDelayMinutes + sorted[i + 1].totalDelayMinutes) / 2f)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        drawTimeTicks(canvas, drawW, heightPx, pad, sorted, tickColour, labelColour)

        // Pulsing live-tip indicator: solid dot + two translucent halo rings.
        val tipX = xFor(n - 1)
        val tipY = yFor(sorted[n - 1].totalDelayMinutes)
        val tipColour = delayColour(sorted[n - 1].totalDelayMinutes)
        val r = Color.red(tipColour); val g = Color.green(tipColour); val b = Color.blue(tipColour)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        haloPaint.color = Color.argb(35, r, g, b)
        canvas.drawCircle(tipX, tipY, 20f, haloPaint)
        haloPaint.color = Color.argb(70, r, g, b)
        canvas.drawCircle(tipX, tipY, 12f, haloPaint)
        haloPaint.color = tipColour
        canvas.drawCircle(tipX, tipY, 5f, haloPaint)

        return bmp
    }

    private fun drawTimeTicks(
        canvas: Canvas,
        drawW: Float,
        heightPx: Int,
        pad: Float,
        sorted: List<DelayPoint>,
        tickColour: Int,
        labelColour: Int
    ) {
        if (sorted.isEmpty()) return

        val now = Instant.now().atZone(ZoneId.systemDefault())
        val oldestSecondsAgo = sorted.first().secondsAgo.toLong()
        val newestSecondsAgo = sorted.last().secondsAgo.toLong()
        val legacyRange = oldestSecondsAgo - newestSecondsAgo
        if (legacyRange <= 0) return

        val minTime = now.minusSeconds(oldestSecondsAgo)
        val maxTime = now.minusSeconds(newestSecondsAgo)

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tickColour
            strokeWidth = 2f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColour
            textSize = 10f
            textAlign = Paint.Align.CENTER
        }

        val candidateTimes = mutableListOf<java.time.ZonedDateTime>()
        var dayCursor = minTime.truncatedTo(ChronoUnit.DAYS).minusDays(1)
        while (dayCursor <= now.plusDays(1)) {
            listOf(0, 6, 12, 18).forEach { hour ->
                candidateTimes.add(dayCursor.withHour(hour).withMinute(0).withSecond(0).withNano(0))
            }
            dayCursor = dayCursor.plusDays(1)
        }

        candidateTimes
            .filter { it >= minTime && it <= maxTime }
            .forEach { tickTime ->
                val secondsAgo = java.time.Duration.between(tickTime, now).seconds.toFloat()
                val x = ((oldestSecondsAgo - secondsAgo) / legacyRange.toFloat()) * drawW
                val label = when (tickTime.hour) {
                    0 -> "12am"
                    12 -> "12pm"
                    else -> null
                }

                if (label != null) {
                    canvas.drawLine(x, heightPx - pad - 14f, x, heightPx - pad - 8f, tickPaint)
                    canvas.drawText(label, x, heightPx - pad - 2f, textPaint)
                } else {
                    canvas.drawLine(x, heightPx - pad - 8f, x, heightPx - pad - 4f, tickPaint)
                }
            }
    }

    private fun smoothDelayHistory(sortedPoints: List<DelayPoint>): List<DelayPoint> {
        if (sortedPoints.size < 3) return sortedPoints

        val totalSpanSeconds = sortedPoints.first().secondsAgo.coerceAtLeast(0)
        val smoothingWindowSeconds = when {
            totalSpanSeconds <= 60 * 60 -> 2 * 60
            totalSpanSeconds <= 3 * 60 * 60 -> 5 * 60
            totalSpanSeconds <= 6 * 60 * 60 -> 10 * 60
            totalSpanSeconds <= 12 * 60 * 60 -> 15 * 60
            else -> 30 * 60
        }

        val n = sortedPoints.size
        val smoothed = MutableList(n) { sortedPoints[it] }

        var left = 0
        var right = 0
        var runningSum = 0f
        var runningCount = 0

        for (i in 0 until n) {
            val center = sortedPoints[i].secondsAgo
            val windowSeconds = smoothingWindowSecondsForPoint(center, smoothingWindowSeconds)
            val halfWindow = windowSeconds / 2
            val windowStart = (center - halfWindow).coerceAtLeast(0)
            val windowEnd = center + halfWindow

            while (left < n && sortedPoints[left].secondsAgo > windowEnd) {
                if (left < right) {
                    runningSum -= sortedPoints[left].totalDelayMinutes
                    runningCount -= 1
                }
                left += 1
            }

            while (right < n && sortedPoints[right].secondsAgo >= windowStart) {
                runningSum += sortedPoints[right].totalDelayMinutes
                runningCount += 1
                right += 1
            }

            if (runningCount > 0) {
                smoothed[i] = DelayPoint(center, runningSum / runningCount)
            }
        }

        return smoothed
    }

    private fun smoothingWindowSecondsForPoint(secondsAgo: Int, maxWindowSeconds: Int): Int {
        val minWindowSeconds = 2 * 60
        if (secondsAgo <= minWindowSeconds) return minWindowSeconds
        if (secondsAgo >= 60 * 60) return maxWindowSeconds

        val progression = (secondsAgo - minWindowSeconds).toFloat() / (60 * 60 - minWindowSeconds)
        return minWindowSeconds + ((maxWindowSeconds - minWindowSeconds) * progression).toInt()
    }

    private fun getSmoothedDelayHistory(history: List<DelayPoint>): List<DelayPoint> {
        val sorted = history.sortedByDescending { it.secondsAgo }
        return smoothDelayHistory(sorted)
    }

    private fun delayColour(delayMin: Float): Int = when {
        delayMin <= 0f  -> Color.rgb(0x4C, 0xAF, 0x50)
        delayMin < 50f  -> lerpColour(Color.rgb(0x4C, 0xAF, 0x50), Color.rgb(0xFF, 0xC1, 0x07), delayMin / 50f)
        delayMin < 100f -> lerpColour(Color.rgb(0xFF, 0xC1, 0x07), Color.rgb(0xF4, 0x43, 0x36), (delayMin - 50f) / 50f)
        delayMin < 150f -> Color.rgb(0xF4, 0x43, 0x36)
        delayMin < 200f -> lerpColour(Color.rgb(0xF4, 0x43, 0x36), Color.rgb(0x9C, 0x27, 0xB0), (delayMin - 150f) / 50f)
        else            -> Color.rgb(0x9C, 0x27, 0xB0)
    }

    private fun lerpColour(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from)   + (Color.red(to)   - Color.red(from))   * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from)  + (Color.blue(to)  - Color.blue(from))  * f).toInt()
        )
    }

}
