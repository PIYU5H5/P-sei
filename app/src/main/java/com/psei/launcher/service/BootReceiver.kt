package com.psei.launcher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Ensures app time-limit monitoring resumes automatically after device reboot or app update
 * when the user has configured daily app limits.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppLimitMonitorService.syncMonitoringState(context)
        }
    }
}
