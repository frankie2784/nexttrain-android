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
 * card, every other route gets a row — except in edit mode, where every route
 * (active or not) uses the row layout, since the hero's live departure detail
 * has nothing useful to show while editing and would make that one pill an
 * odd size out. Replaces the single-layout adapter in ConfigActivity.kt — the
 * data class and the click callback are unchanged.
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
    }

    private val items = mutableListOf<DashboardEntry>()

    // The user's actual saved order (what setItems() was handed, and what
    // getPairs() persists), independent of the active-first sort applied to
    // [items] for display outside edit mode. Lets a route that's currently
    // active drop back to its normal position the moment edit mode opens,
    // instead of staying pinned at the top while it's being edited.
    private val baseOrder = mutableListOf<String>()

    var editMode: Boolean = false
        private set

    /** Whether a route renders as the hero card under a given edit-mode state. */
    private fun isHero(pair: OdPair, editMode: Boolean) =
        !editMode && pair.isActiveNow() && pair.notificationsEnabled

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        val wasEditMode = editMode
        editMode = enabled
        val newItems = if (editMode) {
            items.sortedBy { baseOrder.indexOf(it.pair.id) }
        } else {
            items.sortedByDescending { it.pair.isActiveNow() && it.pair.notificationsEnabled }
        }

        // DiffUtil rather than notifyDataSetChanged so the routes that shift
        // position (the active route dropping to its saved spot, and whatever
        // it displaces) get a real move animation instead of just jumping —
        // "slide into place". Every row's presentation (edit controls, window
        // vs. following text) depends on editMode and not just on entry content,
        // so areContentsTheSame is always false: every row still gets rebound,
        // but supportsChangeAnimations is off (see ConfigActivity's RecyclerView
        // setup) so that rebind is an instant in-place update, not a cross-fade —
        // only the actual moves animate.
        //
        // The one route whose hero <-> row view type flips is deliberately
        // *not* matched as "the same item" (areItemsTheSame false) even though
        // its id is unchanged: RecyclerView's move animation only translates a
        // view, it can't also interpolate the hero card's height down to a row's,
        // so sliding it would overlap the rows swapping in underneath. Treating
        // it as a remove-at-old-position + insert-at-new-position instead makes
        // it fade out/in in place — the default add/remove animation — while
        // every other route still slides normally around it.
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldPair = oldItems[oldPos].pair
                val newPair = newItems[newPos].pair
                if (oldPair.id != newPair.id) return false
                return isHero(oldPair, wasEditMode) == isHero(newPair, editMode)
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = false
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int =
        if (isHero(items[position].pair, editMode)) TYPE_ACTIVE else TYPE_ROW

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
        baseOrder.clear()
        baseOrder.addAll(newItems.map { it.pair.id })
        items.clear()
        items.addAll(
            if (editMode) newItems
            else newItems.sortedByDescending { it.pair.isActiveNow() && it.pair.notificationsEnabled }
        )
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

    /** Drag reorder, only ever active in edit mode — where [items] already matches
     *  [baseOrder], so the two are kept in lockstep. */
    fun moveItems(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        items.add(toPosition, items.removeAt(fromPosition))
        baseOrder.add(toPosition, baseOrder.removeAt(fromPosition))
        notifyItemMoved(fromPosition, toPosition)
    }

    fun getPairs(): List<OdPair> = items.map { it.pair }

    fun removeEntry(pairId: String): Int {
        val index = items.indexOfFirst { it.pair.id == pairId }
        if (index >= 0) {
            items.removeAt(index)
            baseOrder.remove(pairId)
            notifyItemRemoved(index)
        }
        return index
    }

    fun insertEntry(index: Int, entry: DashboardEntry) {
        val at = index.coerceIn(0, items.size)
        items.add(at, entry)
        baseOrder.add(at.coerceAtMost(baseOrder.size), entry.pair.id)
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

        // The recycler hands this holder to whichever route scrolls into it, so
        // a bind is only a value *change* — the thing worth rolling — when it
        // is the same route as last time. Otherwise the numbers are swapped in.
        private var boundPairId: String? = null

        // Only ever bound when !editMode — edit mode forces every route,
        // active or not, into RowVH — so there is no edit-mode branch here.
        fun bind(entry: DashboardEntry) {
            updateFollowing(entry)
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
            mins.setValue(Formatting.minutesValueSpanned(ctx, dep), roll)
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

        private fun updateFollowing(entry: DashboardEntry) {
            val rest = entry.upcoming.drop(1).take(2)
            if (rest.isEmpty()) {
                following.visibility = View.GONE
            } else {
                following.visibility = View.VISIBLE
                following.text = Formatting.followingDepartures(itemView.context, use24HourFormat(), rest)
            }
        }
    }

    // ── Inactive row ──────────────────────────────────────────────────────

    private inner class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.tv_dash_label)
        private val activeDot: ImageView = view.findViewById(R.id.iv_dash_active_dot)
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
            // Row layout is used for the active route while editing (see
            // getItemViewType) as well as for genuinely inactive routes, so
            // the dot is the only thing left marking which one is live.
            activeDot.visibility =
                if (entry.pair.isActiveNow() && entry.pair.notificationsEnabled) View.VISIBLE else View.GONE
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

            mins.setValue(Formatting.minutesValueSpanned(ctx, dep), roll)
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
