package com.nexttrain.config

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.nexttrain.R
import com.nexttrain.data.Departure
import com.nexttrain.data.OdPair
import com.nexttrain.data.dropDeparted
import com.nexttrain.ui.Formatting
import com.nexttrain.ui.RollingTextView

/**
 * Dashboard list. Two view types: the route whose window is live gets the hero
 * card, every other route gets a row. Replaces the single-layout adapter in
 * ConfigActivity.kt — the data class and the click callback are unchanged.
 *
 * Edit mode is an orthogonal per-adapter flag, not a third view type: each
 * view holder swaps its own metrics/chevron block for a delete+reorder block.
 */
data class DashboardEntry(
    val pair: OdPair,
    val departures: List<Departure>,
    val loading: Boolean,
    val unreachable: Boolean = false,
) {
    /**
     * The up-to-6 departures fetched for this route (see DeparturesRepository's
     * maxResults), with anything more than a few seconds past due dropped (see
     * Departure.hasDeparted). While online this rarely removes anything — the
     * server response is already this route's true next trains — but while
     * offline the list is frozen from the last successful fetch, so this is what
     * makes the hero/"after that" cards cycle forward through the remaining
     * fetched trains as each one's departure time passes, instead of pinning a
     * train that already left.
     */
    val upcoming: List<Departure> get() = departures.dropDeparted()
}

