package com.psei.launcher

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.psei.launcher.billing.BillingManager
import com.psei.launcher.data.Prefs

/**
 * Application entry point.
 * Initializes default night mode early before activity view inflation.
 * Starts BillingManager to auto-sync Google Play subscriptions.
 */
class MinimalLauncherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applyThemeSetting()
        BillingManager.getInstance(this).startConnection()
    }

    fun applyThemeSetting() {
        val prefs = Prefs.getInstance(this)
        when (prefs.theme) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            Prefs.THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) // Dark by default
        }
    }
}


