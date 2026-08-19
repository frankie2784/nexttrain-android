package com.nexttrain.ui

import androidx.appcompat.app.AppCompatDelegate
import android.content.Context
import android.content.SharedPreferences

/**
 * Appearance preference for the Light / Dark / System control in Settings.
 *
 * Call [apply] from Application.onCreate() so the choice survives cold start,
 * and again whenever the user changes it.
 */
object Theming {

    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    private const val PREFS = "next_train_prefs"
    private const val KEY = "appearance"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): String = prefs(context).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, value: String) {
        prefs(context).edit().putString(KEY, value).apply()
        apply(value)
    }

    fun apply(context: Context) = apply(get(context))

    fun apply(value: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
