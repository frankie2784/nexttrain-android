package com.nexttrain.config

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.nexttrain.R
import com.nexttrain.data.Departure
import com.nexttrain.data.DeparturesEntry
import com.nexttrain.data.DeparturesRepository
import com.nexttrain.data.OdPair
import com.nexttrain.data.dropDeparted
import com.nexttrain.prefs.WidgetPrefs
import com.nexttrain.ui.Formatting
import com.nexttrain.ui.RollingTextView
import com.nexttrain.widget.AlarmScheduler
import com.nexttrain.widget.sendWidgetRefreshBroadcast
import kotlinx.coroutines.*

/**
 * Route departures — the first departure fills a hero card and the rest are
 * rows. Reads from the same [DeparturesRepository] the widget and
 * notification use, so it shows identical numbers and is refreshed on the
 * same 60s cadence, rather than polling on its own timer.
 */
class RouteDeparturesActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var displayTickerJob: Job? = null
    private var manualRefreshJob: Job? = null

    private lateinit var prefs: WidgetPrefs
    private lateinit var tvRouteLabel: TextView
    private lateinit var tvRouteTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var departuresContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvAfterThat: TextView
    private lateinit var cardHero: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rowUpdated: View

    private var pair: OdPair? = null
    private var lastRendered: List<Departure>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_departures)

        prefs = WidgetPrefs(this)

        // Registered on the FragmentManager (not the sheet instance), so it
        // survives the sheet being recreated across a configuration change —
        // see RouteEditorSheet's class doc.
        supportFragmentManager.setFragmentResultListener(RouteEditorSheet.RESULT_KEY, this) { _, bundle ->
            val savedPairId = bundle.getString(RouteEditorSheet.RESULT_PAIR_ID)
            val updated = prefs.getOdPairs().firstOrNull { it.id == savedPairId } ?: return@setFragmentResultListener
            pair = updated
            tvRouteLabel.text = updated.label
            tvRouteTitle.text = "${updated.originName} ➝ ${updated.destinationName}"
            AlarmScheduler.scheduleIfNeeded(this@RouteDeparturesActivity)
            refreshRoute()
        }

        tvRouteLabel = findViewById(R.id.tv_route_label)
        tvRouteTitle = findViewById(R.id.tv_route_title)
        tvStatus = findViewById(R.id.tv_status)
        departuresContainer = findViewById(R.id.departures_container)
        tvEmpty = findViewById(R.id.tv_empty)
        tvAfterThat = findViewById(R.id.tv_after_that)
        cardHero = findViewById(R.id.card_hero)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        rowUpdated = findViewById(R.id.row_updated)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        swipeRefresh.setOnRefreshListener { refreshRoute() }
        rowUpdated.setOnClickListener {
            swipeRefresh.isRefreshing = true
            refreshRoute()
        }

        val pairId = intent.getStringExtra(ConfigActivity.EXTRA_PAIR_ID)
        pair = prefs.getOdPairs().firstOrNull { it.id == pairId }

        findViewById<ImageButton>(R.id.btn_edit_route).setOnClickListener {
            pair?.let { showRouteEditor(it) }
        }

        val selected = pair
        if (selected == null) {
            tvRouteLabel.text = getString(R.string.route_not_found)
            tvRouteTitle.text = ""
            cardHero.visibility = View.GONE
            tvAfterThat.visibility = View.GONE
            showEmpty(getString(R.string.no_route_selected))
            return
        }
        tvRouteLabel.text = selected.label
        tvRouteTitle.text = "${selected.originName} ➝ ${selected.destinationName}"

        // Load cached data while fetching fresh data
        val cached = DeparturesRepository.cached(prefs, selected.id).dropDeparted()
        if (cached.isNotEmpty()) {
            render(cached, prefs.getLastSuccessfulFetch(selected.id))
        }
    }

    override fun onStart() {
        super.onStart()
        startAutoRefresh()
        startPeriodicFullRefresh()
        startDisplayTicker()
        refreshRoute()
    }

    override fun onStop() {
        stopAutoRefresh()
        stopPeriodicFullRefresh()
        stopDisplayTicker()
        super.onStop()
    }

    /**
     * Listens to [DeparturesRepository] so a refresh from anywhere — the
     * widget's background tick (when it happens to be showing this same
     * route), a manual refresh on the dashboard, or this screen's own
     * periodic fetch below — is reflected here immediately.
     */
    private fun startAutoRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            DeparturesRepository.entries.collect { entries ->
                val selected = pair ?: return@collect
                entries[selected.id]?.let { applyEntry(it) }
            }
        }
    }

    private fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * The widget's background tick only fetches the route it's currently
     * showing, which may not be this one. This screen needs its own route
     * kept fresh regardless, so while it's open it fetches every 60s itself
     * — the cost is bounded to screen-on time, same as the dashboard.
     */
    private fun startPeriodicFullRefresh() {
        if (periodicRefreshJob?.isActive == true) return
        periodicRefreshJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                refreshRoute()
            }
        }
    }

    private fun stopPeriodicFullRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Re-applies whatever's already known every few seconds — no fetch, just
     * re-evaluating wall-clock-dependent display (a countdown reaching "Now",
     * a train crossing Departure.hasDeparted). The real network refresh above
     * only runs every 60s, which is far more lag than "a few seconds" for a
     * train that just departed to keep reading "Now".
     */
    private fun startDisplayTicker() {
        if (displayTickerJob?.isActive == true) return
        displayTickerJob = scope.launch {
            while (isActive) {
                delay(DISPLAY_TICK_MS)
                val selected = pair ?: continue
                DeparturesRepository.entries.value[selected.id]?.let { applyEntry(it) }
            }
        }
    }

    private fun stopDisplayTicker() {
        displayTickerJob?.cancel()
        displayTickerJob = null
    }

    /**
     * Refreshes every configured pair through the shared repository — not
     * just this route — and nudges the widget/notification to repaint from
     * the result, so pulling to refresh here brings all three up to date
     * together instead of only this screen.
     */
    private fun refreshRoute() {
        val selected = pair ?: return
        tvStatus.setText(R.string.updating)

        // A pull while a previous refresh is still in flight (e.g. an
        // impatient re-pull on a dead network) cancels that one rather than
        // piling another slow fetch on top of it.
        manualRefreshJob?.cancel()
        manualRefreshJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            val entries = DeparturesRepository.refreshAll(
                context = this@RouteDeparturesActivity,
                prefs = prefs,
                pairs = prefs.getOdPairs(),
                fetchTimeoutMs = INTERACTIVE_FETCH_TIMEOUT_MS,
                reachabilityTimeoutMs = INTERACTIVE_REACHABILITY_TIMEOUT_MS,
                forceRefresh = true,
            )
            entries[selected.id]?.let { applyEntry(it) }
            awaitMinimumSpinnerDuration(start)
            swipeRefresh.isRefreshing = false
            sendWidgetRefreshBroadcast(this@RouteDeparturesActivity, alreadyFetched = true)
        }
    }

    /**
     * On a dead network the refresh can fail in well under 100ms, which is
     * faster than SwipeRefreshLayout's own show/hide animation — so the
     * spinner never becomes visible and a pull-to-refresh looks like it did
     * nothing. Padding out to a minimum visible duration lets the user
     * actually see it spin before it's dismissed.
     */
    private suspend fun awaitMinimumSpinnerDuration(startElapsedMs: Long) {
        val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
        val remaining = MIN_SPINNER_VISIBLE_MS - elapsed
        if (remaining > 0) delay(remaining)
    }

    companion object {
        private const val MIN_SPINNER_VISIBLE_MS = 500L
        // Shorter than DeparturesRepository's background-tick defaults (15s/5s):
        // a manual refresh has a user actively watching the spinner, so it should
        // give up and report "unreachable" faster than an unattended background tick.
        private const val INTERACTIVE_FETCH_TIMEOUT_MS = 6_000L
        private const val INTERACTIVE_REACHABILITY_TIMEOUT_MS = 3_000L
        // How often the screen repaints from already-known data (no fetch) so a
        // departure crossing Departure.hasDeparted doesn't sit as "Now" for anywhere
        // near the 60s network refresh interval.
        private const val DISPLAY_TICK_MS = 5_000L
    }

    private fun applyEntry(entry: DeparturesEntry) {
        val departures = entry.upcoming
        if (departures.isEmpty()) {
            // Nothing cached to fall back on — the blank error state is the
            // best we can do (distinct from a genuine "no trains" response).
            showEmpty(if (entry.unreachable) getString(R.string.server_unreachable) else getString(R.string.no_trains))
            markUpdated(entry.lastUpdatedMs, offline = entry.unreachable)
        } else if (departures != lastRendered || entry.unreachable) {
            // Also re-render (not just re-stamp) on every unreachable tick, even
            // when the departures list itself hasn't changed, so the "offline"
            // hint doesn't silently go stale once the countdown timers do.
            render(departures, entry.lastUpdatedMs, offline = entry.unreachable)
        } else {
            markUpdated(entry.lastUpdatedMs)
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render(departures: List<Departure>, lastUpdatedMs: Long, offline: Boolean = false) {
        lastRendered = departures
        tvEmpty.visibility = View.GONE
        cardHero.visibility = View.VISIBLE
        bindHero(departures.first(), offline)

        val rest = departures.drop(1)
        tvAfterThat.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        renderRows(rest, offline)
        markUpdated(lastUpdatedMs, offline)
    }

    /**
     * Reuses existing row views by position when the row count hasn't
     * changed, so each row's RollingTextView instances persist across a
     * refresh and can roll the old value into the new one — a full rebuild
     * would otherwise present fresh, unanimated views every tick.
     */
    private fun renderRows(rest: List<Departure>, offline: Boolean) {
        if (departuresContainer.childCount == rest.size) {
            rest.forEachIndexed { index, dep -> bindRow(departuresContainer.getChildAt(index), dep, offline) }
        } else {
            departuresContainer.removeAllViews()
            rest.forEach { departuresContainer.addView(buildRow(it, offline)) }
        }
    }

    /**
     * [lastUpdatedMs] is the last successful fetch, not "now" — while offline
     * it stays fixed at whenever the server was last actually reachable,
     * instead of ticking forward on every failed retry.
     */
    private fun markUpdated(lastUpdatedMs: Long, offline: Boolean = false) {
        if (offline && lastUpdatedMs < 0) {
            // Never once succeeded (e.g. first launch with no network) — no
            // real "last updated" time exists to show.
            tvStatus.text = getString(R.string.server_unreachable)
            return
        }
        val time = java.time.Instant.ofEpochMilli(lastUpdatedMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val now = Formatting.formatTime(prefs.use24HourFormat, time)
        tvStatus.text = if (offline) "Offline · last updated $now" else getString(R.string.updated_at, now)
    }

    private fun bindHero(dep: Departure, offline: Boolean = false) {
        findViewById<RollingTextView>(R.id.tv_hero_mins).text = Formatting.minutesValue(dep)
        findViewById<TextView>(R.id.tv_hero_mins_unit).text = Formatting.minutesUnit(this, dep)

        findViewById<RollingTextView>(R.id.tv_hero_departs).text =
            formatDepartureTime(dep, prefix = getString(R.string.departs_prefix))
        pair?.let { findViewById<TextView>(R.id.tv_hero_origin).text = it.originName }
        findViewById<TextView>(R.id.tv_hero_arrives).text = dep.destinationDisplayTime?.let {
            val arriveTime = Formatting.formatTime(prefs.use24HourFormat, it)
            "Arrives $arriveTime"
        } ?: ""
        pair?.let { findViewById<TextView>(R.id.tv_hero_destination).text = it.destinationName }

        val chipStatus = findViewById<LinearLayout>(R.id.chip_status)
        if (offline) {
            // Server unreachable overrides "Scheduled" too — even a timetable-only
            // route can't be confirmed still on schedule while offline.
            chipStatus.visibility = View.VISIBLE
            chipStatus.setBackgroundResource(R.drawable.nt_chip_neutral)
            val tint = ContextCompat.getColor(this, R.color.nt_sub)
            findViewById<ImageView>(R.id.iv_status).apply {
                setImageResource(R.drawable.ic_schedule)
                setColorFilter(tint)
            }
            findViewById<TextView>(R.id.tv_hero_status).apply {
                text = getString(R.string.status_not_confirmed)
                setTextColor(tint)
            }
        } else if (pair?.region?.hasRealtime == false) {
            // No GTFS-RT feed for this region, so there's no delay data to report —
            // showing "On time" here would be a fabricated claim, not an observation.
            // "Scheduled" says plainly this is the timetable, not a live verdict.
            chipStatus.visibility = View.VISIBLE
            chipStatus.setBackgroundResource(R.drawable.nt_chip_neutral)
            val neutral = ContextCompat.getColor(this, R.color.nt_sub)
            findViewById<ImageView>(R.id.iv_status).apply {
                setImageResource(R.drawable.ic_schedule)
                setColorFilter(neutral)
            }
            findViewById<TextView>(R.id.tv_hero_status).apply {
                text = getString(R.string.status_scheduled)
                setTextColor(neutral)
            }
        } else {
            chipStatus.visibility = View.VISIBLE
            val late = dep.isDelayed
            val colour = ContextCompat.getColor(this, if (late) R.color.nt_late else R.color.nt_primary)
            chipStatus.setBackgroundResource(if (late) R.drawable.nt_chip_late else R.drawable.nt_chip_ok)
            findViewById<ImageView>(R.id.iv_status).apply {
                setImageResource(if (late) R.drawable.ic_error else R.drawable.ic_check_circle)
                setColorFilter(colour)
            }
            findViewById<TextView>(R.id.tv_hero_status).apply {
                text = Formatting.status(dep)
                setTextColor(colour)
            }
        }

        val platformChip = findViewById<LinearLayout>(R.id.chip_platform)
        val platform = dep.platformNumber
        if (platform.isNullOrBlank()) {
            platformChip.visibility = View.GONE
        } else {
            platformChip.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_hero_platform).text = getString(R.string.platform_n, platform)
        }
    }

    private fun buildRow(dep: Departure, offline: Boolean = false): View {
        val row = layoutInflater.inflate(R.layout.item_route_departure, departuresContainer, false)
        bindRow(row, dep, offline)
        return row
    }

    private fun bindRow(row: View, dep: Departure, offline: Boolean = false) {
        row.findViewById<RollingTextView>(R.id.tv_dep_mins).text = Formatting.minutesValue(dep)
        row.findViewById<TextView>(R.id.tv_dep_mins_unit).text = Formatting.minutesUnit(this, dep)
        row.findViewById<RollingTextView>(R.id.tv_dep_time).text = formatDepartureTime(dep)
        row.findViewById<TextView>(R.id.tv_dep_meta).text =
            dep.destinationDisplayTime?.let {
                val arriveTime = Formatting.formatTime(prefs.use24HourFormat, it)
                getString(R.string.arrives_at, arriveTime)
            } ?: ""

        val badge = row.findViewById<TextView>(R.id.tv_dep_badge)
        if (offline) {
            badge.apply {
                visibility = View.VISIBLE
                text = getString(R.string.status_not_confirmed)
                setBackgroundResource(R.drawable.nt_chip_neutral)
                setTextColor(ContextCompat.getColor(context, R.color.nt_sub))
            }
        } else if (pair?.region?.hasRealtime == false) {
            badge.apply {
                visibility = View.VISIBLE
                text = getString(R.string.status_scheduled)
                setBackgroundResource(R.drawable.nt_chip_neutral)
                setTextColor(ContextCompat.getColor(context, R.color.nt_sub))
            }
        } else {
            val late = dep.isDelayed
            badge.apply {
                visibility = View.VISIBLE
                text = Formatting.badge(dep)
                setBackgroundResource(if (late) R.drawable.nt_chip_late else R.drawable.nt_chip_ok)
                setTextColor(
                    ContextCompat.getColor(context, if (late) R.color.nt_late else R.color.nt_primary)
                )
            }
        }
    }

    private fun formatDepartureTime(dep: Departure, prefix: String = ""): CharSequence =
        Formatting.departureTimeWithSchedule(this, prefs.use24HourFormat, dep, prefix)

    private fun showEmpty(message: String) {
        cardHero.visibility = View.GONE
        tvAfterThat.visibility = View.GONE
        departuresContainer.removeAllViews()
        tvEmpty.text = message
        tvEmpty.visibility = View.VISIBLE
    }

    private fun showRouteEditor(existing: OdPair) {
        RouteEditorSheet.newInstance(existing).show(supportFragmentManager, RouteEditorSheet.TAG)
    }

    override fun onDestroy() {
        stopAutoRefresh()
        super.onDestroy()
        scope.cancel()
    }
}
