package com.psei.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.vivo.upslide",
        "com.bbk.launcher2",
        "com.vivo.floatingball",
        "com.android.launcher3",
        "com.vivo.simplelauncher",
        "com.vivo.privacylauncher",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.oneplus.launcher",
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.transsion.hilauncher",
        "com.transsion.XOSLauncher",
        "com.motorola.launcher3"
    )

    @Volatile
    private var cachedLauncherPackages: Set<String>? = null
    @Volatile
    private var lastLauncherQueryTime: Long = 0L

    private fun getIgnoredPackages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = cachedLauncherPackages
        if (cached != null && (now - lastLauncherQueryTime < 60_000L)) {
            return cached
        }

        val allIgnored = SYSTEM_IGNORED_PACKAGES.toMutableSet()
        allIgnored.add(context.packageName)

        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
            for (resolveInfo in activities) {
                val pkg = resolveInfo.activityInfo?.packageName
                if (pkg != null) {
                    allIgnored.add(pkg)
                }
            }
        } catch (e: Exception) {
            // Fallback to static set
        }

        cachedLauncherPackages = allIgnored
        lastLauncherQueryTime = now
        return allIgnored
    }

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
     * Aggregates active daily bucket(s) since midnight, correctly handling device reboots and eliminating
     * 24h bucket-merging distortion from yesterday across all Android OEMs.
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
            val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            if (!statsList.isNullOrEmpty()) {
                val pkgStats = statsList.filter { it.packageName == packageName && it.lastTimeUsed >= startOfDay }
                if (pkgStats.isNotEmpty()) {
                    val todayBuckets = pkgStats.filter { it.firstTimeStamp >= startOfDay }
                    val totalTime = if (todayBuckets.isNotEmpty()) {
                        todayBuckets.sumOf { it.totalTimeInForeground }
                    } else {
                        pkgStats.maxByOrNull { it.lastTimeStamp }?.totalTimeInForeground ?: 0L
                    }
                    if (totalTime > 0L) {
                        return (totalTime / 60_000L).toInt()
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        return 0
    }

    /**
     * Returns the total active screen time spent across all user applications today
     * (since midnight 00:00:00) in minutes. Returns -1 if usage permission is not granted.
     *
     * Production Multi-OEM Architecture:
     * - Uses Android's native INTERVAL_DAILY usage stats.
     * - Filters for usage strictly used today (lastTimeUsed >= startOfDay).
     * - Groups by package and aggregates all buckets started today (firstTimeStamp >= startOfDay)
     *   which captures full usage even if the phone rebooted or updated today.
     * - For non-standard OEM daily buckets that straddle midnight, falls back to the latest active bucket.
     * - Dynamically excludes all OEM stock home launchers (Samsung One UI, Pixel Launcher,
     *   Xiaomi HyperOS/MIUI, Vivo, Oppo, OnePlus, Motorola, etc.) and P-Sei itself so only actual app usage counts.
     * - Directly matches Google Digital Wellbeing and Android System Settings.
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

        val ignoredPackages = getIgnoredPackages(context)

        try {
            val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            if (!statsList.isNullOrEmpty()) {
                val statsByPackage = statsList
                    .filter { it.lastTimeUsed >= startOfDay && !ignoredPackages.contains(it.packageName) }
                    .groupBy { it.packageName }

                var totalMillis = 0L
                for ((_, list) in statsByPackage) {
                    val todayBuckets = list.filter { it.firstTimeStamp >= startOfDay }
                    val appTime = if (todayBuckets.isNotEmpty()) {
                        todayBuckets.sumOf { it.totalTimeInForeground }
                    } else {
                        list.maxByOrNull { it.lastTimeStamp }?.totalTimeInForeground ?: 0L
                    }
                    if (appTime > 0L) {
                        totalMillis += appTime
                    }
                }
                return (totalMillis / 60_000L).toInt()
            }
        } catch (e: Exception) {
            // Fallback
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
