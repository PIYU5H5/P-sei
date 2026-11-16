package com.psei.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

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

        // Daily Focus / Intent
        private const val KEY_SHOW_DAILY_INTENT = "pref_show_daily_intent"
        private const val KEY_DAILY_INTENT_TEXT = "pref_daily_intent_text"
        private const val KEY_DAILY_INTENT_COMPLETED = "pref_daily_intent_completed"
        private const val KEY_DAILY_INTENT_DATE = "pref_daily_intent_date"
        private const val KEY_DAILY_FOCUS_ITEMS = "pref_daily_focus_items_json"

        // Daily Screen-Time Glance
        private const val KEY_SHOW_SCREEN_TIME_GLANCE = "pref_show_screen_time_glance"

        // Pro Digital Detox keys
        private const val KEY_MINDFUL_DELAY_ENABLED = "pref_mindful_delay_enabled"
        private const val KEY_MINDFUL_DELAY_SECONDS = "pref_mindful_delay_seconds"
        private const val KEY_MINDFUL_PACKAGES = "pref_mindful_packages"
        private const val KEY_BIOMETRIC_LOCK = "pref_biometric_lock"
        private const val KEY_APP_LIMIT_PREFIX = "pref_app_limit_"
        private const val KEY_APP_SNOOZE_PREFIX = "pref_app_snooze_"

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
        get() = sp.getString(KEY_LOCK_METHOD, LOCK_METHOD_DEVICE_ADMIN) ?: LOCK_METHOD_DEVICE_ADMIN
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
    // DAILY FOCUS INTENT & SCREEN-TIME GLANCE (UP TO 3 ITEMS)
    // =========================================================================

    var showDailyIntent: Boolean
        get() = sp.getBoolean(KEY_SHOW_DAILY_INTENT, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_DAILY_INTENT, value).apply()

    fun getDailyFocusItems(): List<DailyFocusItem> {
        val savedDate = dailyIntentDate
        val today = getTodayDateString()
        val rawJson = sp.getString(KEY_DAILY_FOCUS_ITEMS, null)

        val items = mutableListOf<DailyFocusItem>()
        if (!rawJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(rawJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id", java.util.UUID.randomUUID().toString())
                    val text = obj.optString("text", "")
                    val isCompleted = obj.optBoolean("done", false)
                    if (text.isNotBlank()) {
                        items.add(DailyFocusItem(id = id, text = text, isCompleted = isCompleted))
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        } else {
            // Migration from single legacy dailyIntentText
            val legacyText = sp.getString(KEY_DAILY_INTENT_TEXT, null)
            if (!legacyText.isNullOrBlank()) {
                val legacyDone = sp.getBoolean(KEY_DAILY_INTENT_COMPLETED, false)
                val legacyItem = DailyFocusItem(text = legacyText, isCompleted = legacyDone)
                items.add(legacyItem)
                saveDailyFocusItems(items)
            }
        }

        // Overnight reset check
        if (savedDate != null && savedDate != today) {
            // Carry forward only uncompleted items; if all were finished yesterday, start fresh
            val uncompleted = items.filter { !it.isCompleted }.take(3).toMutableList()
            saveDailyFocusItems(uncompleted)
            dailyIntentDate = today
            return uncompleted
        }

        return items.take(3)
    }

    fun saveDailyFocusItems(items: List<DailyFocusItem>) {
        val jsonArray = JSONArray()
        for (item in items.take(3)) {
            if (item.text.isNotBlank()) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text.trim())
                    put("done", item.isCompleted)
                }
                jsonArray.put(obj)
            }
        }
        sp.edit()
            .putString(KEY_DAILY_FOCUS_ITEMS, jsonArray.toString())
            .putString(KEY_DAILY_INTENT_DATE, getTodayDateString())
            .apply()
    }

    fun toggleDailyFocusItem(index: Int): Boolean {
        val current = getDailyFocusItems().toMutableList()
        if (index in current.indices) {
            val item = current[index]
            current[index] = item.copy(isCompleted = !item.isCompleted)
            saveDailyFocusItems(current)
            return current[index].isCompleted
        }
        return false
    }

    fun deleteDailyFocusItem(index: Int) {
        val current = getDailyFocusItems().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            saveDailyFocusItems(current)
        }
    }

    fun clearDailyFocusItems() {
        sp.edit()
            .remove(KEY_DAILY_FOCUS_ITEMS)
            .remove(KEY_DAILY_INTENT_TEXT)
            .remove(KEY_DAILY_INTENT_COMPLETED)
            .putString(KEY_DAILY_INTENT_DATE, getTodayDateString())
            .apply()
    }

    // Backward-compatible properties for legacy checks
    var dailyIntentText: String?
        get() = getDailyFocusItems().firstOrNull()?.text
        set(value) {
            val current = getDailyFocusItems().toMutableList()
            if (value.isNullOrBlank()) {
                if (current.isNotEmpty()) {
                    current.removeAt(0)
                    saveDailyFocusItems(current)
                }
            } else {
                if (current.isNotEmpty()) {
                    current[0] = current[0].copy(text = value)
                } else {
                    current.add(DailyFocusItem(text = value))
                }
                saveDailyFocusItems(current)
            }
        }

    var isDailyIntentCompleted: Boolean
        get() = getDailyFocusItems().firstOrNull()?.isCompleted ?: false
        set(value) {
            val current = getDailyFocusItems().toMutableList()
            if (current.isNotEmpty()) {
                current[0] = current[0].copy(isCompleted = value)
                saveDailyFocusItems(current)
            }
        }

    var dailyIntentDate: String?
        get() = sp.getString(KEY_DAILY_INTENT_DATE, null)
        set(value) = sp.edit().putString(KEY_DAILY_INTENT_DATE, value).apply()

    var showScreenTimeGlance: Boolean
        get() = sp.getBoolean(KEY_SHOW_SCREEN_TIME_GLANCE, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_SCREEN_TIME_GLANCE, value).apply()

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
}

