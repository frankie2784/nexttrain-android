package com.nexttrain.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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

    /** "8" / "Now" — the number on its own, for the display-size TextView. */
    fun minutesValue(dep: Departure): String =
        if (dep.minutesUntilDeparture <= 0) "Now" else dep.minutesUntilDeparture.toString()

    /** "min" — or empty when the value reads "Now" and a unit would be nonsense. */
    fun minutesUnit(context: Context, dep: Departure): String =
        if (dep.minutesUntilDeparture <= 0) "" else context.getString(R.string.min)

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

    /** "then 25 min 17:48  ·  46 min 18:08" with each duration emphasized. */
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
            out.append(minutesValue(departure)).append(" min")
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
