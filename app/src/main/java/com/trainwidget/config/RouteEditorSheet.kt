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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
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
 * Filtering adapter behind the origin and destination [AutoCompleteTextView]s.
 *
 * With no query it shows [source] untouched, in whatever order the caller already
 * sorted it (alphabetical, or by line sequence), so tapping a field and scrolling
 * browses exactly the list the old Spinner did — with the current selection bolded
 * so it stays identifiable once the list is long and scrolled. Typing narrows to a
 * case-insensitive substring match.
 *
 * [blockedStopId] is the one station that can't be picked here — the origin, in the
 * destination list. It stays in the unfiltered list, greyed out and unselectable, for
 * positional context; but it drops out entirely the moment the user types anything,
 * even its own name, since a search hit that can't be tapped is worse than one that
 * was never offered.
 */
private class StationFilterAdapter(
    context: android.content.Context,
    private val source: List<Station>,
    private val blockedStopId: Int?,
) : ArrayAdapter<Station>(context, R.layout.spinner_dropdown_item_contrast, source.toMutableList()) {

    private var shown: List<Station> = source
    private var showingFullList: Boolean = true

    /** Bolded in the dropdown. Set by the caller alongside the field's own text. */
    var selectedStopId: Int? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = shown.size

    override fun getItem(position: Int): Station? = shown.getOrNull(position)

    /**
     * Restores the unfiltered list synchronously. Reopening a blanked field can't wait
     * for [Filter]'s background pass — the dropdown would show the previous query's
     * matches for a frame before catching up.
     */
    fun resetToFullList() {
        shown = source
        showingFullList = true
        notifyDataSetChanged()
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val matches = if (query.isEmpty()) {
                source
            } else {
                source.filter { it.stopId != blockedStopId && it.name.contains(query, ignoreCase = true) }
            }
            return FilterResults().apply { values = matches; count = matches.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            shown = results.values as? List<Station> ?: emptyList()
            showingFullList = constraint?.toString()?.trim().isNullOrEmpty()
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): String =
            (resultValue as? Station)?.name.orEmpty()
    }

    override fun areAllItemsEnabled(): Boolean = false

    override fun isEnabled(position: Int): Boolean =
        !(showingFullList && blockedStopId != null && shown.getOrNull(position)?.stopId == blockedStopId)

    // AutoCompleteTextView's popup list is a plain ListView over this adapter, not a
    // Spinner — it renders rows via getView(), never getDropDownView() (that split only
    // matters for Spinner). Overriding the wrong one leaves getView() falling through to
    // ArrayAdapter's default, which renders Station's data-class toString().
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val rowView = super.getView(position, convertView, parent)
        val station = shown.getOrNull(position)
        val disabled = !isEnabled(position)
        (rowView as? TextView)?.apply {
            text = station?.name.orEmpty()
            setTypeface(
                null,
                if (station?.stopId == selectedStopId && !disabled) Typeface.BOLD else Typeface.NORMAL,
            )
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
        (dialog as? BottomSheetDialog)?.let { sheetDialog ->
            sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { bottomSheet ->
                    BottomSheetBehavior.from(bottomSheet).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            // A dialog's window doesn't resize for the keyboard by default (unlike an
            // Activity's, which picks up windowSoftInputMode from the manifest). Without
            // this, the origin/destination dropdowns compute their position against the
            // full, keyboard-less screen height and end up rendered underneath the IME.
            sheetDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
        val acOrigin = view.findViewById<AutoCompleteTextView>(R.id.ac_origin)
        val acDestination = view.findViewById<AutoCompleteTextView>(R.id.ac_destination)
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

        /**
         * Wires one station field so it reads as a picker until it's touched and as a
         * search box once it is.
         *
         * Focusing it blanks the text and drops the selected station's name into the hint,
         * so the field is immediately ready to type into while still showing what's
         * currently chosen; the dropdown opens on the full list with that station bolded.
         * The selection itself only ever changes by tapping a row — typing and then
         * dismissing restores the previous station, exactly as the Spinner's
         * pick-or-nothing contract did.
         */
        fun bindStationPicker(
            field: AutoCompleteTextView,
            chevron: ImageView,
            selectedStation: () -> Station?,
            onPicked: (Station) -> Unit,
        ) {
            fun blankForSearch() {
                field.hint = selectedStation()?.name
                // Not setText("") — AutoCompleteTextView's threshold floors at 1, so an
                // emptied field never re-filters itself; reset the adapter directly.
                field.setText("", false)
                (field.adapter as? StationFilterAdapter)?.resetToFullList()
                // Posted: showDropDown() called synchronously from inside a focus-change
                // callback (itself often triggered mid-touch-event, before the click that
                // caused it finishes dispatching) can be dropped by the popup's own
                // positioning logic. Posting runs it once the current dispatch settles.
                field.post { if (field.hasFocus()) field.showDropDown() }
            }

            // Native touch handling already requests focus on tap, before onClick fires —
            // so the actual blanking is driven from here, once, rather than split across
            // both callbacks (which double-fired: focus-gained, then the click on top of it).
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    blankForSearch()
                } else {
                    // Whatever was typed is discarded — the selection is authoritative.
                    field.setText(selectedStation()?.name.orEmpty(), false)
                }
            }

            field.setOnClickListener {
                if (field.hasFocus()) blankForSearch() else field.requestFocus()
            }

            chevron.setOnClickListener {
                if (field.hasFocus()) {
                    blankForSearch()
                } else {
                    field.requestFocus()
                }
                field.context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }

            field.onItemClickListener = AdapterView.OnItemClickListener { parent, _, position, _ ->
                val picked = (parent.adapter as? StationFilterAdapter)?.getItem(position)
                    ?: return@OnItemClickListener
                onPicked(picked)
                field.hint = picked.name
                field.context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                // Drops focus to the sheet's focusableInTouchMode root, which restores the
                // field's text from the selection just made.
                field.clearFocus()
                clearError()
            }

            field.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    field.context.getSystemService(InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(field.windowToken, 0)
                    field.clearFocus()
                    true
                } else {
                    false
                }
            }
        }

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

            val destAdapter = StationFilterAdapter(requireContext(), destStations, originStopId)
            acDestination.setAdapter(destAdapter)

            val targetStopId = preferredStopId ?: selectedDestStopId ?: pendingDestStopId
            val selected = targetStopId
                ?.takeIf { it != originStopId }
                ?.let { id -> destStations.firstOrNull { it.stopId == id } }
                // No valid target (or it resolved to the origin) — fall back to the first
                // selectable station so the origin is never auto-selected.
                ?: destStations.firstOrNull { it.stopId != originStopId }

            selectedDestStopId = selected?.stopId
            destAdapter.selectedStopId = selected?.stopId
            acDestination.hint = selected?.name
            // The `false` suppresses AutoCompleteTextView's own filter/dropdown, since this
            // is a programmatic preselection rather than the user typing or tapping a match.
            // While the field is focused the user is mid-search, so leave their query alone.
            if (!acDestination.hasFocus()) {
                acDestination.setText(selected?.name.orEmpty(), false)
            }
            if (consumePending && pendingDestStopId == targetStopId) pendingDestStopId = null
        }

        fun updateDestSpinner(
            originStopId: Int,
            preferredDestStopId: Int? = null,
            orderedByLine: Boolean = false,
            preserveSavedDestination: Boolean = true,
        ) {
            val targetDestStopId =
                preferredDestStopId ?: selectedDestStopId ?: pendingDestStopId
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

            acDestination.isEnabled = false
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
                acDestination.isEnabled = true
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

            // Nothing is blocked in the origin list — the destination stays pickable as an
            // origin, since swapping the two ends of a journey is a reasonable edit.
            val originAdapter = StationFilterAdapter(requireContext(), originStations, null)
            acOrigin.setAdapter(originAdapter)

            val targetStopId = pendingOriginStopId ?: selectedOriginStopId ?: current?.originStopId
            val selected = targetStopId?.let { id -> originStations.firstOrNull { it.stopId == id } }
                ?: originStations.firstOrNull()
            if (targetStopId != null) pendingOriginStopId = null

            selectedOriginStopId = selected?.stopId
            originAdapter.selectedStopId = selected?.stopId
            acOrigin.hint = selected?.name
            if (!acOrigin.hasFocus()) {
                acOrigin.setText(selected?.name.orEmpty(), false)
            }

            if (selected != null) {
                updateDestSpinner(
                    selected.stopId,
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
            acOrigin.isEnabled = false
            acDestination.isEnabled = false
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
                acOrigin.isEnabled = true
                acDestination.isEnabled = true
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

            acOrigin.isEnabled = false
            acDestination.isEnabled = false
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
                acOrigin.isEnabled = true
                acDestination.isEnabled = true
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

        bindStationPicker(
            field = acOrigin,
            chevron = view.findViewById(R.id.iv_dropdown_origin),
            selectedStation = { selectedOriginStopId?.let { id -> originStations.firstOrNull { it.stopId == id } } },
            onPicked = { picked ->
                selectedOriginStopId = picked.stopId
                (acOrigin.adapter as? StationFilterAdapter)?.selectedStopId = picked.stopId
                // Changing origin re-derives the destination candidates, which rebuilds
                // the destination adapter and resolves its selection.
                updateDestSpinner(picked.stopId, orderedByLine = selectedLineId != null)
            },
        )

        bindStationPicker(
            field = acDestination,
            chevron = view.findViewById(R.id.iv_dropdown_destination),
            selectedStation = { selectedDestStopId?.let { id -> destStations.firstOrNull { it.stopId == id } } },
            onPicked = { picked ->
                selectedDestStopId = picked.stopId
                (acDestination.adapter as? StationFilterAdapter)?.selectedStopId = picked.stopId
            },
        )

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
            // Neither field has a "selected position" any more now that both are filtering
            // text fields — the selected stop ids are set only when the user taps a
            // suggestion, so typed-but-unconfirmed text leaves the prior selection standing.
            val origin = selectedOriginStopId?.let { id -> originStations.firstOrNull { it.stopId == id } }
            if (origin == null) {
                showError(R.string.err_origin_invalid); return@setOnClickListener
            }
            val destination = selectedDestStopId?.let { id -> destStations.firstOrNull { it.stopId == id } }
            if (destination == null) {
                showError(R.string.err_destination_invalid); return@setOnClickListener
            }

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
