package com.nexttrain.prefs

import android.content.Context
import android.content.SharedPreferences
import com.nexttrain.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexttrain.data.DelayPoint
import com.nexttrain.data.Departure
import com.nexttrain.data.OdPair
import com.nexttrain.data.withCurrentCountdown
import com.nexttrain.data.Region
import com.nexttrain.data.Station
import java.time.LocalTime
import java.util.UUID

private const val PREFS_NAME = "com.nexttrain.prefs"
private const val KEY_OD_PAIRS = "od_pairs"
private const val KEY_NOTIFICATION_MODE = "notification_mode_enabled"
private const val KEY_DISMISSED_PREFIX = "dismissed_"
private const val KEY_DISMISSED_ROUTE_ID = "dismissed_route_id"
private const val KEY_LAST_ACTIVE_ROUTE_ID = "last_active_route_id"
private const val KEY_SELECTED_ROUTE_ID = "selected_route_id"
private const val KEY_LAST_DELAY_HISTORY = "last_delay_history"
private const val KEY_LAST_DELAY_HISTORY_TIMESTAMP = "last_delay_history_timestamp"
private const val KEY_STATION_CATALOG = "station_catalog"
private const val KEY_CACHED_DEPARTURES_PREFIX = "cached_departures_"
private const val KEY_LAST_SUCCESSFUL_FETCH_PREFIX = "last_successful_fetch_"
private const val KEY_USE_24_HOUR_FORMAT = "use_24_hour_format"
private const val KEY_SELECTED_REGION = "selected_region"
private const val KEY_SERVED_REGIONS = "served_regions"
private const val KEY_BATTERY_PROMPT_DISMISSED = "battery_prompt_dismissed"

/**
 * Correct any pair whose origin/destination stopId no longer matches the
 * catalog entry for that station name within its own region (i.e. the id
 * drifted upstream since the pair was saved). Matching is scoped per-region
 * so a VIC and an SA station sharing a name never cross-match each other's
 * stop id. Pure function — no SharedPreferences/Context dependency — so it
 * can be unit tested directly.
 */
internal fun reconcileOdPairs(pairs: List<OdPair>, catalog: List<Station>): List<OdPair> {
    val byRegionAndName = catalog.groupBy { it.region }.mapValues { (_, stations) ->
        stations.associateBy { it.name }
    }
    return pairs.map { pair ->
        val byName = byRegionAndName[pair.region] ?: emptyMap()
        val correctOriginId = byName[pair.originName]?.stopId ?: pair.originStopId
        val correctDestId = byName[pair.destinationName]?.stopId ?: pair.destinationStopId
        if (correctOriginId == pair.originStopId && correctDestId == pair.destinationStopId) {
            pair
        } else {
            pair.copy(originStopId = correctOriginId, destinationStopId = correctDestId)
        }
    }
}

/** Parse a persisted region name, defaulting to VIC for absent/unrecognised values
 *  (i.e. OD pairs saved before regions existed). Pure — unit testable directly. */
internal fun regionFromNameOrDefault(raw: String?, default: Region = Region.VIC): Region =
    raw?.let { runCatching { Region.valueOf(it) }.getOrNull() } ?: default

/**
 * Thin wrapper around SharedPreferences for all widget configuration.
 */
class WidgetPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── GTFS server URL ───────────────────────────────────────────────────
    //
    // Not user-configurable — every build talks to whichever server its
    // product flavor points at (see BuildConfig.SERVER_URL / app/build.gradle.kts).

    val serverUrl: String get() = BuildConfig.SERVER_URL

    var notificationModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_MODE, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFICATION_MODE, v).apply()

    // ── Battery optimization exemption prompt ────────────────────────────
    //
    // Android's Doze mode throttles the 1-minute AlarmScheduler chain down to
    // roughly once every ~9+ minutes once the screen's been off a while, so
    // notifications/widget go stale unless the app is exempted. The prompt
    // (see ConfigActivity.maybeShowBatteryOptimizationPrompt) is shown at most
    // once — "Not now" sets this flag so the user isn't nagged on every visit.

    var batteryPromptDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_DISMISSED, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_PROMPT_DISMISSED, v).apply()

    // ── Default region ───────────────────────────────────────────────────
    //
    // Chosen once via a first-launch prompt (see ConfigActivity) and editable
    // afterwards from Settings. Used as the starting region for new routes
    // (RouteEditorSheet) and for the idle-state delay sparkline.

    /** True once the user has made an explicit region choice (first-launch prompt, or Settings). */
    val hasSelectedRegion: Boolean get() = prefs.contains(KEY_SELECTED_REGION)

    var selectedRegion: Region
        get() = regionFromNameOrDefault(prefs.getString(KEY_SELECTED_REGION, null))
        set(v) = prefs.edit().putString(KEY_SELECTED_REGION, v.name).apply()

    // ── Server-served regions ────────────────────────────────────────────
    //
    // Which regions this server build actually serves (see NextTrainApiClient.
    // getServedRegions), cached so region pickers can filter out regions the
    // server 404s on without needing a network round-trip on every screen
    // open. Defaults to every region (fail open) until the first successful
    // probe, matching this app's general offline-fallback philosophy — an
    // unconfirmed region is better offered than wrongly hidden.

    fun getServedRegions(): Set<Region> {
        val raw = prefs.getString(KEY_SERVED_REGIONS, null) ?: return Region.values().toSet()
        val parsed = raw.split(",").mapNotNull { name -> runCatching { Region.valueOf(name) }.getOrNull() }.toSet()
        return parsed.ifEmpty { Region.values().toSet() }
    }

    fun setServedRegions(regions: Set<Region>) {
        if (regions.isEmpty()) return
        prefs.edit().putString(KEY_SERVED_REGIONS, regions.joinToString(",") { it.name }).apply()
    }

    // ── Time format ───────────────────────────────────────────────────────

    var use24HourFormat: Boolean
        get() = prefs.getBoolean(KEY_USE_24_HOUR_FORMAT, true)
        set(v) = prefs.edit().putBoolean(KEY_USE_24_HOUR_FORMAT, v).apply()

    // ── OD Pairs ──────────────────────────────────────────────────────────

    fun getOdPairs(): List<OdPair> {
        val json = prefs.getString(KEY_OD_PAIRS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<OdPairDto>>() {}.type
            val dtos: List<OdPairDto> = gson.fromJson(json, type)
            // Stop-id drift is corrected centrally by reconcileOdPairsWithCatalog()
            // whenever a fresh station catalog is fetched — no per-read migration.
            dtos.map { it.toOdPair() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOdPairs(pairs: List<OdPair>) {
        val dtos = pairs.map { OdPairDto.from(it) }
        prefs.edit().putString(KEY_OD_PAIRS, gson.toJson(dtos)).apply()
    }

    fun addOdPair(pair: OdPair) = saveOdPairs(getOdPairs() + pair)

    fun removeOdPair(id: String) = saveOdPairs(getOdPairs().filter { it.id != id })

    fun updateOdPair(pair: OdPair) = saveOdPairs(
        getOdPairs().map { if (it.id == pair.id) pair else it }
    )

    /** Returns all OD pairs currently in their active time window with notifications enabled. */
    fun activeOdPairs(): List<OdPair> = getOdPairs().filter { it.isActiveNow() && it.notificationsEnabled }

    /** Returns OD pairs eligible to be cycled through on the home screen widget. */
    fun widgetOdPairs(): List<OdPair> = getOdPairs().filter { it.includeOnWidget }

    // ── Station catalog cache ────────────────────────────────────────────
    //
    // PTV's public stop IDs (and the station list itself — new stations,
    // renames, closures) drift as GTFS data changes, which previously caused
    // saved routes to silently point at the wrong station. Rather than
    // hand-patch each drift, cache the server's live /stations response here
    // and reconcile saved OdPairs against it by station name every time a
    // fresh catalog is fetched, so IDs self-heal automatically.

    /** Last station catalog successfully fetched from the server, if any. */
    fun getCachedStationCatalog(): List<Station> {
        val json = prefs.getString(KEY_STATION_CATALOG, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Station>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Cache the given station catalog and correct any saved OdPairs whose
     * origin/destination stopId no longer matches the catalog entry for
     * that station name (i.e. the id drifted upstream since the pair was
     * saved). Safe to call on every successful catalog fetch.
     */
    fun updateStationCatalog(catalog: List<Station>) {
        if (catalog.isEmpty()) return
        prefs.edit().putString(KEY_STATION_CATALOG, gson.toJson(catalog)).apply()
        reconcileOdPairsWithCatalog(catalog)
    }

    private fun reconcileOdPairsWithCatalog(catalog: List<Station>) {
        val pairs = getOdPairs()
        val reconciled = reconcileOdPairs(pairs, catalog)
        if (reconciled != pairs) {
            saveOdPairs(reconciled)
        }
    }

    /** Returns route id currently selected for widget display, if any. */
    fun getSelectedRouteId(): String? {
        return prefs.getString(KEY_SELECTED_ROUTE_ID, null)
    }

    /** Persist selected route id for widget display. */
    fun setSelectedRouteId(routeId: String?) {
        prefs.edit().apply {
            if (routeId == null) {
                remove(KEY_SELECTED_ROUTE_ID)
            } else {
                putString(KEY_SELECTED_ROUTE_ID, routeId)
            }
            apply()
        }
    }

    /**
     * Advances to the next stop in the cycle: [route0, route1, ..., sparkline].
     * The sparkline stop is omitted from the cycle when [selectedRegion] has no
     * GTFS-RT feed (see [Region.hasRealtime]) — there's never any delay data to show.
     * Returns the newly selected OdPair, or null when the sparkline stop is selected.
     * Pass [activePairNow] so the cycle starts from the correct position when no
     * explicit selection is stored (i.e. the user is on the default view).
     *
     * When no routes are configured at all, the sparkline (if available) is the
     * default view and the "open the app" prompt is reachable only by tapping to
     * cycle past it — it must never appear on top of a sparkline automatically.
     *
     * The sparkline is temporarily disabled entirely (see [SPARKLINE_ENABLED]) —
     * once re-enabled, drop the extra `SPARKLINE_ENABLED &&` guards below.
     */
    fun cycleToNextRoute(activePairNow: OdPair? = null): OdPair? {
        val pairs = widgetOdPairs()
        if (pairs.isEmpty()) {
            if (!SPARKLINE_ENABLED || !selectedRegion.hasRealtime) {
                setSelectedRouteId(null)
                return null
            }
            setSelectedRouteId(
                if (getSelectedRouteId() == NO_ROUTE_PROMPT_ID) null else NO_ROUTE_PROMPT_ID
            )
            return null
        }

        val includeSparkline = SPARKLINE_ENABLED && selectedRegion.hasRealtime
        val cycleLength = if (includeSparkline) pairs.size + 1 else pairs.size

        val currentId = getSelectedRouteId()
        // Positions: 0..(pairs.size-1) = routes, pairs.size = sparkline (if included)
        val currentIndex = when {
            currentId == SPARKLINE_ROUTE_ID ->
                pairs.size
            currentId != null ->
                pairs.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: pairs.size
            activePairNow != null ->
                pairs.indexOfFirst { it.id == activePairNow.id }.takeIf { it >= 0 } ?: pairs.size
            else ->
                // null + no active route = default view: the sparkline stop when it's
                // in the cycle, otherwise position -1 so the next tap lands on route 0.
                if (includeSparkline) pairs.size else -1
        }

        val nextIndex = (currentIndex + 1) % cycleLength

        return if (includeSparkline && nextIndex == pairs.size) {
            setSelectedRouteId(SPARKLINE_ROUTE_ID)
            null
        } else {
            val next = pairs[nextIndex]
            setSelectedRouteId(next.id)
            next
        }
    }

    // ── Notification dismissal tracking ───────────────────────────────────

    /** Check if user has dismissed notifications for this route. */
    fun isNotificationDismissedByUser(pairId: String): Boolean {
        val dismissedRouteId = prefs.getString(KEY_DISMISSED_ROUTE_ID, null)
        val legacyDismissed = prefs.getBoolean(KEY_DISMISSED_PREFIX + pairId, false)
        return dismissedRouteId == pairId || legacyDismissed
    }

    /** Mark notification as dismissed by user for this route. */
    fun setNotificationDismissedByUser(pairId: String, dismissed: Boolean) {
        prefs.edit().apply {
            if (dismissed) {
                putString(KEY_DISMISSED_ROUTE_ID, pairId)
            } else {
                remove(KEY_DISMISSED_ROUTE_ID)
            }
            putBoolean(KEY_DISMISSED_PREFIX + pairId, dismissed)
            apply()
        }
    }

    /** Clear dismissal flag for this route (called when active window ends). */
    fun clearNotificationDismissal(pairId: String) {
        prefs.edit().apply {
            if (prefs.getString(KEY_DISMISSED_ROUTE_ID, null) == pairId) {
                remove(KEY_DISMISSED_ROUTE_ID)
            }
            remove(KEY_DISMISSED_PREFIX + pairId)
            apply()
        }
    }

    /** Clears any current notification dismissal regardless of route id. */
    fun clearNotificationDismissal() {
        prefs.edit().remove(KEY_DISMISSED_ROUTE_ID).apply()
    }

    /** Last route id seen in an active window, or null when no route is active. */
    fun getLastActiveRouteId(): String? {
        return prefs.getString(KEY_LAST_ACTIVE_ROUTE_ID, null)
    }

    /** Persist current active route id (null means no active route right now). */
    fun setLastActiveRouteId(routeId: String?) {
        prefs.edit().apply {
            if (routeId == null) {
                remove(KEY_LAST_ACTIVE_ROUTE_ID)
            } else {
                putString(KEY_LAST_ACTIVE_ROUTE_ID, routeId)
            }
            apply()
        }
    }

    fun getCachedDelayHistory(region: Region, maxAgeMs: Long = 5 * 60 * 1000L): List<DelayPoint> {
        val timestamp = prefs.getLong(KEY_LAST_DELAY_HISTORY_TIMESTAMP + region.apiPath, -1L)
        if (timestamp < 0 || System.currentTimeMillis() - timestamp > maxAgeMs) {
            clearCachedDelayHistory(region)
            return emptyList()
        }

        val json = prefs.getString(KEY_LAST_DELAY_HISTORY + region.apiPath, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DelayPoint>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedDelayHistory(region: Region, history: List<DelayPoint>) {
        if (history.isEmpty()) return
        prefs.edit().apply {
            putString(KEY_LAST_DELAY_HISTORY + region.apiPath, gson.toJson(history))
            putLong(KEY_LAST_DELAY_HISTORY_TIMESTAMP + region.apiPath, System.currentTimeMillis())
            apply()
        }
    }

    fun clearCachedDelayHistory(region: Region) {
        prefs.edit().apply {
            remove(KEY_LAST_DELAY_HISTORY + region.apiPath)
            remove(KEY_LAST_DELAY_HISTORY_TIMESTAMP + region.apiPath)
            apply()
        }
    }

    fun getCachedDepartures(pairId: String): List<Departure> {
        val json = prefs.getString(KEY_CACHED_DEPARTURES_PREFIX + pairId, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Departure>>() {}.type
            val cached: List<Departure> = gson.fromJson(json, type)
            cached.map { it.withCurrentCountdown() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedDepartures(pairId: String, departures: List<Departure>) {
        if (departures.isEmpty()) return
        prefs.edit().putString(KEY_CACHED_DEPARTURES_PREFIX + pairId, gson.toJson(departures)).apply()
    }

    fun clearCachedDepartures(pairId: String) {
        prefs.edit().remove(KEY_CACHED_DEPARTURES_PREFIX + pairId).apply()
    }

    /**
     * Wall-clock time of [pairId]'s last successful (non-unreachable) fetch, or
     * -1L if it has never succeeded. Persisted separately from the cached
     * departures themselves so "last updated" can keep pointing at the last real
     * data even across process death, rather than resetting to "now" just
     * because the app was relaunched while the server is still down.
     */
    fun getLastSuccessfulFetch(pairId: String): Long =
        prefs.getLong(KEY_LAST_SUCCESSFUL_FETCH_PREFIX + pairId, -1L)

    fun saveLastSuccessfulFetch(pairId: String, timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_FETCH_PREFIX + pairId, timestampMs).apply()
    }

    // ── Gson-serialisable DTO (avoids java.time serialization issues) ─────

    private data class OdPairDto(
        val id: String,
        val label: String,
        val originStopId: Int,
        val originName: String,
        val destinationStopId: Int,
        val destinationName: String,
        val activeFromHour: Int,
        val activeFromMinute: Int,
        val activeToHour: Int,
        val activeToMinute: Int,
        val activeDays: List<Int>? = null,
        val directionId: Int,
        val notificationsEnabled: Boolean = true,
        val includeOnWidget: Boolean = true,
        val lineId: String? = null,
        // Absent on OD pairs saved before regions existed — those all predate
        // SA support, so they default to VIC on load (see toOdPair()).
        val region: String? = null
    ) {
        fun toOdPair() = OdPair(
            id = id,
            label = label,
            originStopId = originStopId,
            originName = originName,
            destinationStopId = destinationStopId,
            destinationName = destinationName,
            activeFrom = LocalTime.of(activeFromHour, activeFromMinute),
            activeTo = LocalTime.of(activeToHour, activeToMinute),
            activeDays = activeDays?.toSet() ?: (1..7).toSet(),
            directionId = directionId,
            notificationsEnabled = notificationsEnabled,
            includeOnWidget = includeOnWidget,
            lineId = lineId,
            region = regionFromNameOrDefault(region)
        )

        companion object {
            fun from(p: OdPair) = OdPairDto(
                id = p.id,
                label = p.label,
                originStopId = p.originStopId,
                originName = p.originName,
                destinationStopId = p.destinationStopId,
                destinationName = p.destinationName,
                activeFromHour = p.activeFrom.hour,
                activeFromMinute = p.activeFrom.minute,
                activeToHour = p.activeTo.hour,
                activeToMinute = p.activeTo.minute,
                activeDays = p.activeDays.toList(),
                directionId = p.directionId,
                notificationsEnabled = p.notificationsEnabled,
                includeOnWidget = p.includeOnWidget,
                lineId = p.lineId,
                region = p.region.name
            )
        }
    }

    companion object {
        const val SPARKLINE_ROUTE_ID = "__sparkline__"
        const val NO_ROUTE_PROMPT_ID = "__no_route_prompt__"

        /** Widget sparkline is hidden for now across all regions; revisit in a future version. */
        const val SPARKLINE_ENABLED = false
        fun newId(): String = UUID.randomUUID().toString()
    }
}
