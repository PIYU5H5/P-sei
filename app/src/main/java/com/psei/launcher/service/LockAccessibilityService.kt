package com.psei.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Ultra-lightweight Accessibility Service dedicated strictly to screen locking
 * and notification shade gestures.
 *
 * Performance & Battery Design:
 * - Does NOT inspect view hierarchies or text content (zero CPU overhead, preserves privacy).
 * - Zero CPU / GPU overhead when swiping up into Recents overview or navigating between apps.
 * - Zero native memory leaks.
 * - Preserves biometric / fingerprint unlock on Funtouch OS.
 */
class LockAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: Purely passive service for lockScreen and expandNotifications
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

