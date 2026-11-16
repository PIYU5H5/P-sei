package com.psei.launcher.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Standard DeviceAdminReceiver providing legacy lockNow() capability.
 * Tradeoff: When locked via Device Admin, Android requires PIN/Pattern entry on next unlock
 * and temporarily disables fingerprint authentication on many devices including Funtouch OS.
 */
class LauncherAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, LauncherAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isAdminActive(getComponentName(context)) == true
        }

        fun lockScreen(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null && dpm.isAdminActive(getComponentName(context))) {
                dpm.lockNow()
                return true
            }
            return false
        }

        fun requestAdmin(context: Context) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(context))
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Allows P-Sei to lock the screen upon double-tapping empty space."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}


