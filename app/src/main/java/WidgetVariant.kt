package com.nexttrain.widget

import com.nexttrain.R

/**
 * Next Train ships light and dark widgets as two separate, user-chosen home
 * screen widgets (picked explicitly from the widget drawer) rather than a
 * single widget that follows the system theme. Each variant supplies its own
 * layout and the color resources needed for colors drawn programmatically
 * (sparkline, delay-label fallback) — everything else is baked into the
 * variant's layout XML via @color references.
 */
enum class WidgetVariant(
    val layoutRes: Int,
    val colorFg: Int,
    val colorFgSubtle: Int,
    val colorFgFaint: Int
) {
    LIGHT(
        layoutRes = R.layout.widget_layout_light,
        colorFg = R.color.nt_widget_light_fg,
        colorFgSubtle = R.color.nt_widget_light_fg_subtle,
        colorFgFaint = R.color.nt_widget_light_fg_faint
    ),
    DARK(
        layoutRes = R.layout.widget_layout_dark,
        colorFg = R.color.nt_widget_dark_fg,
        colorFgSubtle = R.color.nt_widget_dark_fg_subtle,
        colorFgFaint = R.color.nt_widget_dark_fg_faint
    )
}
