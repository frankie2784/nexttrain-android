package com.nexttrain.api

import android.util.Log
import com.google.gson.Gson
import com.nexttrain.BuildConfig
import com.nexttrain.data.Departure
import com.nexttrain.data.DelayHistoryResponse
import com.nexttrain.data.DelayPoint
import com.nexttrain.data.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val TAG = "NextTrainApi"

/**
 * Attaches the shared secret required by the server on every request
 * (checked with `hmac.compare_digest` server-side, so an exact string
 * match is all that's needed — no encoding/signing).
 *
 * SECURITY: the key is injected at build time via [BuildConfig] (sourced
 * from local.properties/CI env, never committed) — never log this header.
 * See NextTrainApiClient.httpClient for why logging is already gated
 * behind BuildConfig.DEBUG.
 */
private class ApiKeyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader("X-API-Key", BuildConfig.NEXTTRAIN_API_KEY)
            .build()
        return chain.proceed(request)
    }
}


// ── Server response models ────────────────────────────────────────────────

data class ServerDeparturesResponse(
    val stop_id: String,
    val departures: List<ServerDeparture>,
    val timestamp: String
)

data class ServerDeparture(
    val trip_id: String?,
    val route_id: String?,
    val direction_id: String?,
    val scheduled_time: String,
    val estimated_time: String?,
    val delay_minutes: Int,
    val delay_seconds: Int?,
    val minutes_until: Long,
    val departure_unix_ts: Long?,            // epoch seconds; null when server is an older build
    val trip_headsign: String?,
    val platform: String?,
    val destination_scheduled_time: String?, // "HH:mm" scheduled arrival at destination
    val destination_estimated_time: String?, // "HH:mm" real-time arrival at destination
)

/** Matches the root-level, region-agnostic GET /health response (see app.py). */
private data class HealthResponse(val status: String?, val regions: Map<String, Any>? = null)

/**
 * Outcome of a departures fetch that distinguishes "server reachable but
 * genuinely has nothing to report" from "couldn't confirm either way" and
 * from "server responded but the request itself failed" — all three can look
 * identical as a bare empty list, but callers must show different messaging
 * ("No trains found" vs "Connection error") and must never treat [Error] or
 * [Unreachable] as grounds to discard a last-known-good cached result.
 */
sealed class DeparturesResult {
    data class Success(val departures: List<Departure>) : DeparturesResult()
    object Unreachable : DeparturesResult()
    object Error : DeparturesResult()
}

// ── API Client ──────────────────────────────────────────────────────────────

/**
 * GTFS server API client.
 *
 * All GTFS processing runs on the server; the widget calls GET /departures.
 */
class NextTrainApiClient {

