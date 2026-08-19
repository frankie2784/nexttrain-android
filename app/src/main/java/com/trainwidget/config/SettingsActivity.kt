package com.nexttrain.config

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.appcompat.widget.SwitchCompat
import com.nexttrain.R
import com.nexttrain.api.NextTrainApiClient
import com.nexttrain.data.Region
import com.nexttrain.prefs.WidgetPrefs
import com.nexttrain.ui.Theming
import com.nexttrain.widget.CommuteNotificationManager
import com.nexttrain.widget.sendWidgetRefreshBroadcast
import kotlinx.coroutines.launch

/**
 * Settings screen for notification mode, appearance, time format, and default region.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: WidgetPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = WidgetPrefs(this)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        setupNotificationSection()
        setupAppearanceSection()
        setupTimeFormatSection()
        setupRegionSection()
        setupSupportSection()
    }

    private fun setupNotificationSection() {
        val switch = findViewById<SwitchCompat>(R.id.switch_notification_mode)
        switch.isChecked = prefs.notificationModeEnabled
        switch.setOnCheckedChangeListener { _, isChecked ->
            prefs.notificationModeEnabled = isChecked
            if (!isChecked) CommuteNotificationManager.clear(this)
        }
    }

    private fun setupAppearanceSection() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.group_appearance)
        group.check(
            when (Theming.get(this)) {
                Theming.LIGHT -> R.id.btn_theme_light
                Theming.DARK -> R.id.btn_theme_dark
                else -> R.id.btn_theme_system
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            Theming.set(
                this,
                when (checkedId) {
                    R.id.btn_theme_light -> Theming.LIGHT
                    R.id.btn_theme_dark -> Theming.DARK
                    else -> Theming.SYSTEM
                }
            )
        }
    }

    private fun setupTimeFormatSection() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.group_time_format)
        group.check(
            if (prefs.use24HourFormat) R.id.btn_time_format_24 else R.id.btn_time_format_12
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.use24HourFormat = checkedId == R.id.btn_time_format_24
        }
    }

    /**
     * Only offers regions the server actually has a blueprint for (see
     * NextTrainApiClient.getServedRegions) — the app used to list every
     * Region unconditionally, so picking one the Pi's NEXTTRAIN_REGIONS
     * config doesn't include 404'd on every request and surfaced as an
     * unexplained "Connection error".
     */
    private fun setupRegionSection() {
        val spinner = findViewById<Spinner>(R.id.spinner_default_region)
        var regions = Region.values().filter { it in prefs.getServedRegions() }
        var suppressCallback = false

        fun rebuildSpinner() {
            suppressCallback = true
            spinner.adapter = ArrayAdapter(
                this, R.layout.spinner_item_contrast, regions.map { it.displayName }
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item_contrast) }
            spinner.setSelection(regions.indexOf(prefs.selectedRegion).coerceAtLeast(0))
            suppressCallback = false
        }

        findViewById<ImageView>(R.id.iv_dropdown_default_region).setOnClickListener {
            spinner.performClick()
        }

        rebuildSpinner()

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: android.view.View?, position: Int, id: Long) {
                if (suppressCallback) return
                regions.getOrNull(position)?.let {
                    if (it != prefs.selectedRegion) {
                        prefs.selectedRegion = it
                        sendWidgetRefreshBroadcast(this@SettingsActivity)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // Refresh against the live server and rebuild only if the served set has
        // actually changed, so an open/mid-interaction spinner isn't disturbed
        // for no reason on the common case where nothing changed server-side.
        lifecycleScope.launch {
            val served = NextTrainApiClient().getServedRegions(prefs.serverUrl) ?: return@launch
            prefs.setServedRegions(served)
            val refreshed = Region.values().filter { it in served }
            if (refreshed != regions) {
                regions = refreshed
                rebuildSpinner()
            }
        }
    }

    private fun setupSupportSection() {
        findViewById<TextView>(R.id.tv_support_email).setOnClickListener {
            val email = getString(R.string.support_email_address)
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            }
            startActivity(intent)
        }

        findViewById<TextView>(R.id.tv_support_github).setOnClickListener {
            val url = getString(R.string.support_github_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
