package com.istarvin.cloudstream

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import androidx.test.runner.AndroidJUnitRunner

/** Exercise TV resources and input on ordinary emulators as well as TV system images. */
class TvTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        setInTouchMode(false)
        super.onStart()
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity =
        super.newActivity(cl, className, intent).apply {
            applyOverrideConfiguration(Configuration().apply {
                uiMode = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES
            })
        }
}
