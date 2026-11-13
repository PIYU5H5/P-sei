package com.psei.launcher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight, zero-dependency SharedPreferences wrapper for launcher configuration.
 * Supports Free tier and P-Sei Pro features.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_FILE_NAME = "minimal_launcher_prefs"

        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"
        const val THEME_SEPIA = "sepia"       // Pro
        const val THEME_FOREST = "forest"     // Pro
        const val THEME_CHARCOAL = "charcoal" // Pro
        const val THEME_CYBER = "cyber"       // Pro

        const val TEXT_SIZE_SMALL = "small"
        const val TEXT_SIZE_MEDIUM = "medium"
        const val TEXT_SIZE_LARGE = "large"

        const val LOCK_METHOD_ACCESSIBILITY = "accessibility"
        const val LOCK_METHOD_DEVICE_ADMIN = "device_admin"

        private const val KEY_IS_PRO_USER = "pref_is_pro_user"

        private const val KEY_THEME = "pref_theme"
        private const val KEY_HOME_SLOTS_COUNT = "pref_home_slots_count"
        private const val KEY_SLOT_PREFIX = "pref_slot_"
        private const val KEY_TEXT_SIZE = "pref_text_size"
        private const val KEY_AUTO_KEYBOARD = "pref_auto_keyboard"
        private const val KEY_AUTO_LAUNCH = "pref_auto_launch"
        private const val KEY_HIDDEN_PACKAGES = "pref_hidden_packages"
        private const val KEY_CUSTOM_NAME_PREFIX = "pref_custom_name_"

        private const val KEY_SHORTCUT_BOTTOM_LEFT = "pref_shortcut_bottom_left"
        private const val KEY_SHORTCUT_BOTTOM_RIGHT = "pref_shortcut_bottom_right"
        private const val KEY_SHORTCUT_SWIPE_LEFT = "pref_shortcut_swipe_left"
        private const val KEY_SHORTCUT_SWIPE_RIGHT = "pref_shortcut_swipe_right"
        private const val KEY_LOCK_METHOD = "pref_lock_method"

        private const val KEY_SHOW_CALENDAR_WIDGET = "pref_show_calendar_widget"
        private const val KEY_SHOW_DUOLINGO_WIDGET = "pref_show_duolingo_widget"
        private const val KEY_DUOLINGO_STREAK = "pref_duolingo_streak"
        private const val KEY_DUOLINGO_LAST_DATE = "pref_duolingo_last_date"

        // Pro Digital Detox keys
        private const val KEY_MINDFUL_DELAY_ENABLED = "pref_mindful_delay_enabled"
        private const val KEY_MINDFUL_DELAY_SECONDS = "pref_mindful_delay_seconds"
        private const val KEY_MINDFUL_PACKAGES = "pref_mindful_packages"
        private const val KEY_BIOMETRIC_LOCK = "pref_biometric_lock"
        private const val KEY_APP_LIMIT_PREFIX = "pref_app_limit_"
        private const val KEY_APP_SNOOZE_PREFIX = "pref_app_snooze_"
        private const val KEY_LOCKOUT_END_TIME = "pref_lockout_end_time"

        const val SPECIAL_SHORTCUT_PHONE = "__DEFAULT_PHONE__"
        const val SPECIAL_SHORTCUT_CAMERA = "__DEFAULT_CAMERA__"

        @Volatile
        private var instance: Prefs? = null

        fun getInstance(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }

    var isProUser: Boolean
        get() = isVipUser || sp.getBoolean(KEY_IS_PRO_USER, false)
        set(value) = sp.edit().putBoolean(KEY_IS_PRO_USER, value).apply()

    var isVipUser: Boolean
        get() = sp.getBoolean("pref_is_vip_user", false)
        set(value) = sp.edit().putBoolean("pref_is_vip_user", value).apply()

    var theme: String
        get() = sp.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    var homeSlotsCount: Int
        get() = sp.getInt(KEY_HOME_SLOTS_COUNT, 6)
        set(value) {
            val maxSlots = if (isProUser) 10 else 6
            sp.edit().putInt(KEY_HOME_SLOTS_COUNT, value.coerceIn(0, maxSlots)).apply()
        }

    fun getHomeSlot(index: Int): String? {
        return sp.getString(KEY_SLOT_PREFIX + index, null)
    }

    fun setHomeSlot(index: Int, componentKey: String?) {
        sp.edit().apply {
            if (componentKey.isNullOrBlank()) {
                remove(KEY_SLOT_PREFIX + index)
            } else {
                putString(KEY_SLOT_PREFIX + index, componentKey)
            }
        }.apply()
    }

    var textSize: String
        get() = sp.getString(KEY_TEXT_SIZE, TEXT_SIZE_MEDIUM) ?: TEXT_SIZE_MEDIUM
        set(value) = sp.edit().putString(KEY_TEXT_SIZE, value).apply()

    var autoOpenKeyboard: Boolean
        get() = sp.getBoolean(KEY_AUTO_KEYBOARD, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_KEYBOARD, value).apply()

    var autoLaunchSingleResult: Boolean
        get() = sp.getBoolean(KEY_AUTO_LAUNCH, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_LAUNCH, value).apply()

    var hiddenPackages: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN_PACKAGES, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN_PACKAGES, value).apply()

    fun setAppHidden(componentKey: String, isHidden: Boolean) {
        val current = hiddenPackages.toMutableSet()
        if (isHidden) {
            current.add(componentKey)
        } else {
            current.remove(componentKey)
        }
        hiddenPackages = current
    }

    fun isAppHidden(componentKey: String): Boolean {
        return hiddenPackages.contains(componentKey)
    }

    fun getCustomAppName(componentKey: String): String? {
        return sp.getString(KEY_CUSTOM_NAME_PREFIX + componentKey, null)
    }

    fun setCustomAppName(componentKey: String, customName: String?) {
        sp.edit().apply {
            if (customName.isNullOrBlank()) {
                remove(KEY_CUSTOM_NAME_PREFIX + componentKey)
            } else {
                putString(KEY_CUSTOM_NAME_PREFIX + componentKey, customName.trim())
            }
        }.apply()
    }

    var shortcutBottomLeft: String
        get() = sp.getString(KEY_SHORTCUT_BOTTOM_LEFT, SPECIAL_SHORTCUT_PHONE) ?: SPECIAL_SHORTCUT_PHONE
        set(value) = sp.edit().putString(KEY_SHORTCUT_BOTTOM_LEFT, value).apply()

    var shortcutBottomRight: String
        get() = sp.getString(KEY_SHORTCUT_BOTTOM_RIGHT, SPECIAL_SHORTCUT_CAMERA) ?: SPECIAL_SHORTCUT_CAMERA
        set(value) = sp.edit().putString(KEY_SHORTCUT_BOTTOM_RIGHT, value).apply()

    var shortcutSwipeLeft: String?
        get() = sp.getString(KEY_SHORTCUT_SWIPE_LEFT, null)
        set(value) = sp.edit().putString(KEY_SHORTCUT_SWIPE_LEFT, value).apply()

    var shortcutSwipeRight: String?
        get() = sp.getString(KEY_SHORTCUT_SWIPE_RIGHT, null)
        set(value) = sp.edit().putString(KEY_SHORTCUT_SWIPE_RIGHT, value).apply()

    var lockMethod: String
        get() = sp.getString(KEY_LOCK_METHOD, LOCK_METHOD_ACCESSIBILITY) ?: LOCK_METHOD_ACCESSIBILITY
        set(value) = sp.edit().putString(KEY_LOCK_METHOD, value).apply()

    var showCalendarWidget: Boolean
        get() = sp.getBoolean(KEY_SHOW_CALENDAR_WIDGET, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_CALENDAR_WIDGET, value).apply()

    var showDuolingoWidget: Boolean
        get() = sp.getBoolean(KEY_SHOW_DUOLINGO_WIDGET, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_DUOLINGO_WIDGET, value).apply()

    var duolingoStreak: Int
        get() = sp.getInt(KEY_DUOLINGO_STREAK, 0)
        set(value) = sp.edit().putInt(KEY_DUOLINGO_STREAK, value.coerceAtLeast(0)).apply()

    var duolingoLastPracticeDate: String?
        get() = sp.getString(KEY_DUOLINGO_LAST_DATE, null)
        set(value) = sp.edit().putString(KEY_DUOLINGO_LAST_DATE, value).apply()

    fun getTodayDateString(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }

    var isDuolingoPracticedToday: Boolean
        get() = duolingoLastPracticeDate == getTodayDateString()
        set(value) {
            duolingoLastPracticeDate = if (value) getTodayDateString() else null
        }

    // =========================================================================
    // PRO FEATURES: MINDFUL DELAY & BIOMETRIC LOCK
    // =========================================================================

    var isMindfulDelayEnabled: Boolean
        get() = sp.getBoolean(KEY_MINDFUL_DELAY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_MINDFUL_DELAY_ENABLED, value).apply()

    var mindfulDelaySeconds: Int
        get() = sp.getInt(KEY_MINDFUL_DELAY_SECONDS, 5)
        set(value) = sp.edit().putInt(KEY_MINDFUL_DELAY_SECONDS, value.coerceIn(3, 15)).apply()

    var mindfulPackages: Set<String>
        get() = sp.getStringSet(KEY_MINDFUL_PACKAGES, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_MINDFUL_PACKAGES, value).apply()

    fun isMindfulApp(packageName: String): Boolean {
        return isProUser && isMindfulDelayEnabled && mindfulPackages.contains(packageName)
    }

    fun toggleMindfulApp(packageName: String): Boolean {
        val current = mindfulPackages.toMutableSet()
        val willBeMindful = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        mindfulPackages = current
        return willBeMindful
    }

    var isBiometricLockEnabled: Boolean
        get() = sp.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) = sp.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    // Screen-Time Limits
    fun getAppScreenTimeLimit(packageName: String): Int {
        return sp.getInt(KEY_APP_LIMIT_PREFIX + packageName, 0)
    }

    fun setAppScreenTimeLimit(packageName: String, limitMinutes: Int) {
        sp.edit().apply {
            if (limitMinutes <= 0) {
                remove(KEY_APP_LIMIT_PREFIX + packageName)
            } else {
                putInt(KEY_APP_LIMIT_PREFIX + packageName, limitMinutes)
            }
        }.apply()
    }

    fun getAllConfiguredLimits(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for ((key, value) in sp.all) {
            if (key.startsWith(KEY_APP_LIMIT_PREFIX) && value is Int && value > 0) {
                val pkg = key.removePrefix(KEY_APP_LIMIT_PREFIX)
                result[pkg] = value
            }
        }
        return result
    }

    fun snoozeAppLimit(packageName: String, minutes: Int = 5) {
        val snoozeUntil = System.currentTimeMillis() + (minutes * 60_000L)
        sp.edit().putLong(KEY_APP_SNOOZE_PREFIX + packageName, snoozeUntil).apply()
    }

    fun isAppSnoozed(packageName: String): Boolean {
        val snoozeUntil = sp.getLong(KEY_APP_SNOOZE_PREFIX + packageName, 0L)
        return System.currentTimeMillis() < snoozeUntil
    }

    // =========================================================================
    // PRO DEEP LOCKOUT (MONK MODE)
    // =========================================================================

    var lockoutEndTime: Long
        get() = sp.getLong(KEY_LOCKOUT_END_TIME, 0L)
        set(value) = sp.edit().putLong(KEY_LOCKOUT_END_TIME, value).apply()

    val isLockoutActive: Boolean
        get() = lockoutEndTime > System.currentTimeMillis()

    fun startLockout(durationMinutes: Int) {
        lockoutEndTime = System.currentTimeMillis() + (durationMinutes * 60_000L)
    }

    fun stopLockout() {
        lockoutEndTime = 0L
    }

    fun getLockoutRemainingMillis(): Long {
        return (lockoutEndTime - System.currentTimeMillis()).coerceAtLeast(0L)
    }
}