class DashboardAdapter(
    private val onCardClick: (OdPair) -> Unit,
    private val onDeleteClick: (OdPair) -> Unit,
    private val onDragHandleTouch: (RecyclerView.ViewHolder) -> Unit,
    // PERF: read once per bind (not once per Formatting call, ~3 per row) —
    // avoids each row constructing its own WidgetPrefs/SharedPreferences/Gson
    // just to read one boolean. Supplied as a lambda (rather than a snapshot
    // Boolean) so a Settings change is picked up on the next bind without
    // needing to reconstruct the adapter.
    private val use24HourFormat: () -> Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ACTIVE = 0
        private const val TYPE_ROW = 1
        private const val PAYLOAD_EDIT_MODE = "edit_mode"
    }

    private val items = mutableListOf<DashboardEntry>()
    var editMode: Boolean = false
        private set

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT_MODE)
    }

    override fun getItemViewType(position: Int): Int {
        val pair = items[position].pair
        return if (pair.isActiveNow() && pair.notificationsEnabled) TYPE_ACTIVE else TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ACTIVE) {
            ActiveVH(inflater.inflate(R.layout.item_dashboard_active, parent, false))
        } else {
            RowVH(inflater.inflate(R.layout.item_dashboard_route, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = items[position]
        holder.itemView.setOnClickListener { onCardClick(entry.pair) }
        when (holder) {
            is ActiveVH -> holder.bind(entry)
            is RowVH -> holder.bind(entry)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_EDIT_MODE)) {
            when (holder) {
                is ActiveVH -> holder.bindEditMode(items[position])
                is RowVH -> holder.bindEditMode(items[position])
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = items.size

    /** Currently displayed entry for a route, if any — lets callers preserve
     *  already-fresh in-memory data instead of reverting to disk cache on refresh. */
    fun getEntry(pairId: String): DashboardEntry? = items.firstOrNull { it.pair.id == pairId }

    /**
     * Forces every row to rebind against the current wall clock without
     * touching [items] or fetching anything. [DashboardEntry.upcoming] is a
     * computed property (not part of the data class's equality), so a route
     * whose hero train just crossed [Departure.hasDeparted] wouldn't
     * otherwise get picked up until [applyEntries] next receives genuinely
     * different data from a real fetch — up to 60s away. Callers should run
     * this every few seconds while the screen is visible so a departed train
     * doesn't linger as "Now" for anywhere near that long. Cheap: RollingTextView
     * no-ops when a rebind sets the same text it already has.
     */
    fun refreshDisplay() {
        if (editMode) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun setItems(newItems: List<DashboardEntry>) {
        items.clear()
        items.addAll(newItems.sortedByDescending { it.pair.isActiveNow() && it.pair.notificationsEnabled })
        notifyDataSetChanged()
    }

    /**
     * Apply every pair's fetch result from one refresh tick in a single pass:
     * update all matching entries, sort once, then diff old vs new so only
     * rows that actually changed are rebound — a per-pair updateEntry() that
     * each re-sorted and notifyDataSetChanged()'d the whole list would flash
     * every row (lost ripple states, visible flicker) N times per tick for N
     * routes, even though most ticks change at most one or two of them.
     */
    fun applyEntries(updates: Map<String, DashboardEntry>) {
        if (editMode || updates.isEmpty()) return
        val newItems = items
            .map { entry -> updates[entry.pair.id] ?: entry }
            .sortedByDescending { it.pair.isActiveNow() && it.pair.notificationsEnabled }

        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].pair.id == newItems[newPos].pair.id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun moveItems(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        items.add(toPosition, items.removeAt(fromPosition))
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getPairs(): List<OdPair> = items.map { it.pair }

    fun removeEntry(pairId: String): Int {
        val index = items.indexOfFirst { it.pair.id == pairId }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
        return index
    }

    fun insertEntry(index: Int, entry: DashboardEntry) {
        val at = index.coerceIn(0, items.size)
        items.add(at, entry)
        notifyItemInserted(at)
    }

    /** Toggles the small alarm icon that marks the active-times window text in edit mode. */
    private fun setAlarmIcon(textView: TextView, show: Boolean) {
        val icon = if (show) ContextCompat.getDrawable(textView.context, R.drawable.ic_alarm) else null
        textView.compoundDrawablePadding =
            textView.resources.getDimensionPixelSize(R.dimen.nt_alarm_icon_padding)
        textView.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    }

    /** Wires the delete/reorder icons and toggles their visibility against [editMode]. */
    private fun bindEditControls(
        editControls: LinearLayout,
        counterpart: View,
        btnDelete: ImageButton,
        btnReorder: ImageButton,
        entry: DashboardEntry,
        holder: RecyclerView.ViewHolder,
    ) {
        editControls.visibility = if (editMode) View.VISIBLE else View.GONE
        counterpart.visibility = if (editMode) View.GONE else View.VISIBLE
        btnDelete.setOnClickListener { onDeleteClick(entry.pair) }
        btnReorder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) onDragHandleTouch(holder)
            false
        }
    }

    // ── Hero card ─────────────────────────────────────────────────────────

    private inner class ActiveVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.tv_dash_label)
        private val route: TextView = view.findViewById(R.id.tv_dash_route)
        private val mins: RollingTextView = view.findViewById(R.id.tv_dash_mins)
        private val minsUnit: TextView = view.findViewById(R.id.tv_dash_mins_unit)
        private val time: RollingTextView = view.findViewById(R.id.tv_dash_time)
        private val arrival: TextView = view.findViewById(R.id.tv_dash_arrival)
        private val status: TextView = view.findViewById(R.id.tv_dash_status)
        private val statusIcon: ImageView = view.findViewById(R.id.iv_status)
        private val statusChip: LinearLayout = view.findViewById(R.id.chip_status)
        private val following: TextView = view.findViewById(R.id.tv_dash_following)
        private val chevron: ImageView = view.findViewById(R.id.iv_dash_chevron)
        private val editControls: LinearLayout = view.findViewById(R.id.edit_controls)
        private val btnDelete: ImageButton = view.findViewById(R.id.btn_row_delete)
        private val btnReorder: ImageButton = view.findViewById(R.id.btn_row_reorder)

        // The recycler hands this holder to whichever route scrolls into it, so
        // a bind is only a value *change* — the thing worth rolling — when it
        // is the same route as last time. Otherwise the numbers are swapped in.
        private var boundPairId: String? = null

        fun bind(entry: DashboardEntry) {
            bindEditMode(entry)
            val ctx = itemView.context
            val roll = boundPairId == entry.pair.id
            boundPairId = entry.pair.id
            label.text = entry.pair.label
            route.text = "${entry.pair.originName} ➝ ${entry.pair.destinationName}"

            val dep = entry.upcoming.firstOrNull()
            if (dep == null) {
                mins.setValue("—", roll)
                minsUnit.text = ""
                val message = when {
                    entry.loading -> ctx.getString(R.string.updating)
                    entry.unreachable -> ctx.getString(R.string.connection_error)
                    else -> ctx.getString(R.string.no_trains)
                }
                time.setValue(message, roll)
                arrival.text = ""
                statusChip.visibility = View.GONE
                return
            }

            val use24Hour = use24HourFormat()
            mins.setValue(Formatting.minutesValue(dep), roll)
            minsUnit.text = Formatting.minutesUnit(ctx, dep)
            time.setValue(Formatting.departureTimeWithSchedule(ctx, use24Hour, dep), roll)
            arrival.text = dep.destinationDisplayTime?.let {
                val arriveTime = Formatting.formatTime(use24Hour, it)
                "arrives $arriveTime"
            } ?: ""

            if (entry.unreachable) {
                // The server can't be reached right now — even a timetable-only
                // ("Scheduled") route should read as offline, not as a confirmed
                // status, since we don't actually know it's still on schedule.
                statusChip.visibility = View.VISIBLE
                statusChip.setBackgroundResource(R.drawable.nt_chip_neutral)
                statusIcon.setImageResource(R.drawable.ic_schedule)
                val tint = ContextCompat.getColor(ctx, R.color.nt_sub)
                statusIcon.setColorFilter(tint)
                status.setTextColor(tint)
                status.text = ctx.getString(R.string.status_not_confirmed)
            } else if (!entry.pair.region.hasRealtime) {
                // No GTFS-RT feed for this region, so there's no delay data to report —
                // showing "On time" here would be a fabricated claim, not an observation.
                // "Scheduled" says plainly this is the timetable, not a live verdict.
                statusChip.visibility = View.VISIBLE
                statusChip.setBackgroundResource(R.drawable.nt_chip_neutral)
                statusIcon.setImageResource(R.drawable.ic_schedule)
                val neutral = ContextCompat.getColor(ctx, R.color.nt_sub)
                statusIcon.setColorFilter(neutral)
                status.setTextColor(neutral)
                status.text = ctx.getString(R.string.status_scheduled)
            } else {
                val late = dep.isDelayed
                statusChip.visibility = View.VISIBLE
                statusChip.setBackgroundResource(if (late) R.drawable.nt_chip_late else R.drawable.nt_chip_ok)
                statusIcon.setImageResource(if (late) R.drawable.ic_error else R.drawable.ic_check_circle)
                val tint = ContextCompat.getColor(ctx, if (late) R.color.nt_late else R.color.nt_primary)
                statusIcon.setColorFilter(tint)
                status.setTextColor(tint)
                status.text = Formatting.status(dep)
            }
        }

        /** Shows the route's active-times window in edit mode, the upcoming departures otherwise. */
        private fun updateFollowing(entry: DashboardEntry) {
            setAlarmIcon(following, editMode)
            if (editMode) {
                following.visibility = View.VISIBLE
                following.text = Formatting.window(entry.pair)
                return
            }
            val rest = entry.upcoming.drop(1).take(2)
            if (rest.isEmpty()) {
                following.visibility = View.GONE
            } else {
                following.visibility = View.VISIBLE
                following.text = Formatting.followingDepartures(itemView.context, use24HourFormat(), rest)
            }
        }

        fun bindEditMode(entry: DashboardEntry) {
            bindEditControls(editControls, chevron, btnDelete, btnReorder, entry, this)
            updateFollowing(entry)
        }
    }

    // ── Inactive row ──────────────────────────────────────────────────────

    private inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.tv_dash_label)
        private val route: TextView = view.findViewById(R.id.tv_dash_route)
        private val window: TextView = view.findViewById(R.id.tv_dash_window)
        private val mins: RollingTextView = view.findViewById(R.id.tv_dash_mins)
        private val minsUnit: TextView = view.findViewById(R.id.tv_dash_mins_unit)
        private val time: RollingTextView = view.findViewById(R.id.tv_dash_time)
        private val status: TextView = view.findViewById(R.id.tv_dash_status)
        private val metrics: LinearLayout = view.findViewById(R.id.dash_metrics)
        private val editControls: LinearLayout = view.findViewById(R.id.edit_controls)
        private val btnDelete: ImageButton = view.findViewById(R.id.btn_row_delete)
        private val btnReorder: ImageButton = view.findViewById(R.id.btn_row_reorder)

        /** See ActiveVH.boundPairId. */
        private var boundPairId: String? = null

        fun bind(entry: DashboardEntry) {
            bindEditMode(entry)
            val ctx = itemView.context
            val roll = boundPairId == entry.pair.id
            boundPairId = entry.pair.id
            label.text = entry.pair.label
            route.text = "${entry.pair.originName} ➝ ${entry.pair.destinationName}"

            val dep = entry.upcoming.firstOrNull()
            if (dep == null) {
                mins.setValue("—", roll)
                minsUnit.text = ""
                val message = when {
                    entry.loading -> ctx.getString(R.string.updating)
                    entry.unreachable -> ctx.getString(R.string.connection_error)
                    else -> ctx.getString(R.string.no_trains)
                }
                time.setValue(message, roll)
                status.visibility = View.GONE
                return
            }

            mins.setValue(Formatting.minutesValue(dep), roll)
            minsUnit.text = Formatting.minutesUnit(ctx, dep)
            time.setValue(Formatting.departureTimeWithSchedule(ctx, use24HourFormat(), dep), roll)
            if (entry.unreachable) {
                // See ActiveVH.bind — offline overrides "Scheduled" too, since we
                // can't confirm even a timetable-only route is still on schedule.
                status.visibility = View.VISIBLE
                status.text = ctx.getString(R.string.status_not_confirmed)
                status.setTextColor(ContextCompat.getColor(ctx, R.color.nt_sub))
            } else if (!entry.pair.region.hasRealtime) {
                status.visibility = View.VISIBLE
                status.text = ctx.getString(R.string.status_scheduled)
                status.setTextColor(ContextCompat.getColor(ctx, R.color.nt_sub))
            } else {
                status.visibility = View.VISIBLE
                status.text = Formatting.status(dep)
                status.setTextColor(
                    ContextCompat.getColor(ctx, if (dep.isDelayed) R.color.nt_late else R.color.nt_primary)
                )
            }
        }

        /** Shows the route's active-times window in edit mode, the upcoming departures otherwise. */
        private fun updateWindow(entry: DashboardEntry) {
            setAlarmIcon(window, editMode)
            if (editMode) {
                window.visibility = View.VISIBLE
                window.text = Formatting.window(entry.pair)
                return
            }
            val following = entry.upcoming.drop(1).take(2)
            if (following.isNotEmpty()) {
                window.visibility = View.VISIBLE
                window.text = Formatting.followingDepartures(itemView.context, use24HourFormat(), following)
            } else {
                window.visibility = View.GONE
            }
        }

        fun bindEditMode(entry: DashboardEntry) {
            bindEditControls(editControls, metrics, btnDelete, btnReorder, entry, this)
            updateWindow(entry)
        }
    }
}
