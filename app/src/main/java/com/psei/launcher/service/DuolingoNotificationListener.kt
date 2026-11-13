package com.psei.launcher.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.psei.launcher.data.Prefs

/**
 * NotificationListenerService to auto-sync streak count and practice completion
 * from official Duolingo notifications.
 */
class DuolingoNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_DUOLINGO_UPDATED = "com.psei.launcher.ACTION_DUOLINGO_UPDATED"
        private const val DUOLINGO_PACKAGE = "com.duolingo"

        // Comprehensive regex patterns for Duolingo notifications:
        // Pattern 1: "50 day streak", "50-day streak", "50 days", "50 day"
        private val STREAK_REGEX_1 = Regex("""(\d+)\s*(?:[- ]?day[s]?\s+streak|streak|days|day)""", RegexOption.IGNORE_CASE)
        // Pattern 2: "🔥 50", "Streak: 50", "Day: 50"
        private val STREAK_REGEX_2 = Regex("""(?:🔥|streak|day[s]?)\s*[:#-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        // Pattern 3: "50 🔥"
        private val STREAK_REGEX_3 = Regex("""(\d+)\s*🔥""", RegexOption.IGNORE_CASE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        checkActiveNotifications()
    }

    private fun checkActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (sbn.packageName == DUOLINGO_PACKAGE) {
                    processNotification(sbn)
                }
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != DUOLINGO_PACKAGE) return
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        val ticker = sbn.notification.tickerText?.toString().orEmpty()
        val fullContent = "$title $text $bigText $subText $infoText $ticker"

        val prefs = Prefs.getInstance(applicationContext)

        // 1. Extract streak number if present
        var extractedStreak: Int? = null
        val m1 = STREAK_REGEX_1.find(fullContent)
        if (m1 != null) {
            extractedStreak = m1.groupValues[1].toIntOrNull()
        }
        if (extractedStreak == null || extractedStreak <= 0) {
            val m2 = STREAK_REGEX_2.find(fullContent)
            if (m2 != null) {
                extractedStreak = m2.groupValues[1].toIntOrNull()
            }
        }
        if (extractedStreak == null || extractedStreak <= 0) {
            val m3 = STREAK_REGEX_3.find(fullContent)
            if (m3 != null) {
                extractedStreak = m3.groupValues[1].toIntOrNull()
            }
        }

        if (extractedStreak != null && extractedStreak > 0) {
            prefs.duolingoStreak = extractedStreak
        }

        // 2. Detect if practice was completed today
        val lower = fullContent.lowercase()
        val isCompleted = lower.contains("extended") ||
                lower.contains("kept your streak") ||
                lower.contains("saved your streak") ||
                lower.contains("streak is safe") ||
                lower.contains("practiced") ||
                lower.contains("lesson complete") ||
                lower.contains("streak secured") ||
                lower.contains("completed a lesson") ||
                lower.contains("streak active") ||
                lower.contains("streak frozen")

        if (isCompleted) {
            prefs.isDuolingoPracticedToday = true
        }

        // Broadcast update to launcher UI
        sendBroadcast(Intent(ACTION_DUOLINGO_UPDATED).setPackage(packageName))
    }
}

