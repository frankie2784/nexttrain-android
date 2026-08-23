package com.nexttrain.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.nexttrain.R
import com.nexttrain.data.Departure
import com.nexttrain.data.OdPair
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Forces normal (non-bold) weight regardless of the base TextView's style, since
 * [StyleSpan] only ORs style bits onto the inherited typeface and can't un-bold
 * text inside a view whose style already sets `textStyle="bold"`.
 */
private class NormalWeightSpan : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)
    private fun apply(tp: TextPaint) {
        tp.isFakeBoldText = false
        tp.typeface = Typeface.create(tp.typeface, Typeface.NORMAL)
    }
}

/**
 * One place for the strings the redesign shows in more than one screen, so the
 * dashboard, the departures screen, the widget and the notification cannot drift.
 */
object Formatting {

    private val fmt24 = DateTimeFormatter.ofPattern("HH:mm")
    private val fmt12 = DateTimeFormatter.ofPattern("h:mm a")
    private val fmt12Compact = DateTimeFormatter.ofPattern("h:mm")

    /**
     * Convert time string "HH:mm" to 12 or 24-hour format based on [use24Hour].
     *
     * PERF: takes the flag directly rather than a Context so callers that
     * format several times in one render pass (a departures list, a widget
     * update) read WidgetPrefs.use24HourFormat once and reuse it, instead of
     * each call constructing its own WidgetPrefs/SharedPreferences/Gson.
     */
    fun formatTime(use24Hour: Boolean, timeStr: String): String {
        return try {
            if (use24Hour) {
                timeStr
            } else {
                val time = LocalTime.parse(timeStr, fmt24)
                time.format(fmt12)
            }
        } catch (e: Exception) {
            timeStr
        }
    }

    /**
     * Same as [formatTime] but for space-constrained surfaces (the collapsed
     * notification and the widget): "3:45p" instead of "3:45 pm".
     */
    fun formatTimeCompact(use24Hour: Boolean, timeStr: String): String {
        return try {
            if (use24Hour) {
                timeStr
            } else {
                val time = LocalTime.parse(timeStr, fmt24)
                val suffix = if (time.hour < 12) "a" else "p"
                "${time.format(fmt12Compact)}$suffix"
            }
        } catch (e: Exception) {
            timeStr
        }
    }

    /**
     * "8" / "Now" / "1 hr 43" / "2 hr" — the number on its own, for the display-size
     * TextView. Past 59 minutes this leads with "N hr" so the display-size text stays
     * consistent with the plain-minutes case; the trailing "min" (when there are
     * leftover minutes) comes from [minutesUnit], same as the sub-60 case.
     */
    fun minutesValue(dep: Departure): String {
        val total = dep.minutesUntilDeparture
        return when {
            total <= 0 -> "Now"
            total < 60 -> total.toString()
            total % 60 == 0L -> "${total / 60} hr"
            else -> "${total / 60} hr ${total % 60}"
        }
    }

    /**
     * Same as [minutesValue] but with "hr" rendered small and non-bold, for the big
     * display-size TextViews (`NT.Text.Number`/`NT.Text.Display`) whose base style is
     * bold — matches the look of the separate small "min" unit label next to it.
     */
    fun minutesValueSpanned(context: Context, dep: Departure): CharSequence {
        val total = dep.minutesUntilDeparture
        if (total <= 0) return "Now"
        if (total < 60) return total.toString()

        val hours = total / 60
        val mins = total % 60
        val subColor = ContextCompat.getColor(context, R.color.nt_sub)
        val out = SpannableStringBuilder("$hours")
        val hrStart = out.length
        out.append(" hr")
        val hrEnd = out.length
        out.setSpan(NormalWeightSpan(), hrStart, hrEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.5f), hrStart, hrEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(subColor), hrStart, hrEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (mins != 0L) out.append(" ").append(mins.toString())
        return out
    }

    /** "min" — empty when the value reads "Now" or a whole number of hours. */
    fun minutesUnit(context: Context, dep: Departure): String =
        if (dep.minutesUntilDeparture > 0 && dep.minutesUntilDeparture % 60 != 0L) {
            context.getString(R.string.min)
        } else {
            ""
        }

