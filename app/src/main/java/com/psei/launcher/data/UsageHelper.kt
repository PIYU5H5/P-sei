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
     * Computes exact milliseconds using UsageEvents activity transitions to match system Digital Wellbeing / Jovi.
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

        // Query starting from 2 hours before midnight to capture any session crossing midnight
        val queryStart = startOfDay - (2 * 3600_000L)

        val events = try {
            usageStatsManager.queryEvents(queryStart, now)
        } catch (e: Exception) {
            null
        } ?: return 0

        var totalMillis = 0L
        var sessionStart = 0L
        var resumedCount = 0

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val eventType = event.eventType
            val eventPkg = event.packageName
            val eventTime = event.timeStamp

            if (eventPkg == packageName) {
                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (sessionStart == 0L) {
                        sessionStart = eventTime
                    }
                    resumedCount++
                } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                    resumedCount = maxOf(0, resumedCount - 1)
                    if (resumedCount == 0 && sessionStart > 0L) {
                        val effectiveStart = maxOf(sessionStart, startOfDay)
                        val effectiveEnd = minOf(eventTime, now)
                        if (effectiveEnd > effectiveStart) {
                            totalMillis += (effectiveEnd - effectiveStart)
                        }
                        sessionStart = 0L
                    }
                }
            } else {
                // Another app came into foreground or screen turned off -> close target session
                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED && sessionStart > 0L) {
                    val effectiveStart = maxOf(sessionStart, startOfDay)
                    val effectiveEnd = minOf(eventTime, now)
                    if (effectiveEnd > effectiveStart) {
                        totalMillis += (effectiveEnd - effectiveStart)
                    }
                    sessionStart = 0L
                    resumedCount = 0
                } else if (eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE && sessionStart > 0L) {
                    val effectiveStart = maxOf(sessionStart, startOfDay)
                    val effectiveEnd = minOf(eventTime, now)
                    if (effectiveEnd > effectiveStart) {
                        totalMillis += (effectiveEnd - effectiveStart)
                    }
                    sessionStart = 0L
                    resumedCount = 0
                }
            }
        }

        // If the app is currently in the foreground right now:
        if (sessionStart > 0L) {
            val effectiveStart = maxOf(sessionStart, startOfDay)
            if (now > effectiveStart) {
                totalMillis += (now - effectiveStart)
            }
        }

        return (totalMillis / 60_000L).toInt()
    }
}


