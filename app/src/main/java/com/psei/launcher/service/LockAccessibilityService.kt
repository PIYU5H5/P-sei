package com.psei.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.psei.launcher.data.Prefs
import com.psei.launcher.data.UsageHelper
import java.lang.ref.WeakReference

/**
 * Ultra-lightweight Accessibility Service dedicated strictly to screen locking,
 * notification shade gestures, and optional instant app-switch notification for app limits.
 *
 * Performance & Battery Design:
 * - Does NOT inspect view hierarchies or text content (zero CPU overhead, preserves privacy).
 * - Only listens to TYPE_WINDOW_STATE_CHANGED when app limits are actively configured.
 * - Optional: app timer monitoring works fully without this service to preserve Google Pay / UPI compatibility.
 */
class LockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        syncEventSubscription(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (Prefs.getInstance(applicationContext).getAllConfiguredLimits().isEmpty()) return
        AppLimitMonitorService.onAccessibilityPackageEntered(pkg)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance?.get() == this) {
            instance = null
        }
    }

    companion object {
        private var instance: WeakReference<LockAccessibilityService>? = null

        val isServiceRunning: Boolean
            get() = instance?.get() != null

        fun goHome(): Boolean {
            val service = instance?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun syncEventSubscription(context: Context) {
            val service = instance?.get() ?: return
            try {
                val hasLimits = Prefs.getInstance(context).getAllConfiguredLimits().isNotEmpty() &&
                    UsageHelper.hasUsagePermission(context)
                val info = service.serviceInfo ?: AccessibilityServiceInfo()
                info.eventTypes = if (hasLimits) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0
                info.notificationTimeout = if (hasLimits) 50 else 100
                service.serviceInfo = info
            } catch (e: Exception) {
                // Defensive
            }
        }

        fun lockScreen(): Boolean {
            val service = instance?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else {
                false
            }
        }

        fun expandNotifications(): Boolean {
            val service = instance?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }

        /**
         * Programmatically disables this Accessibility Service so Google Pay and Indian UPI
         * banking apps run immediately without security blocks.
         */
        fun disableService(): Boolean {
            val service = instance?.get() ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    service.disableSelf()
                }
                instance = null
                true
            } catch (e: Exception) {
                false
            }
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun openAccessibilitySettings(context: Context) = openSettings(context)
    }
}

