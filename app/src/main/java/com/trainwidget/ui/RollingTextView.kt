package com.nexttrain.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * A TextView that, when its value changes, rolls the old text out of the top
 * (or bottom) of its own bounds while the new text rolls in behind it — an
 * odometer / split-flap flip rather than an instant swap.
 *
 * It is a real [AppCompatTextView], not a container of them, which is what
 * makes it behave: baseline alignment against neighbouring labels, styles,
 * spans, ellipsizing and accessibility all work exactly as on a plain
 * TextView, and a roll costs no view inflation and no layout pass.
 *
 * Two refinements over a naive whole-string slide:
 *
 *  - Only the characters that actually changed roll. "08:16" → "08:21" leaves
 *    "08:" planted and rolls "16" → "21"; "departs 8:04pm" doesn't drag its
 *    prefix along for the ride. This is done by clipping to the changed run's
 *    x-range and drawing the *full* layout inside it, so kerning and spans
 *    (the struck-through scheduled time) survive the animation untouched.
 *  - Direction follows the value. A falling countdown rolls down, a rising
 *    clock time rolls up, so the motion reads as the number moving rather
 *    than as an arbitrary transition.
 *
 * The first value assigned is never animated, so a freshly inflated screen
 * doesn't roll in from its design-time placeholder. Use [setTextImmediate]
 * for the same reason when rebinding a recycled view to different data.
 */
class RollingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    // `false` for the whole of the superclass constructor (JVM default), which
    // is what keeps the android:text applied during inflation off the animated
    // path — at that point none of the state below exists yet.
    private var ready = false
    private var hasValue = false

    private var outgoingLayout: Layout? = null
    private var outgoingWidth = 0f
    private var progress = 1f
    private var rollUp = true

    /** Character range of the changed run; [partial] says whether to use it. */
    private var changedStart = 0
    private var changedEnd = 0
    private var partial = false

    private var animator: ValueAnimator? = null

    init {
        ready = true
    }

    override fun setText(text: CharSequence?, type: BufferType) {
        val previous = if (ready) getText() else null
        super.setText(text, type)
        if (!ready) return

        val old = previous?.toString().orEmpty()
        val new = text?.toString().orEmpty()

        // Same characters: a span-only change (a delay appearing on the
        // scheduled time) still needs to show, but has nothing to roll.
        if (!hasValue || old.isEmpty() || new.isEmpty() || old == new || !isLaidOut) {
            hasValue = true
            finishRoll()
            return
        }
        startRoll(previous!!, old, new)
    }

    /**
     * Sets the value, rolling only when [animate] is true. Pass false when the
     * view is being rebound to a different subject (a recycled row taking on
     * another route): a roll there would claim a change that never happened.
     */
    fun setValue(value: CharSequence?, animate: Boolean = true) {
        if (!animate) {
            finishRoll()
            hasValue = false
        }
        setText(value, BufferType.NORMAL)
    }

    // ── Roll setup ────────────────────────────────────────────────────────

    private fun startRoll(previous: CharSequence, old: String, new: String) {
        val available = max(width - compoundPaddingLeft - compoundPaddingRight, 0)
        val desired = ceil(Layout.getDesiredWidth(previous, paint)).toInt()
        val outgoing = StaticLayout.Builder
            .obtain(previous, 0, previous.length, paint, max(available, desired))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .build()

        outgoingLayout = outgoing
        outgoingWidth = desired.toFloat()
        rollUp = risesInValue(old, new)
        partial = resolveChangedRun(old, new, outgoing)

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ROLL_DURATION_MS
            interpolator = ROLL_INTERPOLATOR
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishRoll()
            })
            start()
        }
        // Width is held at max(old, new) for the duration (see onMeasure), so
        // ask for that now rather than letting the new text's width land a
        // frame early and shove the neighbouring "min" label sideways.
        requestLayout()
        invalidate()
    }

    /**
     * Narrows the roll to the run of characters that differ, trimming the
     * common prefix and suffix. Returns false — roll the whole string — when
     * the two values aren't directly comparable character-for-character
     * (different lengths, or wrapped onto more than one line), because then
     * the old and new glyph positions no longer line up and a clipped window
     * would slice through unrelated characters.
     */
    private fun resolveChangedRun(old: String, new: String, outgoing: Layout): Boolean {
        if (old.length != new.length) return false
        if (outgoing.lineCount != 1) return false

        var start = 0
        while (start < old.length && old[start] == new[start]) start++
        var end = old.length
        while (end > start && old[end - 1] == new[end - 1]) end--
        if (start == 0 && end == old.length) return false

        changedStart = start
        changedEnd = end
        return true
    }

    /** Leading integer of each value, so "7 min" → "6 min" reads as a fall. */
    private fun risesInValue(old: String, new: String): Boolean {
        val before = LEADING_NUMBER.find(old)?.value?.toLongOrNull() ?: return true
        val after = LEADING_NUMBER.find(new)?.value?.toLongOrNull() ?: return true
        return after >= before
    }

    private fun finishRoll() {
        animator?.let {
            animator = null
            it.cancel()
        }
        if (outgoingLayout != null) {
            outgoingLayout = null
            requestLayout()
        }
        progress = 1f
        partial = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        finishRoll()
        super.onDetachedFromWindow()
    }

    // ── Measure & draw ────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (outgoingLayout == null) return
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return
        // Hold the wider of the two values while both are on screen; shrinking
        // to "9" the instant the "10" starts leaving would jerk everything
        // laid out after it.
        val needed = ceil(outgoingWidth).toInt() + compoundPaddingLeft + compoundPaddingRight
        if (needed > measuredWidth) {
            setMeasuredDimension(resolveSize(needed, widthMeasureSpec), measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val outgoing = outgoingLayout
        if (outgoing == null || progress >= 1f) {
            super.onDraw(canvas)
            return
        }

        // Pure translation, no crossfade: an odometer digit never fades, it
        // physically leaves. Fading would hold both glyphs near-opaque over
        // the same spot mid-roll and read as a double exposure.
        val travel = max(height - paddingTop - paddingBottom, lineHeight).toFloat()
        val direction = if (rollUp) 1f else -1f
        val incomingY = direction * travel * (1f - progress)
        val outgoingY = -direction * travel * progress

        // Resolved here rather than at setText time because the layout for the
        // new value only exists once the view has been measured.
        val window = if (partial) changedRunBounds(outgoing) else null
        val windowLeft = window?.first ?: 0f
        val windowRight = window?.second ?: width.toFloat()

        if (window != null) {
            // Unchanged characters stay planted, drawn either side of the
            // rolling window and never clipped vertically.
            drawClipped(canvas, 0f, windowLeft, 0f, false, outgoing)
            drawClipped(canvas, windowRight, width.toFloat(), 0f, false, outgoing)
        }
        drawClipped(canvas, windowLeft, windowRight, incomingY, false, outgoing)
        drawClipped(canvas, windowLeft, windowRight, outgoingY, true, outgoing)
    }

    /**
     * Horizontal extent of the rolling window: the union of the changed run's
     * extents in the old and new layouts. The numerals are tabular (tnum) so
     * these normally coincide, but a proportional glyph creeping in must
     * widen the window rather than be sliced down the middle. Null if the new
     * layout can no longer support the run (relaid out to more than one line).
     */
    private fun changedRunBounds(outgoing: Layout): Pair<Float, Float>? {
        val current = layout ?: return null
        if (current.lineCount != 1 || changedEnd > current.text.length) return null
        val left = minOf(current.getPrimaryHorizontal(changedStart), outgoing.getPrimaryHorizontal(changedStart))
        val right = maxOf(current.getPrimaryHorizontal(changedEnd), outgoing.getPrimaryHorizontal(changedEnd))
        val from = floor(left) + compoundPaddingLeft
        val to = ceil(right) + compoundPaddingLeft
        return if (to > from) from to to else null
    }

    /**
     * Draws one text pass inside a horizontal slice, offset vertically and
     * clipped to the view's own content box so anything on its way out
     * disappears at the edge of the "window" instead of overlapping whatever
     * sits above or below.
     *
     * PERF: up to four passes per frame while rolling, each re-drawing a
     * single short line of text — cheap at these sizes, and the alternative
     * (a bitmap cache per value) costs an allocation per tick.
     */
    private fun drawClipped(
        canvas: Canvas,
        left: Float,
        right: Float,
        offsetY: Float,
        isOutgoing: Boolean,
        outgoing: Layout,
    ) {
        if (right <= left) return
        val save = canvas.save()
        if (offsetY == 0f) {
            canvas.clipRect(left, 0f, right, height.toFloat())
        } else {
            canvas.clipRect(left, paddingTop.toFloat(), right, (height - paddingBottom).toFloat())
        }
        canvas.translate(0f, offsetY)
        if (isOutgoing) {
            canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
            paint.color = currentTextColor
            outgoing.draw(canvas)
        } else {
            super.onDraw(canvas)
        }
        canvas.restoreToCount(save)
    }

    companion object {
        private const val ROLL_DURATION_MS = 360L

        /** Weighted ease-out: leaves quickly, settles softly, like a drum
         *  coming to rest against its detent. */
        private val ROLL_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        private val LEADING_NUMBER = Regex("\\d+")
    }
}
