package com.psei.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import com.psei.launcher.data.Prefs
import java.lang.ref.WeakReference

/**
 * Ultra-lightweight Accessibility Service dedicated strictly to screen locking,
 * notification shade gestures, and Monk Mode Lockout enforcement.
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
        setLockoutModeActive(Prefs.getInstance(applicationContext).isLockoutActive)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val prefs = Prefs.getInstance(applicationContext)
        if (!prefs.isLockoutActive) return

        if (!isPackageAllowedDuringLockout(pkg)) {
            // Unauthorized app (e.g. Gmail) opened from notification during lockout:
            // Safely return to Home Lockout screen without interfering with System UI or Power Menu
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun isPackageAllowedDuringLockout(pkg: String): Boolean {
        // 1. If keyguard is locked (user is on lockscreen entering PIN or fingerprint), allow systemui
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            return true
        }

        // 2. Launcher itself
        if (pkg == packageName) return true

        // 3. System core / telecom / phone dialer / in-call UI / system UI (power menu, lockscreen, volume)
        val knownAllowed = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.vivo.upslide",
            "com.bbk.launcher2"
        )
        if (knownAllowed.contains(pkg)) return true

        // 4. Default phone dialer
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (tm?.defaultDialerPackage == pkg) return true
        } catch (e: Exception) {}

        // 5. Any dialer activity
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL)
            val resolves = packageManager.queryIntentActivities(dialIntent, 0)
            for (res in resolves) {
                if (res.activityInfo?.packageName == pkg) return true
            }
        } catch (e: Exception) {}

        return false
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

        fun collapseNotificationShade(): Boolean {
            val service = instance?.get() ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Official action on Android 12+ (API 31+)
                service.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                // Do NOT use GLOBAL_ACTION_BACK as it cancels user dialogs (Power Menu, Emergency exit)
                false
            }
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

        fun setLockoutModeActive(active: Boolean) {
            val service = instance?.get() ?: return
            try {
                val info = service.serviceInfo ?: AccessibilityServiceInfo()
                if (active) {
                    info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    info.notificationTimeout = 50
                } else {
                    info.eventTypes = 0
                }
                service.serviceInfo = info
            } catch (e: Exception) {
                // Defensive
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