    companion object {
        // Region-scoped resource endpoints (departures, stations, lines, ...) live
        // under a versioned prefix so the server can evolve the response shape
        // behind /api/v2 without breaking widgets still pointed at /api/v1. The
        // root-level /health liveness probe is deliberately unversioned — it's an
        // infra check, not a resource clients parse.
        private const val API_PREFIX = "api/v1"

        fun regionUrl(serverUrl: String, region: Region): String =
            "$serverUrl/$API_PREFIX/${region.apiPath}"

        // Process-wide HTTP client so connection pooling + keep-alive persist
        // across NextTrainApiClient instances (widget updates, notifications, dashboard).
        // OkHttp's pool is per-client, so a single shared instance lets a warm
        // TLS connection survive between fetches instead of being rebuilt each
        // time a new client is constructed. Idle connections are still evicted
        // after OkHttp's default 5-minute keep-alive, so nothing lingers.
        val httpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(ApiKeyInterceptor())
            // SECURITY: every request URL (including stop ids) would otherwise be
            // logged to logcat in production builds too — debug builds only.
            // Level.BASIC never logs headers, so the API key interceptor above is
            // unaffected regardless of ordering.
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor { Log.d(TAG, it) }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
                builder.addInterceptor(logging)
            }
            builder.build()
        }

        // PERF: CACHE — the widget paint and the notification are refreshed from
        // the same 60s alarm tick and usually want the same stop. Memoising the
        // raw server rows for less than one tick means those two callers share a
        // single request instead of issuing two, without ever serving data that
        // outlives the tick that fetched it.
        const val DEPARTURES_TTL_MS = 30_000L

        private val departuresCache = ConcurrentHashMap<String, CachedDepartures>()
    }

    /** Raw server rows plus the wall-clock time they were fetched. */
    private data class CachedDepartures(
        val fetchedAtMs: Long,
        val rows: List<ServerDeparture>
    )

    private val gson by lazy { Gson() }

    /**
     * Perform a GET against [url] using the shared pooled client and return the
     * response body as a string. Throws on transport/HTTP errors.
     *
     * Dispatched via OkHttp's async `enqueue` rather than blocking `execute`, and
     * wired to coroutine cancellation via [suspendCancellableCoroutine]. A plain
     * blocking call here would ignore an enclosing `withTimeoutOrNull` — cancelling
     * the coroutine wouldn't stop the underlying socket operation, so a caller that
     * gave up waiting would still leave the request running (and a thread tied up)
     * until OkHttp's own connect/read timeouts elapsed. On a dead network that
     * meant repeated refresh attempts could pile up faster than they drained,
     * making the app look unresponsive for many attempts before failing.
     */
    private suspend fun httpGet(url: String): String = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(Request.Builder().url(url).get().build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!cont.isActive) return
                    if (!it.isSuccessful) {
                        cont.resumeWithException(IOException("HTTP ${it.code} for $url"))
                        return
                    }
                    val body = it.body?.string()
                    if (body == null) {
                        cont.resumeWithException(IOException("Empty body for $url"))
                    } else {
                        cont.resume(body, onCancellation = null)
                    }
                }
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Fetch departures from the GTFS server instead of the PTV API directly.
     * The server merges static schedule + real-time delays and returns
     * pre-processed Departure objects.
     */
    suspend fun getDeparturesFromServer(
        serverUrl: String,
        region: Region,
        stopId: Int,
        destinationStopId: Int? = null,
        directionId: Int = -1,
        maxResults: Int = 5,
        forceRefresh: Boolean = false,
    ): List<Departure> = withContext(Dispatchers.IO) {
        val sb = StringBuilder("${regionUrl(serverUrl, region)}/departures?stop_id=$stopId")
        if (destinationStopId != null) sb.append("&destination_stop_id=$destinationStopId")
        sb.append("&max_results=$maxResults")
        if (directionId >= 0) sb.append("&direction_id=$directionId")
        val url = sb.toString()

        val nowMs = System.currentTimeMillis()
        // A manual/foreground refresh must always hit the network — reusing a
        // cache entry here would silently serve stale data while still
        // reporting "just updated", which is indistinguishable from a real
        // refresh to the user. The cache is only for de-duping the widget and
        // notification when they want the same stop within one background tick.
        val fresh = if (forceRefresh) null else departuresCache[url]?.takeIf { nowMs - it.fetchedAtMs < DEPARTURES_TTL_MS }

        // Deliberately not caught here: an HTTP/parse failure must propagate to
        // fetchDepartures so it can be reported as DeparturesResult.Error rather
        // than silently collapsing into an empty list indistinguishable from a
        // genuine "no trains" response.
        val entry = fresh ?: run {
            val json = httpGet(url)
            val response = gson.fromJson(json, ServerDeparturesResponse::class.java)
            CachedDepartures(nowMs, response.departures).also { departuresCache[url] = it }
        }

        toDepartures(entry, nowMs)
    }

    /**
     * Map cached server rows to [Departure]s using the *current* clock, so a
     * cache hit still reports accurate countdowns rather than the ones that were
     * correct when the rows were fetched.
     */
    private fun toDepartures(entry: CachedDepartures, nowMs: Long): List<Departure> {
        val nowSeconds = nowMs / 1000L
        // Rows without an absolute timestamp only carry a relative countdown, so
        // age it by however long the entry has been cached.
        val cachedMinutes = (nowMs - entry.fetchedAtMs) / 60_000L
        return entry.rows.map { dep ->
            val minutesUntil = if (dep.departure_unix_ts != null) {
                (dep.departure_unix_ts - nowSeconds) / 60L
            } else {
                dep.minutes_until - cachedMinutes
            }
            Departure(
                scheduledTime = dep.scheduled_time,
                estimatedTime = dep.estimated_time,
                delayMinutes = dep.delay_minutes,
                platformNumber = dep.platform,
                minutesUntilDeparture = minutesUntil,
                departureUnixTs = dep.departure_unix_ts,
                destinationScheduledTime = dep.destination_scheduled_time,
                destinationEstimatedTime = dep.destination_estimated_time,
            )
        }
    }

    /**
     * Fetch departures and, when the result is empty, confirm whether that's
     * because the server has genuinely nothing to report, because it
     * couldn't be reached at all, or because the request itself failed while
     * the server was reachable (HTTP 4xx/5xx, malformed JSON) — a bare empty
     * list can't tell any of these apart, but callers must not report the
     * last two as "no trains found". Wraps both the fetch and the follow-up
     * reachability check in their own timeouts so a hung server can't block a
     * caller indefinitely.
     */
    suspend fun fetchDepartures(
        serverUrl: String,
        region: Region,
        stopId: Int,
        destinationStopId: Int? = null,
        directionId: Int = -1,
        maxResults: Int = 5,
        fetchTimeoutMs: Long = 15_000L,
        reachabilityTimeoutMs: Long = 5_000L,
        forceRefresh: Boolean = false,
    ): DeparturesResult {
        var fetchFailed = false
        val departures = try {
            withTimeoutOrNull(fetchTimeoutMs) {
                getDeparturesFromServer(serverUrl, region, stopId, destinationStopId, directionId, maxResults, forceRefresh)
            } ?: run {
                // Timed out before the server responded — a real failure, not
                // confirmation that there are no trains. Must set fetchFailed so
                // this can never fall through to Success(emptyList()) below: that
                // would read as an authoritative empty result and (via
                // DeparturesRepository.fetchOne) wipe the last-known-good cache
                // over what might just be one slow response.
                Log.w(TAG, "Timed out fetching departures from server $serverUrl")
                fetchFailed = true
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch departures from server $serverUrl", e)
            fetchFailed = true
            null
        }

        if (departures == null || departures.isEmpty()) {
            val reachable = withTimeoutOrNull(reachabilityTimeoutMs) { isServerReachable(serverUrl) } ?: false
            if (!reachable) return DeparturesResult.Unreachable
            if (fetchFailed) return DeparturesResult.Error
        }

        return DeparturesResult.Success(departures ?: emptyList())
    }

    suspend fun getDelayHistory(serverUrl: String, region: Region): List<DelayPoint> = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("${regionUrl(serverUrl, region)}/delay_history")
            val response = gson.fromJson(json, DelayHistoryResponse::class.java)
            response.points.map { DelayPoint(it.seconds_ago, it.total_delay_minutes) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch delay history from $serverUrl", e)
            emptyList()
        }
    }

    suspend fun isServerReachable(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            httpGet("$serverUrl/health")
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Return the set of [Region]s this server build actually has a blueprint
     * registered for (see app.py's NEXTTRAIN_REGIONS / RUNTIME), or null on
     * any failure. The key being present means every region-scoped endpoint
     * will resolve — even if that region's GTFS hasn't finished loading yet
     * (a transient 503, not a permanent 404) — so callers can safely use this
     * to filter which regions the app offers to pick, instead of offering a
     * region the server 404s on entirely.
     */
    suspend fun getServedRegions(serverUrl: String): Set<Region>? = withContext(Dispatchers.IO) {
        try {
            val json = httpGet("$serverUrl/health")
            val response = gson.fromJson(json, HealthResponse::class.java)
            val keys = response.regions?.keys ?: return@withContext null
            Region.values().filter { it.apiPath in keys }.toSet().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch served regions from $serverUrl", e)
            null
        }
    }

    /**
     * Blocking GET against [url] using the shared pooled client, run on [Dispatchers.IO].
     * For callers with a bespoke response shape (station/line lookups) that don't fit the
     * cached departures/delay-history flows above. Throws on transport/HTTP errors — callers
     * are expected to catch and log, as the existing endpoints here do.
     */
    suspend fun getRaw(url: String): String = withContext(Dispatchers.IO) { httpGet(url) }
}