    /** "Now" / "45m" / "1h5m" / "2h" — compact countdown for the notification and widget. */
    fun minutesCompact(dep: Departure): String {
        val total = dep.minutesUntilDeparture
        return when {
            total <= 0 -> "Now"
            total < 60 -> "${total}m"
            total % 60 == 0L -> "${total / 60}h"
            else -> "${total / 60}h${total % 60}m"
        }
    }

    /** "On time" / "6 min late" / "2 min early" */
    fun status(dep: Departure): String = when {
        dep.delayMinutes >= 1 -> "${dep.delayMinutes} min late"
        dep.delayMinutes <= -1 -> "${-dep.delayMinutes} min early"
        else -> "On time"
    }

    /** Badge form used on the "after that" rows: "+5 late" / "on time" */
    fun badge(dep: Departure): String =
        if (dep.delayMinutes >= 1) "+${dep.delayMinutes} late" else "on time"

    /** "Mon–Fri" / "Sat–Sun" / "Every day" / "Mon Wed Fri" */
    fun days(activeDays: Set<Int>): String {
        if (activeDays.size == 7) return "Every day"
        val range = consecutiveRange(activeDays)
        return range?.let { (start, end) -> "$start–$end" } ?: activeDays.sorted().joinToString(" ") {
            DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
    }

    private fun consecutiveRange(activeDays: Set<Int>): Pair<String, String>? {
        if (activeDays.size < 2 || activeDays.size == 7) return null

        val mask = BooleanArray(7)
        activeDays.forEach { mask[it - 1] = true }

        val transitions = (0 until 7).count { mask[it] != mask[(it + 1) % 7] }
        if (transitions != 2) return null

        val startIndex = (0 until 7).indexOfFirst { !mask[it] && mask[(it + 1) % 7] }
            .takeIf { it >= 0 }
            ?.let { (it + 1) % 7 }
            ?: return null
        val endIndex = (startIndex + activeDays.size - 1) % 7

        val startName = DayOfWeek.of(startIndex + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val endName = DayOfWeek.of(endIndex + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return startName to endName
    }

    /** "06:00 – 10:00  ·  Mon–Fri" for the routes list. */
    fun window(pair: OdPair): String =
        "${pair.activeFrom} – ${pair.activeTo}  ·  ${days(pair.activeDays)}"

    /** "active Mon–Fri 15:30–19:30" for inactive dashboard rows. */
    fun windowShort(pair: OdPair): String =
        "active ${days(pair.activeDays)} ${pair.activeFrom}–${pair.activeTo}"

    /** "then 59m 17:48  ·  1h23m 18:08" with each duration emphasized. */
    fun followingDepartures(
        context: Context,
        use24Hour: Boolean,
        departures: List<Departure>,
    ): CharSequence {
        val out = SpannableStringBuilder("then ")
        val emphasisColor = ContextCompat.getColor(context, R.color.nt_text)
        departures.forEachIndexed { index, departure ->
            if (index > 0) out.append("  ·  ")
            val durationStart = out.length
            out.append(minutesCompact(departure))
            val durationEnd = out.length
            out.setSpan(StyleSpan(Typeface.BOLD), durationStart, durationEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(emphasisColor), durationStart, durationEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(" ").append(formatTime(use24Hour, departure.displayTime))
        }
        return out
    }

    /** Expected time plus the struck-through scheduled time when real time has moved, with an optional leading label. */
    fun departureTimeWithSchedule(context: Context, use24Hour: Boolean, dep: Departure, prefix: String = ""): CharSequence {
        val expectedTime = formatTime(use24Hour, dep.expectedTime)
        if (!dep.hasRealtimeTimeChange) return "$prefix$expectedTime"

        val scheduledTime = formatTime(use24Hour, dep.scheduledTime)
        val out = SpannableStringBuilder(prefix).append(expectedTime).append("  ").append(scheduledTime)
        val start = out.length - scheduledTime.length
        out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.nt_muted)),
            start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return out
    }
}
