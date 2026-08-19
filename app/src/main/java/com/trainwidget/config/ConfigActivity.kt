package com.nexttrain.config

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.nexttrain.R
import com.nexttrain.api.NextTrainApiClient
import com.nexttrain.data.DeparturesEntry
import com.nexttrain.data.DeparturesRepository
import com.nexttrain.data.OdPair
import com.nexttrain.data.Region
import com.nexttrain.prefs.WidgetPrefs
import com.nexttrain.ui.Formatting
import com.nexttrain.widget.AlarmScheduler
import com.nexttrain.widget.sendWidgetRefreshBroadcast
import com.nexttrain.widget.shouldPromptForBatteryExemption
import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Dashboard. Add/edit/delete/reorder now live here behind an edit-mode
 * toggle, instead of a separate "manage routes" screen. When launched for
 * widget placement (APPWIDGET_CONFIGURE), the dashboard starts permanently
 * in edit mode and the toolbar toggle becomes a "Save & update widget" action.
 */
class ConfigActivity : AppCompatActivity() {

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 100
        private const val STATE_EDIT_MODE = "is_edit_mode"
        private const val MIN_SPINNER_VISIBLE_MS = 500L
        // Shorter than DeparturesRepository's background-tick defaults (15s/5s):
        // a manual refresh has a user actively watching the spinner, so it should
        // give up and report "unreachable" faster than an unattended background tick.
        private const val INTERACTIVE_FETCH_TIMEOUT_MS = 6_000L
        private const val INTERACTIVE_REACHABILITY_TIMEOUT_MS = 3_000L
        // How often the dashboard repaints from already-known data (no fetch) so a
        // departure crossing Departure.hasDeparted doesn't sit as "Now" for anywhere
        // near the 60s network refresh interval.
        private const val DISPLAY_TICK_MS = 5_000L
        const val EXTRA_PAIR_ID = "extra_pair_id"
    }

    private lateinit var prefs: WidgetPrefs
    private lateinit var dashboardAdapter: DashboardAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var fabAddRoute: FloatingActionButton
    private lateinit var tvUpdated: TextView
    private lateinit var btnEditMode: ImageButton
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var displayTickerJob: Job? = null
    private var manualRefreshJob: Job? = null
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isEditMode = false
    private var lastRefreshElapsedMs = 0L

    private val isWidgetConfigMode: Boolean
        get() = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = WidgetPrefs(this)

        // Registered on the FragmentManager (not the sheet instance), so it
        // survives the sheet being recreated across a configuration change —
        // see RouteEditorSheet's class doc.
        supportFragmentManager.setFragmentResultListener(RouteEditorSheet.RESULT_KEY, this) { _, _ ->
            AlarmScheduler.scheduleIfNeeded(this)
            refreshNotifications()
            maybeShowBatteryOptimizationPrompt()
            // Forced: a new/edited pair has no fresh cache entry to fall
            // back on yet, and an edited stop/direction changes the fetch
            // URL anyway, so there's no stale-cache case to protect here.
            loadDashboard(forceRefresh = true)
        }

        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)

        setContentView(R.layout.activity_config)
        requestNotificationPermissionIfNeeded()
        maybeShowRegionPrompt()
        maybeShowBatteryOptimizationPrompt()

        tvUpdated = findViewById(R.id.tv_updated)
        btnEditMode = findViewById(R.id.btn_edit_mode)

        isEditMode = isWidgetConfigMode || (savedInstanceState?.getBoolean(STATE_EDIT_MODE) ?: false)

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_add_first_route).setOnClickListener {
            showRouteEditor(null)
        }
        fabAddRoute = findViewById(R.id.fab_add_route)
        fabAddRoute.setOnClickListener {
            showRouteEditor(null)
        }
        btnEditMode.setOnClickListener { toggleEditMode() }
        findViewById<View>(R.id.row_updated).setOnClickListener {
            swipeRefresh.isRefreshing = true
            loadDashboard(forceRefresh = true)
        }

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener { loadDashboard(forceRefresh = true) }
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.nt_primary))
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.nt_surface)
        )

        val rv = findViewById<RecyclerView>(R.id.rv_dashboard)
        dashboardAdapter = DashboardAdapter(
            onCardClick = { pair ->
                if (isEditMode) {
                    showRouteEditor(pair)
                } else {
                    startActivity(
                        Intent(this, RouteDeparturesActivity::class.java)
                            .putExtra(EXTRA_PAIR_ID, pair.id)
                    )
                }
            },
            onDeleteClick = { pair -> confirmDeletePair(pair) },
            onDragHandleTouch = { holder -> itemTouchHelper.startDrag(holder) },
            use24HourFormat = { prefs.use24HourFormat },
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = dashboardAdapter
        // A changed row is rebound in place rather than cross-faded against a
        // snapshot of its old self. DefaultItemAnimator's change animation
        // binds the new values into a *second* view holder and fades the two
        // over each other, which both hides the digit roll behind a fade and
        // robs it of its previous value (a fresh holder has nothing to roll
        // from) — so a pull-to-refresh looked like a plain fade. Move, add and
        // remove animations still earn their keep when routes are reordered,
        // so only the change animation is turned off.
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                if (!isEditMode) return false
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                dashboardAdapter.moveItems(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe actions.
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                prefs.saveOdPairs(dashboardAdapter.getPairs())
                refreshNotifications()
            }

            override fun isLongPressDragEnabled() = false
        })
        itemTouchHelper.attachToRecyclerView(rv)

        applyEditModeUi()
        dashboardAdapter.setEditMode(isEditMode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EDIT_MODE, isEditMode)
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        dashboardAdapter.setEditMode(isEditMode)
        applyEditModeUi()
        if (isEditMode) {
            stopAutoRefresh()
            stopPeriodicFullRefresh()
        } else if (!isWidgetConfigMode) {
            startAutoRefresh()
            startPeriodicFullRefresh()
            // The periodic job's own tick always waits a fresh 60s before firing, so
            // catch up immediately here if edit mode was open long enough for the
            // data to have already gone stale — otherwise the dashboard would sit on
            // pre-edit data until that next tick completes.
            val pairs = prefs.getOdPairs()
            if (pairs.isNotEmpty() && SystemClock.elapsedRealtime() - lastRefreshElapsedMs >= 60_000L) {
                refreshDashboard(pairs, forceRefresh = true)
            }
        }
    }

    private fun applyEditModeUi() {
        fabAddRoute.visibility = if (isEditMode) View.VISIBLE else View.GONE
        if (isWidgetConfigMode) {
            btnEditMode.setImageResource(R.drawable.ic_check_circle)
            btnEditMode.contentDescription = getString(R.string.save_update_widget)
            btnEditMode.setOnClickListener { triggerWidgetUpdate() }
        } else if (isEditMode) {
            btnEditMode.setImageResource(R.drawable.ic_close)
            btnEditMode.contentDescription = getString(R.string.cd_exit_edit_mode)
        } else {
            btnEditMode.setImageResource(R.drawable.ic_edit)
            btnEditMode.contentDescription = getString(R.string.cd_edit_mode)
        }
    }

    private fun showRouteEditor(existing: OdPair?) {
        RouteEditorSheet.newInstance(existing).show(supportFragmentManager, RouteEditorSheet.TAG)
    }

    /**
     * Delete confirmation. Fully custom content (see dialog_confirm_delete.xml) so the
     * dialog reads as part of the app rather than a stock Material alert: tinted icon
     * tile, and the same outlined Cancel / filled action pair as the route editor sheet.
     */
    private fun confirmDeletePair(pair: OdPair) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        // SECURITY: the label is user-entered, so escape it before it becomes markup.
        dialogView.findViewById<TextView>(R.id.tv_delete_message).text = HtmlCompat.fromHtml(
            getString(R.string.delete_route_message, TextUtils.htmlEncode(pair.label)),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .show()

        dialogView.findViewById<MaterialButton>(R.id.btn_delete_cancel)
            .setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<MaterialButton>(R.id.btn_delete_confirm).setOnClickListener {
            dialog.dismiss()
            deletePairWithUndo(pair)
        }
    }

    private fun deletePairWithUndo(pair: OdPair) {
        val index = dashboardAdapter.removeEntry(pair.id)
        if (index < 0) return
        prefs.removeOdPair(pair.id)
        refreshNotifications()
        updateEmptyState(prefs.getOdPairs().isEmpty())
        Snackbar.make(findViewById(R.id.rv_dashboard), R.string.route_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                prefs.addOdPair(pair)
                val cached = prefs.getCachedDepartures(pair.id)
                dashboardAdapter.insertEntry(index, DashboardEntry(pair, cached, loading = cached.isEmpty()))
                AlarmScheduler.scheduleIfNeeded(this)
                refreshNotifications()
                updateEmptyState(false)
            }
            .show()
    }

    /** Toggles the "Add a route" empty state and dashboard list, mirroring first-install. */
    private fun updateEmptyState(isEmpty: Boolean) {
        val emptyLayout = findViewById<View>(R.id.layout_empty) ?: return
        if (isEmpty) {
            emptyLayout.visibility = View.VISIBLE
            swipeRefresh.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            fabAddRoute.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            swipeRefresh.visibility = View.VISIBLE
            fabAddRoute.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }
    }

    /** Immediately re-syncs the commute notification against the latest saved routes. */
    private fun refreshNotifications() {
        sendWidgetRefreshBroadcast(this)
    }

    private fun triggerWidgetUpdate() {
        // This activity is the shared `android:configure` target for both the light and
        // dark widget providers, so resolve which one this specific appWidgetId actually
        // belongs to rather than assuming a single provider class.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        if (provider != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = provider
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            sendBroadcast(intent)
        }
        refreshNotifications()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluates battery-optimization exemption every time this screen
        // regains focus, not just at onCreate — the user may have just come
        // back from the Settings screen maybeShowBatteryOptimizationPrompt
        // launched (no callback fires for that; onResume is the only signal),
        // and this is cheap/idempotent enough to run unconditionally.
        AlarmScheduler.scheduleIfNeeded(this)
        // Not forced: returning here from another in-app screen (back button,
        // finishing the route-detail screen, etc.) shouldn't refetch data that's
        // only seconds old. DeparturesRepository's own TTL still fetches when the
        // cache has actually gone stale (e.g. the app was backgrounded a while).
        loadDashboard(forceRefresh = false)
        if (!isEditMode) {
            startAutoRefresh()
            startPeriodicFullRefresh()
        }
    }

    override fun onPause() {
        stopAutoRefresh()
        stopPeriodicFullRefresh()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadDashboard(forceRefresh: Boolean) {
        val pairs = prefs.getOdPairs()
        updateEmptyState(pairs.isEmpty())

        if (pairs.isEmpty()) {
            dashboardAdapter.setItems(emptyList())
        } else {
            // Only announce "Updating..." for a forced refresh (pull-to-refresh,
            // tapping the row, a route edit) — a non-forced call like onResume
            // mostly just re-reads the cache and shouldn't flash this text for
            // what's often a sub-millisecond, no-op "refresh".
            if (forceRefresh) {
                tvUpdated.setText(R.string.updating)
            }
            // Prefer whatever's already showing (may already be fresher than disk
            // cache) over reverting to the on-disk snapshot; only fall back to disk
            // cache for routes not currently displayed (first load, newly added route).
            dashboardAdapter.setItems(
                pairs.map { pair ->
                    val existing = dashboardAdapter.getEntry(pair.id)
                    if (existing != null) {
                        // Carry over cached departures/loading state, but always take the
                        // freshly-read pair metadata (label, etc. may have just been edited).
                        existing.copy(pair = pair)
                    } else {
                        val cached = DeparturesRepository.cached(prefs, pair.id)
                        DashboardEntry(pair, cached, loading = cached.isEmpty())
                    }
                }
            )
            dashboardAdapter.setEditMode(isEditMode)
            refreshDashboard(pairs, forceRefresh)
        }
    }

    /**
     * Fetches every pair through [DeparturesRepository] — the same shared
     * fetch/cache the widget and notification read — then nudges them to
     * repaint from the result, so a manual refresh from this screen (pull to
     * refresh, tapping "Updated ...") brings the widget and notification
     * up to date immediately too, instead of waiting for the next 60s tick.
     */
    private fun refreshDashboard(pairs: List<OdPair>, forceRefresh: Boolean) {
        // A pull while a previous refresh is still in flight (e.g. an
        // impatient re-pull on a dead network) cancels that one rather than
        // piling another slow fetch on top of it — otherwise a user who
        // re-pulls before the ~20s worst case elapses just accumulates
        // stacked requests, none of which resolve any sooner.
        manualRefreshJob?.cancel()
        manualRefreshJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            val entries = DeparturesRepository.refreshAll(
                context = this@ConfigActivity,
                prefs = prefs,
                pairs = pairs,
                fetchTimeoutMs = INTERACTIVE_FETCH_TIMEOUT_MS,
                reachabilityTimeoutMs = INTERACTIVE_REACHABILITY_TIMEOUT_MS,
                forceRefresh = forceRefresh,
            )
            applyEntries(pairs, entries)
            // The padding exists so a fast pull-to-refresh doesn't finish before the
            // spinner even animates in — irrelevant (and just added latency) when
            // nothing was shown for this refresh in the first place.
            if (forceRefresh) {
                awaitMinimumSpinnerDuration(start)
            }
            swipeRefresh.isRefreshing = false
            // "Updated" would otherwise read as a successful fetch even when every
            // pair just fell back to stale cached data — say "Offline" instead so a
            // dead server (or dead network) doesn't look identical to a real refresh.
            // The timestamp is the most recent of any route's last *successful*
            // fetch, not now, so it stays fixed while every route is unreachable.
            val lastUpdatedMs = entries.values.maxOfOrNull { it.lastUpdatedMs } ?: -1L
            markUpdated(lastUpdatedMs, offline = entries.isNotEmpty() && entries.values.all { it.unreachable })
            sendWidgetRefreshBroadcast(this@ConfigActivity, alreadyFetched = true)
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

    private fun applyEntries(pairs: List<OdPair>, entries: Map<String, DeparturesEntry>) {
        val pairsById = pairs.associateBy { it.id }
        val updates = entries.mapNotNull { (pairId, entry) ->
            val pair = pairsById[pairId] ?: return@mapNotNull null
            pairId to DashboardEntry(pair, entry.departures, loading = false, unreachable = entry.unreachable)
        }.toMap()
        dashboardAdapter.applyEntries(updates)
    }

    /**
     * [lastUpdatedMs] is the last successful fetch across every route, not
     * "now" — while offline it stays fixed at whenever the server was last
     * actually reachable, instead of ticking forward on every failed retry.
     */
    private fun markUpdated(lastUpdatedMs: Long, offline: Boolean = false) {
        if (offline && lastUpdatedMs < 0) {
            // Never once succeeded (e.g. first launch with no network) — no
            // real "last updated" time exists to show.
            tvUpdated.text = getString(R.string.connection_error)
            lastRefreshElapsedMs = SystemClock.elapsedRealtime()
            return
        }
        val time = java.time.Instant.ofEpochMilli(lastUpdatedMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        val now = Formatting.formatTime(prefs.use24HourFormat, time)
        // When offline, every route just fell back to stale cached data — say so
        // rather than implying this pull just fetched something new.
        tvUpdated.text = if (offline) "Offline · last updated $now" else getString(R.string.updated_at, now)
        lastRefreshElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * Listens to [DeparturesRepository] so any refresh from anywhere — the
     * widget's own background tick (just the route it's showing), a manual
     * refresh on the route-detail screen, or this screen's own periodic
     * fetch below — is reflected here immediately, without waiting for the
     * next scheduled refresh.
     */
    private fun startAutoRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            DeparturesRepository.entries.collect { applyEntries(prefs.getOdPairs(), it) }
        }
    }

    private fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * The widget's background tick only fetches the one route it's showing
     * (see [com.nexttrain.widget.BaseTrainWidgetProvider]), to avoid
     * background requests for routes nothing is displaying. This dashboard
     * shows every route at once, so while it's actually open it fetches all
     * of them itself, every 60s — the cost is bounded to screen-on time.
     */
    private fun startPeriodicFullRefresh() {
        if (periodicRefreshJob?.isActive == true) return
        periodicRefreshJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                if (!isEditMode) {
                    val pairs = prefs.getOdPairs()
                    if (pairs.isNotEmpty()) refreshDashboard(pairs, forceRefresh = true)
                }
            }
        }
        startDisplayTicker()
    }

    private fun stopPeriodicFullRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
        stopDisplayTicker()
    }

    /**
     * Repaints the dashboard from already-known data every few seconds — no
     * fetch, just re-evaluating wall-clock-dependent display (a countdown
     * reaching "Now", a train crossing Departure.hasDeparted). The real
     * network refresh above only runs every 60s, which is far more lag than
     * "a few seconds" for a train that just departed to keep reading "Now".
     */
    private fun startDisplayTicker() {
        if (displayTickerJob?.isActive == true) return
        displayTickerJob = scope.launch {
            while (isActive) {
                delay(DISPLAY_TICK_MS)
                if (!isEditMode) dashboardAdapter.refreshDisplay()
            }
        }
    }

    private fun stopDisplayTicker() {
        displayTickerJob?.cancel()
        displayTickerJob = null
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS
            )
        }
    }

    /**
     * Android's Doze mode throttles the [AlarmScheduler] 1-minute alarm chain down to
     * roughly once every ~9+ minutes once the screen's been off a while, silently making
     * notifications and the widget go stale — [AlarmManager.setAndAllowWhileIdle]
     * guarantees the alarm eventually fires in Doze, not that it fires on schedule.
     * Exempting the app from battery optimization is the only way to get the requested
     * cadence back. Shown only when the user actually has a notification-enabled route
     * (no point asking otherwise) and skipped entirely once already exempted or once the
     * user has dismissed it — see [shouldPromptForBatteryExemption].
     */
    private fun maybeShowBatteryOptimizationPrompt() {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val shouldPrompt = shouldPromptForBatteryExemption(
            hasNotificationEnabledPair = prefs.getOdPairs().any { it.notificationsEnabled },
            isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName),
            dismissed = prefs.batteryPromptDismissed,
        )
        if (!shouldPrompt) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_prompt_title)
            .setMessage(R.string.battery_prompt_message)
            .setCancelable(false)
            .setPositiveButton(R.string.battery_prompt_allow) { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.battery_prompt_not_now) { _, _ ->
                prefs.batteryPromptDismissed = true
            }
            .show()
    }

    /**
     * First-launch-only prompt: the user must pick a default region before using the
     * app. Non-dismissible (no cancel/back-out) since every route and the idle-state
     * sparkline need a region to query. Editable afterwards from Settings.
     *
     * Title, body, field and action all live in the custom view (setView) — the
     * builder sets no title/message/buttons of its own. That sidesteps the old
     * setItems()/setMessage() panel clash (see git history for the bug that caused)
     * and lets the prompt use the app's own type, field and button styles.
     */
    private fun maybeShowRegionPrompt() {
        // Refresh the served-region cache for next time this (or Settings') picker is
        // built — see WidgetPrefs.getServedRegions. Not awaited: the dialog below uses
        // whichever set was cached as of the last successful probe (defaults to every
        // region until the first one), rather than blocking this non-dismissible prompt
        // on a network round-trip.
        refreshServedRegions()

        if (prefs.hasSelectedRegion) return

        val regions = Region.values().filter { it in prefs.getServedRegions() }
        val dialogView = layoutInflater.inflate(R.layout.dialog_choose_region, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_region_dialog)
        dialogView.findViewById<View>(R.id.iv_dropdown_region_dialog).setOnClickListener {
            spinner.performClick()
        }
        spinner.adapter = ArrayAdapter(
            this, R.layout.spinner_item_contrast, regions.map { it.displayName }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item_contrast) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .show()

        dialogView.findViewById<MaterialButton>(R.id.btn_region_continue).setOnClickListener {
            prefs.selectedRegion = regions.getOrElse(spinner.selectedItemPosition) { Region.VIC }
            dialog.dismiss()
        }
    }

    /** See [maybeShowRegionPrompt] — caches which regions the server actually serves. */
    private fun refreshServedRegions() {
        scope.launch {
            NextTrainApiClient().getServedRegions(prefs.serverUrl)?.let { prefs.setServedRegions(it) }
        }
    }
}
