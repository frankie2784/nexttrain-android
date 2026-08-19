package com.nexttrain.config

import android.app.TimePickerDialog
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.animation.doOnEnd
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.nexttrain.R
import com.nexttrain.api.NextTrainApiClient
import com.nexttrain.data.Line
import com.nexttrain.data.MelbourneStations
import com.nexttrain.data.OdPair
import com.nexttrain.data.Region
import com.nexttrain.data.Station
import com.nexttrain.prefs.WidgetPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val ALL_LINES_ID = "__all__"

private data class ReachableStation(
    val station_gtfs_id: String,
    val display_name: String,
    val public_stop_id: String?,
    val sequence: Int?,
)

private data class ReachableResponse(val stations: List<ReachableStation> = emptyList())

private data class StationCatalogStation(
    val display_name: String,
    val public_stop_id: String?,
)

private data class StationCatalogResponse(val stations: List<StationCatalogStation> = emptyList())

private data class LineDto(val line_id: String, val name: String, val color: String?)

private data class LinesResponse(val lines: List<LineDto> = emptyList())

private data class LineStationDto(
    val station_gtfs_id: String,
    val display_name: String,
    val public_stop_id: String?,
    val sequence: Int,
)

private data class LineStationsResponse(val stations: List<LineStationDto> = emptyList())

/**
 * ArrayAdapter that (a) bolds the currently selected item within the dropdown
 * list itself, so it stays identifiable once the list is open and scrolled
 * (the collapsed spinner already shows it, but the long open list doesn't),
 * and (b) can render specific positions greyed-out and unselectable — used to
 * show the origin station in-place in the destination list for context.
 */
private class StationSpinnerAdapter(
    context: android.content.Context,
    items: List<String>,
) : ArrayAdapter<String>(context, R.layout.spinner_item_contrast, items) {
    var selectedPosition: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var disabledPositions: Set<Int> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    init {
        setDropDownViewResource(R.layout.spinner_dropdown_item_contrast)
    }

    override fun areAllItemsEnabled(): Boolean = disabledPositions.isEmpty()

    override fun isEnabled(position: Int): Boolean = position !in disabledPositions

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val rowView = super.getDropDownView(position, convertView, parent)
        val disabled = position in disabledPositions
        (rowView as? TextView)?.apply {
            setTypeface(null, if (position == selectedPosition && !disabled) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context, if (disabled) R.color.nt_muted else R.color.nt_text
                )
            )
        }
        rowView.alpha = if (disabled) 0.6f else 1f
        return rowView
    }
}

/**
 * Route editor. Replaces the AlertDialog that lived in both EditRoutesActivity
 * and RouteDeparturesActivity.
 *
 * All behaviour is carried across intact: the live /stations catalog, the
 * /reachable_destinations filter, the saved-station fallbacks, the suppressed
 * spinner callbacks and both validation rules. Only the presentation changes —
 * a bottom sheet instead of a dialog, seven circles instead of a joined day
 * bar, and inline errors instead of Toasts.
 *
 * The sheet persists the route itself (via WidgetPrefs) rather than handing
 * the finished OdPair back through a lambda: a lambda assigned by the host at
 * show-time is a `var` on this Fragment instance, so a configuration change
 * (rotation) recreates the Fragment with that field null, silently turning
 * Save into a no-op dismiss. Reporting completion through the Fragment
 * Result API instead survives recreation, since the host re-registers its
 * listener on the FragmentManager (not this instance) every time it's
 * created — see ConfigActivity/RouteDeparturesActivity's onCreate.
 *
 *   RouteEditorSheet.newInstance(existingOrNull)
 *       .show(supportFragmentManager, RouteEditorSheet.TAG)
 */
class RouteEditorSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "route_editor"
        private const val ARG_ID = "pair_id"

        /** Fragment Result API key + payload key for the saved route's id. */
        const val RESULT_KEY = "route_editor_result"
        const val RESULT_PAIR_ID = "saved_pair_id"

        /** Pass null to create a new route. */
        fun newInstance(existing: OdPair?): RouteEditorSheet =
            RouteEditorSheet().apply {
                arguments = Bundle().apply { putString(ARG_ID, existing?.id) }
            }
    }

    private lateinit var prefs: WidgetPrefs
    private var existing: OdPair? = null

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.let { bottomSheet ->
                BottomSheetBehavior.from(bottomSheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View =
        inflater.inflate(R.layout.sheet_od_pair, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = WidgetPrefs(requireContext())
        existing = arguments?.getString(ARG_ID)?.let { id ->
            prefs.getOdPairs().firstOrNull { it.id == id }
        }
        val current = existing

        val title = view.findViewById<TextView>(R.id.tv_sheet_title)
        val etLabel = view.findViewById<TextInputEditText>(R.id.et_label)
        val spinnerLine = view.findViewById<Spinner>(R.id.spinner_line)
        val dotLineColor = view.findViewById<ImageView>(R.id.dot_line_color)
        val spinnerOrigin = view.findViewById<Spinner>(R.id.spinner_origin)
        val spinnerDest = view.findViewById<Spinner>(R.id.spinner_destination)
        val tvFrom = view.findViewById<TextView>(R.id.tv_time_from)
        val tvTo = view.findViewById<TextView>(R.id.tv_time_to)
        val tvError = view.findViewById<TextView>(R.id.tv_error)
        val switchNotifications = view.findViewById<SwitchCompat>(R.id.switch_route_notifications)
        val notificationDetails = view.findViewById<View>(R.id.notification_details_container)
        val switchIncludeOnWidget = view.findViewById<SwitchCompat>(R.id.switch_include_on_widget)

        etLabel.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = textView.context.getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(textView.windowToken, 0)
                textView.clearFocus()
                true
            } else {
                false
            }
        }

        view.findViewById<android.widget.ImageView>(R.id.iv_dropdown_line).setOnClickListener {
            spinnerLine.performClick()
        }
        view.findViewById<android.widget.ImageView>(R.id.iv_dropdown_origin).setOnClickListener {
            spinnerOrigin.performClick()
        }
        view.findViewById<android.widget.ImageView>(R.id.iv_dropdown_destination).setOnClickListener {
            spinnerDest.performClick()
        }

        title.setText(if (current == null) R.string.new_route else R.string.edit_route)

        val dayButtons = listOf(
            DayOfWeek.MONDAY.value to view.findViewById<MaterialButton>(R.id.cb_mon),
            DayOfWeek.TUESDAY.value to view.findViewById<MaterialButton>(R.id.cb_tue),
            DayOfWeek.WEDNESDAY.value to view.findViewById<MaterialButton>(R.id.cb_wed),
            DayOfWeek.THURSDAY.value to view.findViewById<MaterialButton>(R.id.cb_thu),
            DayOfWeek.FRIDAY.value to view.findViewById<MaterialButton>(R.id.cb_fri),
            DayOfWeek.SATURDAY.value to view.findViewById<MaterialButton>(R.id.cb_sat),
            DayOfWeek.SUNDAY.value to view.findViewById<MaterialButton>(R.id.cb_sun),
        )

        val notificationSlideOffset = (12 * resources.displayMetrics.density).toInt()

        fun setNotificationDetailsVisible(visible: Boolean, animate: Boolean) {
            notificationDetails.animate().cancel()
            (notificationDetails.getTag(R.id.notification_details_container) as? ValueAnimator)?.cancel()

            if (!animate) {
                notificationDetails.visibility = if (visible) View.VISIBLE else View.GONE
                notificationDetails.alpha = 1f
                notificationDetails.translationY = 0f
                notificationDetails.layoutParams = notificationDetails.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                notificationDetails.requestLayout()
                notificationDetails.setTag(R.id.notification_details_container, null)
                return
            }

            val startHeight = notificationDetails.height.takeIf { it > 0 } ?: 0
            val endHeight = if (visible) {
                notificationDetails.visibility = View.VISIBLE
                notificationDetails.alpha = 0f
                notificationDetails.translationY = -notificationSlideOffset.toFloat()
                notificationDetails.measure(
                    View.MeasureSpec.makeMeasureSpec((notificationDetails.parent as View).width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                notificationDetails.measuredHeight
            } else {
                0
            }

            if (startHeight == endHeight) {
                notificationDetails.alpha = 1f
                notificationDetails.translationY = 0f
                notificationDetails.layoutParams = notificationDetails.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                notificationDetails.visibility = if (visible) View.VISIBLE else View.GONE
                notificationDetails.setTag(R.id.notification_details_container, null)
                return
            }

            notificationDetails.layoutParams = notificationDetails.layoutParams.apply {
                height = startHeight
            }

            val animator = ValueAnimator.ofInt(startHeight, endHeight).apply {
                duration = 220L
                addUpdateListener { valueAnimator ->
                    notificationDetails.layoutParams = notificationDetails.layoutParams.apply {
                        height = valueAnimator.animatedValue as Int
                    }
                    notificationDetails.requestLayout()
                }
                doOnEnd {
                    notificationDetails.layoutParams = notificationDetails.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    notificationDetails.alpha = 1f
                    notificationDetails.translationY = 0f
                    notificationDetails.visibility = if (visible) View.VISIBLE else View.GONE
                    notificationDetails.setTag(R.id.notification_details_container, null)
                }
            }

            notificationDetails.setTag(R.id.notification_details_container, animator)
            notificationDetails.animate()
                .alpha(if (visible) 1f else 0f)
                .translationY(if (visible) 0f else -notificationSlideOffset.toFloat())
                .setDuration(220L)
                .start()
            animator.start()
        }

        fun clearError() { tvError.visibility = View.GONE }
        fun showError(res: Int) {
            tvError.setText(res)
            tvError.visibility = View.VISIBLE
        }

        // ── Stations: cached catalog, then live catalog, then reachable filter ──

        // Fixed for the life of this editor session — no in-editor region picker.
        // New routes use the default region set in Settings; editing an existing
        // route keeps that route's original region (its stop ids belong to that
        // region's GTFS feed and can't be reinterpreted under a different one).
        val selectedRegion: Region = current?.region ?: prefs.selectedRegion

        // Bundled last-resort data for when the server catalog fetch fails and nothing is
        // cached yet. Only regions with an entry here have offline coverage — add new
        // regions' hardcoded station lists to this map as they gain one (see MelbourneStations).
        val offlineFallbackStationsByRegion: Map<Region, List<Station>> = mapOf(
            Region.VIC to MelbourneStations.ALL,
        )

        fun offlineFallbackStations(region: Region): List<Station> =
            offlineFallbackStationsByRegion[region] ?: emptyList()

        var originStations: List<Station> =
            prefs.getCachedStationCatalog().filter { it.region == selectedRegion }
                .ifEmpty { offlineFallbackStations(selectedRegion) }
        var fullOriginCatalog: List<Station> = originStations
        var destStations: List<Station> = originStations
        var filterJob: Job? = null
        var lineFilterJob: Job? = null
        var lines: List<Line> = listOf(Line(ALL_LINES_ID, getString(R.string.all_lines), null))
        var selectedLineId: String? = current?.lineId
        val serverUrl = prefs.serverUrl
        val apiClient = NextTrainApiClient()

        var pendingOriginStopId: Int? = current?.originStopId
        var pendingDestStopId: Int? = current?.destinationStopId
        var selectedOriginStopId: Int? = current?.originStopId
        var selectedDestStopId: Int? = current?.destinationStopId
        var suppressSpinnerCallbacks = false

        fun applyDestStations(
            originStopId: Int,
            candidates: List<Station>,
            preferredStopId: Int? = null,
            consumePending: Boolean = false,
            orderedByLine: Boolean = false,
            preserveSavedDestination: Boolean = true,
        ) {
            // Origin is kept in the list (in its correct line/alphabetical position, for
            // context) rather than filtered out — it's rendered greyed-out and disabled
            // below instead, so it can't actually be picked as a destination.
            destStations = candidates.distinctBy { it.stopId }
            // Only re-inject the originally saved destination while the origin is still
            // the one it was saved against — once the user picks a different origin, a
            // saved destination that isn't a valid candidate for it should stay hidden
            // rather than being force-added back into the dropdown.
            val savedDest = current
                ?.takeIf {
                    preserveSavedDestination &&
                        it.region == selectedRegion &&
                        it.originStopId == originStopId
                }
                ?.let { Station(it.destinationName, it.destinationStopId, it.region) }
            if (savedDest != null && savedDest.stopId != originStopId &&
                destStations.none { it.stopId == savedDest.stopId }
            ) {
                destStations = destStations + savedDest
            }
            destStations = if (orderedByLine) {
                destStations.sortedBy { it.sequence ?: Int.MAX_VALUE }
            } else {
                destStations.sortedBy { it.name }
            }

            val originIdx = destStations.indexOfFirst { it.stopId == originStopId }
            val destAdapter = StationSpinnerAdapter(requireContext(), destStations.map { it.name })
            destAdapter.disabledPositions = if (originIdx >= 0) setOf(originIdx) else emptySet()
            spinnerDest.adapter = destAdapter

            val targetStopId = preferredStopId ?: selectedDestStopId ?: pendingDestStopId
            var selectedIdx = targetStopId
                ?.takeIf { it != originStopId }
                ?.let { id -> destStations.indexOfFirst { it.stopId == id } } ?: -1
            if (selectedIdx < 0) {
                // No valid target (or it resolved to the disabled origin row) — fall back
                // to the first selectable station so the origin is never auto-selected.
                selectedIdx = destStations.indices.firstOrNull { it != originIdx } ?: -1
            }
            if (selectedIdx >= 0) {
                suppressSpinnerCallbacks = true
                spinnerDest.setSelection(selectedIdx)
                suppressSpinnerCallbacks = false
                selectedDestStopId = destStations[selectedIdx].stopId
            }
            if (consumePending && pendingDestStopId == targetStopId) pendingDestStopId = null
            destAdapter.selectedPosition = spinnerDest.selectedItemPosition
        }

        fun updateDestSpinner(
            originStopId: Int,
            preferredDestStopId: Int? = null,
            orderedByLine: Boolean = false,
            preserveSavedDestination: Boolean = true,
        ) {
            val currentDestStopId = spinnerDest.selectedItemPosition
                .takeIf { it in destStations.indices }
                ?.let { destStations[it].stopId }
            val targetDestStopId =
                preferredDestStopId ?: selectedDestStopId ?: currentDestStopId ?: pendingDestStopId
            val consumePending = targetDestStopId != null && targetDestStopId == pendingDestStopId

            applyDestStations(
                originStopId,
                originStations,
                targetDestStopId,
                consumePending,
                orderedByLine,
                preserveSavedDestination,
            )

            // When a line filter is active, originStations is already the line-ordered,
            // same-line candidate set — the /reachable_destinations refine below would
            // just redundantly refetch the same ordering, so skip it.
            if (orderedByLine || serverUrl.isBlank()) return

            spinnerDest.isEnabled = false
            filterJob?.cancel()
            filterJob = viewLifecycleOwner.lifecycleScope.launch {
                val filteredByLine = withContext(Dispatchers.IO) {
                    try {
                        val json = apiClient.getRaw("${NextTrainApiClient.regionUrl(serverUrl, selectedRegion)}/reachable_destinations?stop_id=$originStopId")
                        val response = com.google.gson.Gson().fromJson(json, ReachableResponse::class.java)
                        response.stations.map { s ->
                            Station(
                                name = s.display_name,
                                stopId = s.public_stop_id?.toIntOrNull() ?: 0,
                                region = selectedRegion,
                                sequence = s.sequence,
                            )
                        }.filter { it.stopId > 0 }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Reachable dest fetch failed", e)
                        emptyList()
                    }
                }

                if (filteredByLine.isNotEmpty()) {
                    applyDestStations(
                        originStopId,
                        filteredByLine,
                        targetDestStopId,
                        consumePending = true,
                        orderedByLine = true,
                        preserveSavedDestination = preserveSavedDestination,
                    )
                } else if (pendingDestStopId != null) {
                    pendingDestStopId = null
                }
                spinnerDest.isEnabled = true
            }
        }

        fun applyOriginStations(
            candidates: List<Station>,
            orderedByLine: Boolean = false,
            preserveSavedOrigin: Boolean = true,
            preserveSavedDestination: Boolean = true,
        ) {
            if (candidates.isEmpty()) return
            val deduped = candidates.distinctBy { it.stopId }
            originStations = if (orderedByLine) {
                deduped.sortedBy { it.sequence ?: Int.MAX_VALUE }
            } else {
                deduped.sortedBy { it.name }
            }
            val savedOrigin = current
                ?.takeIf { preserveSavedOrigin && it.region == selectedRegion }
                ?.let { Station(it.originName, it.originStopId, it.region) }
            if (savedOrigin != null && originStations.none { it.stopId == savedOrigin.stopId }) {
                originStations = originStations + savedOrigin
                originStations = if (orderedByLine) {
                    originStations.sortedBy { it.sequence ?: Int.MAX_VALUE }
                } else {
                    originStations.sortedBy { it.name }
                }
            }

            val originAdapter = StationSpinnerAdapter(requireContext(), originStations.map { it.name })
            spinnerOrigin.adapter = originAdapter

            val targetStopId = pendingOriginStopId ?: selectedOriginStopId ?: current?.originStopId
            if (targetStopId != null) {
                val idx = originStations.indexOfFirst { it.stopId == targetStopId }
                if (idx >= 0) {
                    suppressSpinnerCallbacks = true
                    spinnerOrigin.setSelection(idx)
                    suppressSpinnerCallbacks = false
                    selectedOriginStopId = originStations[idx].stopId
                }
                pendingOriginStopId = null
            }
            originAdapter.selectedPosition = spinnerOrigin.selectedItemPosition

            if (originStations.isNotEmpty()) {
                val selected = spinnerOrigin.selectedItemPosition
                val idx = if (selected in originStations.indices) selected else 0
                updateDestSpinner(
                    originStations[idx].stopId,
                    pendingDestStopId,
                    orderedByLine,
                    preserveSavedDestination,
                )
            }
        }

        applyOriginStations(originStations)

        var stationCatalogJob: Job? = null
        fun fetchStationCatalogForSelectedRegion() {
            stationCatalogJob?.cancel()
            if (serverUrl.isBlank()) return
            val regionAtFetchTime = selectedRegion
            spinnerOrigin.isEnabled = false
            spinnerDest.isEnabled = false
            stationCatalogJob = viewLifecycleOwner.lifecycleScope.launch {
                val catalogStations = withContext(Dispatchers.IO) {
                    try {
                        val json = apiClient.getRaw("${NextTrainApiClient.regionUrl(serverUrl, regionAtFetchTime)}/stations")
                        val response = com.google.gson.Gson().fromJson(json, StationCatalogResponse::class.java)
                        response.stations.mapNotNull { s ->
                            val pubId = s.public_stop_id?.toIntOrNull()
                            if (pubId == null || s.display_name.isBlank()) null
                            else Station(name = s.display_name, stopId = pubId, region = regionAtFetchTime)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Station catalog fetch failed", e)
                        emptyList()
                    }
                }

                if (catalogStations.isNotEmpty()) {
                    fullOriginCatalog = catalogStations
                    // Merge into the cache alongside whatever the other region already has,
                    // so reconcileOdPairsWithCatalog() keeps seeing both regions' stations.
                    val merged = prefs.getCachedStationCatalog()
                        .filterNot { it.region == regionAtFetchTime } + catalogStations
                    prefs.updateStationCatalog(merged)
                    if (selectedLineId == null) applyOriginStations(catalogStations)
                } else if (selectedLineId == null) {
                    applyOriginStations(offlineFallbackStations(regionAtFetchTime))
                }
                spinnerOrigin.isEnabled = true
                spinnerDest.isEnabled = true
            }
        }
        fetchStationCatalogForSelectedRegion()

        // ── Line filter: optional, defaults to "All Lines" (today's behaviour) ──

        fun tintLineDot(color: String?) {
            val parsed = try {
                if (color.isNullOrBlank()) null else Color.parseColor("#$color")
            } catch (e: IllegalArgumentException) {
                null
            }
            val fallback = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nt_muted)
            dotLineColor.imageTintList = ColorStateList.valueOf(parsed ?: fallback)
        }

        fun applyLineFilter(lineId: String, resetInvalidStations: Boolean = false) {
            filterJob?.cancel()
            lineFilterJob?.cancel()

            if (lineId == ALL_LINES_ID) {
                selectedLineId = null
                tintLineDot(null)
                applyOriginStations(fullOriginCatalog)
                return
            }

            selectedLineId = lineId
            tintLineDot(lines.firstOrNull { it.lineId == lineId }?.color)

            if (serverUrl.isBlank()) return

            spinnerOrigin.isEnabled = false
            spinnerDest.isEnabled = false
            val regionAtFetchTime = selectedRegion
            lineFilterJob = viewLifecycleOwner.lifecycleScope.launch {
                val lineStations = withContext(Dispatchers.IO) {
                    try {
                        val json = apiClient.getRaw("${NextTrainApiClient.regionUrl(serverUrl, regionAtFetchTime)}/line_stations?line_id=${android.net.Uri.encode(lineId)}")
                        val response = com.google.gson.Gson().fromJson(json, LineStationsResponse::class.java)
                        response.stations.mapNotNull { s ->
                            val pubId = s.public_stop_id?.toIntOrNull() ?: return@mapNotNull null
                            Station(name = s.display_name, stopId = pubId, region = regionAtFetchTime, sequence = s.sequence)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Line stations fetch failed", e)
                        emptyList()
                    }
                }

                if (lineStations.isNotEmpty()) {
                    if (resetInvalidStations) {
                        val lineStopIds = lineStations.map { it.stopId }.toSet()
                        val originIsValid = selectedOriginStopId in lineStopIds
                        val destinationIsValid = selectedDestStopId in lineStopIds
                        val fallbackOrigin = lineStations.firstOrNull {
                            !destinationIsValid || it.stopId != selectedDestStopId
                        }?.stopId
                        val nextOrigin = selectedOriginStopId
                            ?.takeIf { originIsValid }
                            ?: fallbackOrigin
                        val fallbackDestination = lineStations.firstOrNull {
                            it.stopId != nextOrigin
                        }?.stopId
                        val nextDestination = selectedDestStopId
                            ?.takeIf { destinationIsValid && it != nextOrigin }
                            ?: fallbackDestination

                        pendingOriginStopId = null
                        pendingDestStopId = null
                        selectedOriginStopId = nextOrigin
                        selectedDestStopId = nextDestination
                    }
                    applyOriginStations(
                        lineStations,
                        orderedByLine = true,
                        preserveSavedOrigin = !resetInvalidStations,
                        preserveSavedDestination = !resetInvalidStations,
                    )
                }
                spinnerOrigin.isEnabled = true
                spinnerDest.isEnabled = true
            }
        }

        fun resetLineSpinnerToAllLines() {
            selectedLineId = null
            tintLineDot(null)
            lines = listOf(Line(ALL_LINES_ID, getString(R.string.all_lines), null))
            spinnerLine.adapter = ArrayAdapter(
                requireContext(),
                R.layout.spinner_item_contrast,
                lines.map { it.name },
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item_contrast) }
            spinnerLine.isEnabled = false
        }

        var linesJob: Job? = null
        fun fetchLines() {
            linesJob?.cancel()
            resetLineSpinnerToAllLines()
            if (serverUrl.isBlank()) return
            linesJob = viewLifecycleOwner.lifecycleScope.launch {
                val fetchedLines = withContext(Dispatchers.IO) {
                    try {
                        val json = apiClient.getRaw("${NextTrainApiClient.regionUrl(serverUrl, selectedRegion)}/lines")
                        val response = com.google.gson.Gson().fromJson(json, LinesResponse::class.java)
                        response.lines.map { Line(lineId = it.line_id, name = it.name, color = it.color) }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Lines fetch failed", e)
                        emptyList()
                    }
                }

                if (fetchedLines.isNotEmpty()) {
                    lines = listOf(Line(ALL_LINES_ID, getString(R.string.all_lines), null)) + fetchedLines
                    spinnerLine.adapter = ArrayAdapter(
                        requireContext(), R.layout.spinner_item_contrast, lines.map { it.name }
                    ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item_contrast) }
                    spinnerLine.isEnabled = true

                    val preselectIdx = current?.lineId?.let { savedLineId ->
                        lines.indexOfFirst { it.lineId == savedLineId }
                    } ?: -1
                    if (preselectIdx >= 0) {
                        suppressSpinnerCallbacks = true
                        spinnerLine.setSelection(preselectIdx)
                        suppressSpinnerCallbacks = false
                        applyLineFilter(lines[preselectIdx].lineId)
                    }
                }
            }
        }
        fetchLines()

        spinnerLine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                if (position in lines.indices) {
                    applyLineFilter(lines[position].lineId, resetInvalidStations = true)
                }
                clearError()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerOrigin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                if (position in originStations.indices) {
                    selectedOriginStopId = originStations[position].stopId
                    (spinnerOrigin.adapter as? StationSpinnerAdapter)?.selectedPosition = position
                    updateDestSpinner(originStations[position].stopId, orderedByLine = selectedLineId != null)
                }
                clearError()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerDest.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                if (position in destStations.indices) {
                    selectedDestStopId = destStations[position].stopId
                    (spinnerDest.adapter as? StationSpinnerAdapter)?.selectedPosition = position
                }
                clearError()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // ── Time window ────────────────────────────────────────────────────

        var fromTime = current?.activeFrom ?: LocalTime.of(6, 0)
        var toTime = current?.activeTo ?: LocalTime.of(10, 0)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")

        fun paintTimes() {
            tvFrom.text = fromTime.format(fmt)
            tvTo.text = toTime.format(fmt)
        }
        paintTimes()

        (tvFrom.parent as View).setOnClickListener {
            TimePickerDialog(requireContext(), R.style.Theme_NT_TimePicker, { _, h, m ->
                fromTime = LocalTime.of(h, m); paintTimes()
            }, fromTime.hour, fromTime.minute, prefs.use24HourFormat).show()
        }
        (tvTo.parent as View).setOnClickListener {
            TimePickerDialog(requireContext(), R.style.Theme_NT_TimePicker, { _, h, m ->
                toTime = LocalTime.of(h, m); paintTimes()
            }, toTime.hour, toTime.minute, prefs.use24HourFormat).show()
        }

        // ── Days, label, notifications ─────────────────────────────────────

        if (current != null) {
            etLabel.setText(current.label)
            switchNotifications.isChecked = current.notificationsEnabled
            switchIncludeOnWidget.isChecked = current.includeOnWidget
        } else {
            switchNotifications.isChecked = true
            switchIncludeOnWidget.isChecked = true
        }

        setNotificationDetailsVisible(switchNotifications.isChecked, animate = false)
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            setNotificationDetailsVisible(isChecked, animate = true)
            clearError()
        }

        val activeDays = current?.activeDays ?: (1..7).toSet()
        dayButtons.forEach { (day, button) ->
            button.isChecked = day in activeDays
            button.setOnClickListener {
                clearError()
            }
        }

        // ── Save ───────────────────────────────────────────────────────────

        view.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { dismiss() }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            val originPos = spinnerOrigin.selectedItemPosition
            val destPos = spinnerDest.selectedItemPosition
            if (originPos !in originStations.indices) {
                showError(R.string.err_origin_invalid); return@setOnClickListener
            }
            if (destPos !in destStations.indices) {
                showError(R.string.err_destination_invalid); return@setOnClickListener
            }

            val origin = originStations[originPos]
            val destination = destStations[destPos]
            if (origin.stopId == destination.stopId) {
                showError(R.string.err_same_station); return@setOnClickListener
            }

            val days = dayButtons.filter { it.second.isChecked }.map { it.first }.toSet()
            if (switchNotifications.isChecked && days.isEmpty()) {
                showError(R.string.err_no_days); return@setOnClickListener
            }

            val label = etLabel.text?.toString()?.trim()?.ifBlank { null }
                ?: "${origin.name} ➝ ${destination.name}"

            val pairId = current?.id ?: WidgetPrefs.newId()
            val reenabledNotifications = switchNotifications.isChecked &&
                current?.notificationsEnabled == false
            if (reenabledNotifications) {
                prefs.clearNotificationDismissal(pairId)
            }

            val savedPair = OdPair(
                id = pairId,
                label = label,
                originStopId = origin.stopId,
                originName = origin.name,
                destinationStopId = destination.stopId,
                destinationName = destination.name,
                activeFrom = fromTime,
                activeTo = toTime,
                activeDays = days,
                directionId = current?.directionId ?: -1,
                notificationsEnabled = switchNotifications.isChecked,
                includeOnWidget = switchIncludeOnWidget.isChecked,
                lineId = selectedLineId,
                region = selectedRegion,
            )
            if (current == null) prefs.addOdPair(savedPair) else prefs.updateOdPair(savedPair)

            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_PAIR_ID to savedPair.id))
            dismiss()
        }
    }
}
