package com.nexttrain.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nexttrain.api.DeparturesResult
import com.nexttrain.api.NextTrainApiClient
import com.nexttrain.prefs.WidgetPrefs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Latest known departures for one OD pair, plus whether the server was
 * reachable and when data was last *actually* fetched from it.
 *
 * [lastUpdatedMs] only ever advances on a successful fetch — it is not
 * "now" while [unreachable], so a status line built from it reads "last
 * updated 9:14" and holds that time fixed for as long as the server stays
 * down, rather than ticking forward on every failed retry.
 */
data class DeparturesEntry(
    val departures: List<Departure>,
    val unreachable: Boolean,
    val lastUpdatedMs: Long,
) {
    /** [departures] with anything more than a few seconds past due dropped — see [Departure.hasDeparted]. */
    val upcoming: List<Departure> get() = departures.dropDeparted()
}

/**
 * Single fetch-and-cache layer shared by the widget, the commute notification
 * and every in-app screen. Whoever triggers a refresh (the 60s alarm tick, or
 * a manual pull-to-refresh) fetches once per OD pair and publishes the result
 * here; every other consumer reads the same snapshot instead of issuing its
 * own request, so the widget, the notification and the app never disagree.
 *
 * Process-wide singleton — safe because the widget, notification and app
 * screens all run in this app's single default process (no `android:process`
 * override on any component).
 */
object DeparturesRepository {

    private val client = NextTrainApiClient()

    private val _entries = MutableStateFlow<Map<String, DeparturesEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DeparturesEntry>> = _entries

    /** Last known departures for [pairId] — from this process's memory if it has
     *  fetched since launch, otherwise the on-disk snapshot from a prior run. */
    fun cached(prefs: WidgetPrefs, pairId: String): List<Departure> =
        _entries.value[pairId]?.departures ?: prefs.getCachedDepartures(pairId)

    /**
     * Fetch every pair in [pairs] concurrently, publish each result to
     * [entries] as it lands, and persist it to [prefs] so a fresh process
     * (e.g. the widget after the app was killed) starts from the same data.
     *
     * Checks connectivity once up front rather than letting each pair's HTTP
     * call independently discover a dead network — that discovery is a real
     * socket operation (DNS + TCP connect) whose failure timing varies per
     * call, so concurrent pairs could flip to "unreachable" seconds apart
     * even though none of them ever had a chance of succeeding. The upfront
     * check is a local query against state Android already maintains, not a
     * network request, so it costs nothing to do every time.
     */
    suspend fun refreshAll(
        context: Context,
        prefs: WidgetPrefs,
        pairs: List<OdPair>,
        fetchTimeoutMs: Long = 15_000L,
        reachabilityTimeoutMs: Long = 5_000L,
        forceRefresh: Boolean = false,
    ): Map<String, DeparturesEntry> = coroutineScope {
        val distinctPairs = pairs.distinctBy { it.id }
        if (!isNetworkAvailable(context)) {
            return@coroutineScope distinctPairs.associate { pair -> pair.id to unreachableEntry(prefs, pair) }
        }
        distinctPairs
            .map { pair -> async { pair.id to fetchOne(prefs, pair, fetchTimeoutMs, reachabilityTimeoutMs, forceRefresh) } }
            .awaitAll()
            .toMap()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Marks [pair] unreachable, but still serves the last-known-good cached
     * departures (if any) rather than an empty list — a commuter mid-journey
     * is better served by stale times with an "offline" hint (see
     * TrainWidgetProvider/CommuteNotificationManager) than a blank error
     * screen. The on-disk cache itself is left untouched (no
     * clearCachedDepartures call) so a transient network blip or a
     * server-side error doesn't destroy it for the *next* process/fetch
     * either.
     */
    private fun unreachableEntry(prefs: WidgetPrefs, pair: OdPair): DeparturesEntry {
        val entry = DeparturesEntry(
            departures = prefs.getCachedDepartures(pair.id),
            unreachable = true,
            lastUpdatedMs = prefs.getLastSuccessfulFetch(pair.id),
        )
        _entries.update { it + (pair.id to entry) }
        return entry
    }

    private suspend fun fetchOne(
        prefs: WidgetPrefs,
        pair: OdPair,
        fetchTimeoutMs: Long,
        reachabilityTimeoutMs: Long,
        forceRefresh: Boolean,
    ): DeparturesEntry {
        val result = client.fetchDepartures(
            serverUrl = prefs.serverUrl,
            region = pair.region,
            stopId = pair.originStopId,
            destinationStopId = pair.destinationStopId,
            directionId = pair.directionId,
            maxResults = 6,
            fetchTimeoutMs = fetchTimeoutMs,
            reachabilityTimeoutMs = reachabilityTimeoutMs,
            forceRefresh = forceRefresh,
        )

        val entry = when (result) {
            // Both "couldn't confirm reachability" and "request failed while the
            // server was up" must never read as "no trains" nor wipe the disk
            // cache — see unreachableEntry.
            is DeparturesResult.Unreachable, is DeparturesResult.Error -> return unreachableEntry(prefs, pair)
            is DeparturesResult.Success -> {
                if (result.departures.isNotEmpty()) {
                    prefs.saveCachedDepartures(pair.id, result.departures)
                } else {
                    prefs.clearCachedDepartures(pair.id)
                }
                val nowMs = System.currentTimeMillis()
                prefs.saveLastSuccessfulFetch(pair.id, nowMs)
                DeparturesEntry(result.departures, unreachable = false, lastUpdatedMs = nowMs)
            }
        }

        _entries.update { it + (pair.id to entry) }
        return entry
    }
}
