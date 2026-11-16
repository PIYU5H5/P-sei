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
 * Uses exact event intervals from local midnight (00:00:00) to match system Digital Wellbeing.
 * Zero external libraries, zero battery drain, zero network calls.
 */
object UsageHelper {

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
     * Returns total foreground time spent on the specified app today (strictly since local midnight) in minutes.
     * Queries native UsageStatsManager aggregated daily intervals to match system Digital Wellbeing / Jovi exactly.
     */
    fun getTodayUsageMinutes(context: Context, packageName: String): Int {
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

        // 1. Primary: queryAndAggregateUsageStats combines the intervals between startOfDay and now
        try {
            val aggregated = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
            val stats = aggregated[packageName]
            if (stats != null && stats.totalTimeInForeground > 0L) {
                return (stats.totalTimeInForeground / 60_000L).toInt()
            }
        } catch (e: Exception) {
            // Fallback
        }

        // 2. Secondary fallback: queryUsageStats(INTERVAL_DAILY, startOfDay, now)
        try {
            val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            if (!statsList.isNullOrEmpty()) {
                var totalMillis = 0L
                for (s in statsList) {
                    if (s.packageName == packageName && s.lastTimeUsed >= startOfDay) {
                        totalMillis += s.totalTimeInForeground
                    }
                }
                if (totalMillis > 0L) {
                    return (totalMillis / 60_000L).toInt()
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        return 0
    }

    /**
     * Returns the total active screen time spent across all user applications today
     * (since midnight) in minutes. Returns -1 if usage permission is not granted.
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

        var totalMillis = 0L
        val launcherPkg = context.packageName

        // 1. Primary: queryAndAggregateUsageStats
        try {
            val aggregated = usageStatsManager.queryAndAggregateUsageStats(startOfDay, now)
            if (!aggregated.isNullOrEmpty()) {
                for ((pkg, stats) in aggregated) {
                    if (pkg == launcherPkg || pkg == "com.android.systemui" || pkg == "android") continue
                    if (stats.totalTimeInForeground > 0L) {
                        totalMillis += stats.totalTimeInForeground
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        // 2. Secondary fallback: queryUsageStats
        if (totalMillis == 0L) {
            try {
                val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
                if (!statsList.isNullOrEmpty()) {
                    for (s in statsList) {
                        if (s.packageName == launcherPkg || s.packageName == "com.android.systemui" || s.packageName == "android") continue
                        if (s.lastTimeUsed >= startOfDay && s.totalTimeInForeground > 0L) {
                            totalMillis += s.totalTimeInForeground
                        }
                    }
                }
            } catch (e: Exception) {
                // Defensive
            }
        }

        return (totalMillis / 60_000L).toInt()
    }

    /**
     * Formats screen time minutes into a clean, minimal string (e.g. "45m screen time today" or "2h 15m screen time today").
     */
    fun formatScreenTime(minutes: Int): String {
        if (minutes < 0) return "Tap to show screen time"
        if (minutes < 60) {
            return "${minutes}m screen time today"
        }
        val hours = minutes / 60
        val mins = minutes % 60
        return "${hours}h ${mins}m screen time today"
    }
}


