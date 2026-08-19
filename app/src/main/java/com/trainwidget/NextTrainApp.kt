package com.nexttrain

import android.app.Application
import com.nexttrain.ui.Theming

/**
 * Applies the saved Appearance choice before the first activity inflates.
 * Registered in the manifest as android:name=".NextTrainApp".
 */
class NextTrainApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Theming.apply(this)
    }
}
