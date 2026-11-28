package com.psei.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * Lightweight helper to query daily app screen-time usage from native UsageStatsManager.
 * - Uses native INTERVAL_DAILY buckets for total daily screen-time glance to match Digital Wellbeing.
 * - Uses exact live UsageEvents intervals (including active ongoing foreground sessions) for
 *   second-accurate app time-limit enforcement without OS bucket flush lag.
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

    fun getIgnoredPackages(context: Context): Set<String> {
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

    fun getStartOfDayMillis(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
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
     * Checks if "Display over other apps" (SYSTEM_ALERT_WINDOW) is granted.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
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
     * Opens system "Display over other apps" permission screen directly for P-Sei.
     */
    fun openOverlayPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                openUsageAccessSettings(context)
            }
        }
    }

    /**
     * Returns real-time active foreground usage in milliseconds today (since local midnight)
     * for a specific package, including any ongoing active foreground session right now.
     *
     * Handles multi-activity intra-app transitions cleanly (ActivityA.PAUSED -> ActivityB.RESUMED
     * -> ActivityA.STOPPED) so active sessions are never prematurely dropped.
     */
    fun getTodayUsageMillis(context: Context, packageName: String): Long {
        if (!hasUsagePermission(context)) return 0L
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L

        val startOfDay = getStartOfDayMillis()
        val now = System.currentTimeMillis()
        if (now <= startOfDay) return 0L

        var totalMillis = 0L
        var hadEvents = false

        try {
            val events = usageStatsManager.queryEvents(startOfDay, now)
            if (events != null) {
                val event = UsageEvents.Event()
                val activeCounts = HashMap<String, Int>()
                var sessionStart = -1L

                val maxSingleSessionMs = 12 * 3600 * 1000L

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val ts = event.timeStamp
                    if (ts < startOfDay) continue

                    val pkg = event.packageName ?: continue
                    val type = event.eventType

                    if (pkg == packageName) {
                        hadEvents = true
                        val compKey = event.className.orEmpty()

                        when (type) {
                            UsageEvents.Event.ACTIVITY_RESUMED -> {
                                activeCounts[compKey] = (activeCounts[compKey] ?: 0) + 1
                                if (sessionStart == -1L) {
                                    sessionStart = ts
                                }
                            }
                            UsageEvents.Event.ACTIVITY_PAUSED -> {
                                val prev = activeCounts[compKey] ?: 0
                                if (prev <= 1) {
                                    activeCounts.remove(compKey)
                                } else {
                                    activeCounts[compKey] = prev - 1
                                }
                                if (activeCounts.isEmpty() && sessionStart != -1L) {
                                    val delta = ts - sessionStart
                                    if (delta in 1 until maxSingleSessionMs) {
                                        totalMillis += delta
                                    }
                                    sessionStart = -1L
                                }
                            }
                        }
                    } else {
                        // Screen locked / display turned off / launcher resumed closes any open foreground session cleanly
                        val closesSession = type == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                            type == UsageEvents.Event.KEYGUARD_SHOWN ||
                            type == UsageEvents.Event.DEVICE_SHUTDOWN ||
                            (pkg == context.packageName && type == UsageEvents.Event.ACTIVITY_RESUMED)

                        if (closesSession && sessionStart != -1L) {
                            val delta = ts - sessionStart
                            if (delta in 1 until maxSingleSessionMs) {
                                totalMillis += delta
                            }
                            sessionStart = -1L
                            activeCounts.clear()
                        }
                    }
                }

                // Include currently running foreground session if screen is on
                if (sessionStart != -1L && now > sessionStart) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isInteractive = powerManager?.isInteractive ?: true
                    if (isInteractive) {
                        val delta = now - sessionStart
                        if (delta in 1 until maxSingleSessionMs) {
                            totalMillis += delta
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fall through to bucket fallback
        }

        // Fallback if UsageEvents buffer was unavailable or empty
        if (!hadEvents || totalMillis == 0L) {
            try {
                val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
                if (!statsList.isNullOrEmpty()) {
                    val pkgStats = statsList.filter { it.packageName == packageName && it.lastTimeUsed >= startOfDay }
                    val todayBuckets = pkgStats.filter { it.firstTimeStamp >= startOfDay }
                    val bucketMillis = if (todayBuckets.isNotEmpty()) {
                        todayBuckets.sumOf { it.totalTimeInForeground }
                    } else {
                        0L
                    }
                    if (bucketMillis > totalMillis) {
                        return bucketMillis
                    }
                }
            } catch (e: Exception) {
                // Ignored
            }
        }

        return totalMillis
    }

    /**
     * Returns total active foreground time spent on a specific app today (strictly since local midnight) in minutes.
     */
    fun getTodayUsageMinutes(context: Context, packageName: String): Int {
        return (getTodayUsageMillis(context, packageName) / 60_000L).toInt()
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
