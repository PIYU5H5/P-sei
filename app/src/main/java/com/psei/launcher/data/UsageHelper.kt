package com.psei.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Lightweight helper to query daily app screen-time usage from native UsageStatsManager.
 * Uses exact event intervals from local midnight (00:00:00) via UsageEvents to match
 * system Digital Wellbeing / Jovi exactly, without bucket-merging distortion from yesterday.
 * Zero external libraries, zero battery drain, zero network calls.
 */
object UsageHelper {

    private val SYSTEM_IGNORED_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.vivo.upslide",
        "com.bbk.launcher2",
        "com.vivo.floatingball"
    )

    /**
     * Checks if the user has granted PACKAGE_USAGE_STATS permission to P-Sei.
     */
    fun hasUsagePermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Opens system Usage Access settings page.
     */
    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                // Ignored
            }
        }
    }

    /**
     * Returns total active foreground time spent on a specific app today (strictly since local midnight) in minutes.
     * Uses native UsageEvents for timestamped precision matching Digital Wellbeing.
     */
    fun getTodayUsageMinutes(context: Context, packageName: String): Int {
        if (!hasUsagePermission(context)) return 0
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()
        if (now <= startOfDay) return 0

        try {
            val usageEvents = usageStatsManager.queryEvents(startOfDay, now)
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                var currentStart = -1L
                var totalMillis = 0L

                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    val timestamp = event.timeStamp
                    if (timestamp < startOfDay) continue

                    if (pkg == packageName) {
                        when (event.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED -> {
                                if (currentStart == -1L) {
                                    currentStart = timestamp
                                }
                            }
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED -> {
                                if (currentStart != -1L) {
                                    val delta = timestamp - currentStart
                                    if (delta in 1 until (12 * 3600 * 1000L)) {
                                        totalMillis += delta
                                    }
                                    currentStart = -1L
                                }
                            }
                        }
                    } else {
                        // Another app took over foreground
                        if (currentStart != -1L && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            val delta = timestamp - currentStart
                            if (delta in 1 until (12 * 3600 * 1000L)) {
                                totalMillis += delta
                            }
                            currentStart = -1L
                        }
                    }

                    if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE && currentStart != -1L) {
                        val delta = timestamp - currentStart
                        if (delta in 1 until (12 * 3600 * 1000L)) {
                            totalMillis += delta
                        }
                        currentStart = -1L
                    }
                }

                if (currentStart != -1L && now > currentStart) {
                    val delta = now - currentStart
                    if (delta in 1 until (12 * 3600 * 1000L)) {
                        totalMillis += delta
                    }
                }

                if (totalMillis > 0L) {
                    return (totalMillis / 60_000L).toInt()
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        // Secondary fallback for edge devices
        try {
            val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            if (!statsList.isNullOrEmpty()) {
                val stats = statsList.firstOrNull { it.packageName == packageName && it.lastTimeUsed >= startOfDay }
                if (stats != null && stats.totalTimeInForeground > 0L) {
                    return (stats.totalTimeInForeground / 60_000L).toInt()
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        return 0
    }

    /**
     * Returns the total active screen time spent across all user applications today
     * (since midnight 00:00:00) in minutes. Returns -1 if usage permission is not granted.
     *
     * Precision Design:
     * - Uses exact UsageEvents stream between local midnight and now.
     * - Prevents Android's queryAndAggregateUsageStats from falsely merging yesterday's historical buckets.
     * - Excludes launcher and system overlays.
     */
    fun getTotalScreenTimeTodayMinutes(context: Context): Int {
        if (!hasUsagePermission(context)) return -1
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return -1

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()
        if (now <= startOfDay) return 0

        val launcherPkg = context.packageName

        try {
            val usageEvents = usageStatsManager.queryEvents(startOfDay, now)
            if (usageEvents != null) {
                val event = UsageEvents.Event()
                var currentStart = -1L
                var totalMillis = 0L

                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    val timestamp = event.timeStamp
                    if (timestamp < startOfDay) continue

                    val isIgnored = (pkg == launcherPkg || SYSTEM_IGNORED_PACKAGES.contains(pkg))

                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            if (!isIgnored) {
                                if (currentStart == -1L) {
                                    currentStart = timestamp
                                }
                            } else {
                                // Returned to launcher or system UI
                                if (currentStart != -1L) {
                                    val delta = timestamp - currentStart
                                    if (delta in 1 until (12 * 3600 * 1000L)) {
                                        totalMillis += delta
                                    }
                                    currentStart = -1L
                                }
                            }
                        }
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            // Screen turned off
                            if (currentStart != -1L) {
                                val delta = timestamp - currentStart
                                if (delta in 1 until (12 * 3600 * 1000L)) {
                                    totalMillis += delta
                                }
                                currentStart = -1L
                            }
                        }
                    }
                }

                if (currentStart != -1L && now > currentStart) {
                    val delta = now - currentStart
                    if (delta in 1 until (12 * 3600 * 1000L)) {
                        totalMillis += delta
                    }
                }

                return (totalMillis / 60_000L).toInt()
            }
        } catch (e: Exception) {
            // Fallback
        }

        // Secondary fallback
        try {
            val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            if (!statsList.isNullOrEmpty()) {
                var fallbackMillis = 0L
                for (s in statsList) {
                    if (s.packageName == launcherPkg || SYSTEM_IGNORED_PACKAGES.contains(s.packageName)) continue
                    if (s.lastTimeUsed >= startOfDay && s.totalTimeInForeground > 0L) {
                        fallbackMillis += s.totalTimeInForeground
                    }
                }
                return (fallbackMillis / 60_000L).toInt()
            }
        } catch (e: Exception) {
            // Ignored
        }

        return 0
    }

    /**
     * Formats screen time minutes into a clean, minimal string (e.g. "45m screen time today" or "1h 58m screen time today").
     */
    fun formatScreenTime(minutes: Int): String {
        if (minutes < 0) return "Tap to show screen time"
        if (minutes == 0) return "0m screen time today"
        if (minutes < 60) {
            return "${minutes}m screen time today"
        }
        val hours = minutes / 60
        val mins = minutes % 60
        return if (mins > 0) {
            "${hours}h ${mins}m screen time today"
        } else {
            "${hours}h screen time today"
        }
    }
}
