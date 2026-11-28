package com.psei.launcher.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.psei.launcher.MainActivity
import com.psei.launcher.R
import com.psei.launcher.data.Prefs
import com.psei.launcher.data.UsageHelper
import com.psei.launcher.databinding.DialogGentleLockoutBinding
import com.psei.launcher.ui.AppLimitDialogHelper
import java.lang.ref.WeakReference

/**
 * Lightweight background guard service that enforces daily app screen-time limits
 * across every entry vector:
 * - Auto-closing an app immediately when its daily limit crosses mid-session
 * - Blocking launches from Notifications
 * - Blocking launches from the Recents overview
 * - Blocking launches from redirection / deep links inside other apps
 *
 * Battery & Privacy Architecture:
 * - Zero polling while the screen is OFF (pauses completely on ACTION_SCREEN_OFF).
 * - Uses O(1) incremental delta UsageEvents windows while screen is ON (~0.1ms per tick).
 * - Automatically stops itself whenever no app limits are configured.
 * - Zero INTERNET permission, works without Accessibility Service (preserving Google Pay / UPI).
 */
class AppLimitMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var windowManager: WindowManager? = null
    private var usageStatsManager: UsageStatsManager? = null
    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null

    private var isScreenInteractive = true
    private var lastMonitorDate: String = ""
    private var lastEventTimestamp: Long = 0L
    private val lastEventHashes = HashSet<Int>()
    private var trackedForegroundPkg: String? = null

    // Per-session usage cache so active countdown doesn't rescan full day every 600ms
    private var cachedUsagePkg: String? = null
    private var cachedUsageMillis: Long = 0L
    private var cachedUsageCheckedAt: Long = 0L

    // Active full-screen lockout overlay state
    private var overlayRoot: View? = null
    private var activeOverlayPkg: String? = null
    private var dismissOverlayOnHomeResume = false
    private var tvExitHintRef: TextView? = null
    private var cooldownHandle: AppLimitDialogHelper.CooldownHandle? = null
    private var stepAwayCheckRunnable: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    stopPolling()
                    // Keep Lockscreen clear while phone is locked
                    removeOverlay()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    resyncForegroundFromRecentEvents()
                    scheduleNextPoll(150L)
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isScreenInteractive) return
            evaluateCurrentForeground()
            if (isScreenInteractive) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        prefs = Prefs.getInstance(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        isScreenInteractive = powerManager?.isInteractive ?: true
        lastMonitorDate = prefs.getTodayDateString()
        lastEventTimestamp = UsageHelper.getStartOfDayMillis()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        startForegroundMinimal()
        resyncForegroundFromRecentEvents()
        if (isScreenInteractive) {
            scheduleNextPoll(200L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundMinimal()

        if (prefs.getAllConfiguredLimits().isEmpty() || !UsageHelper.hasUsagePermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SYNC -> {
                cachedUsagePkg = null
                resyncForegroundFromRecentEvents()
                if (isScreenInteractive) {
                    scheduleNextPoll(100L)
                }
            }
            ACTION_LAUNCHER_RESUMED -> {
                trackedForegroundPkg = packageName
                if (dismissOverlayOnHomeResume) {
                    removeOverlay()
                }
            }
            ACTION_APP_LAUNCHED -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!pkg.isNullOrBlank()) {
                    trackedForegroundPkg = pkg
                    cachedUsagePkg = null
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        removeOverlay()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        if (instance?.get() == this) {
            instance = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundMinimal() {
        createNotificationChannel()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer_minimal)
            .setContentTitle("P-Sei Timer Guard")
            .setContentText("Monitoring daily app limits")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Timer Guard",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps daily app time limits active"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
    }

    private fun scheduleNextPoll(delayMs: Long) {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, delayMs)
    }

    /**
     * Scans recent UsageEvents up to now (capped to local midnight) to bootstrap exact foreground state
     * without missing currently active apps when the service starts or screen wakes.
     */
    private fun resyncForegroundFromRecentEvents() {
        if (!UsageHelper.hasUsagePermission(this)) return
        val usm = usageStatsManager ?: return

        val now = System.currentTimeMillis()
        val startOfDay = UsageHelper.getStartOfDayMillis()
        val lookbackStart = maxOf(startOfDay, now - 4 * 3600_000L)

        try {
            val events = usm.queryEvents(lookbackStart, now) ?: return
            val event = UsageEvents.Event()
            var latestTs = lookbackStart
            var latestResumed: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val ts = event.timeStamp
                val pkg = event.packageName ?: continue
                val type = event.eventType

                if (ts > latestTs) {
                    latestTs = ts
                    lastEventHashes.clear()
                }
                if (ts >= latestTs) {
                    lastEventHashes.add(eventHash(event))
                }

                when (type) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (!isTransientOverlayPackage(pkg)) {
                            latestResumed = pkg
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN -> {
                        latestResumed = null
                    }
                }
            }

            lastEventTimestamp = latestTs
            if (latestResumed != null) {
                trackedForegroundPkg = latestResumed
            } else if (MainActivity.isForeground) {
                trackedForegroundPkg = packageName
            }
        } catch (e: Exception) {
            // Defensive
        }
    }

    /**
     * O(1) incremental delta event polling (inspects only events since lastEventTimestamp).
     */
    private fun pollDeltaEvents() {
        val usm = usageStatsManager ?: return
        val now = System.currentTimeMillis()
        val startOfDay = UsageHelper.getStartOfDayMillis()
        val queryStart = lastEventTimestamp.coerceAtLeast(startOfDay)
        if (now <= queryStart) return

        try {
            val events = usm.queryEvents(queryStart, now) ?: return
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val ts = event.timeStamp
                if (ts < lastEventTimestamp) continue

                val hash = eventHash(event)
                if (ts == lastEventTimestamp && lastEventHashes.contains(hash)) {
                    continue
                }

                if (ts > lastEventTimestamp) {
                    lastEventTimestamp = ts
                    lastEventHashes.clear()
                }
                lastEventHashes.add(hash)

                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (!isTransientOverlayPackage(pkg)) {
                            onPackageEnteredForeground(pkg)
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN -> {
                        trackedForegroundPkg = null
                        cachedUsagePkg = null
                    }
                }
            }
        } catch (e: Exception) {
            // Defensive
        }
    }

    private fun eventHash(event: UsageEvents.Event): Int {
        var result = event.packageName?.hashCode() ?: 0
        result = 31 * result + event.eventType
        result = 31 * result + (event.className?.hashCode() ?: 0)
        return result
    }

    private fun onPackageEnteredForeground(pkg: String) {
        if (pkg != trackedForegroundPkg) {
            trackedForegroundPkg = pkg
            cachedUsagePkg = null
        }

        val currentBlocked = activeOverlayPkg ?: return
        if (pkg != currentBlocked) {
            // Dismiss immediately if user tapped Step Away into Home, or switched to any other allowed app (e.g., via Recents/Notification)
            if (pkg != packageName || dismissOverlayOnHomeResume) {
                removeOverlay()
            }
        }
    }

    private fun isTransientOverlayPackage(pkg: String): Boolean {
        return pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg == "com.google.android.permissioncontroller" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller"
    }

    private fun evaluateCurrentForeground() {
        val today = prefs.getTodayDateString()
        if (today != lastMonitorDate) {
            // Midnight crossed: reset cached counters cleanly
            lastMonitorDate = today
            cachedUsagePkg = null
            lastEventTimestamp = UsageHelper.getStartOfDayMillis()
            lastEventHashes.clear()
            trackedForegroundPkg = null
            removeOverlay()
            resyncForegroundFromRecentEvents()
        }

        val configuredLimits = prefs.getAllConfiguredLimits()
        if (configuredLimits.isEmpty() || !UsageHelper.hasUsagePermission(this)) {
            removeOverlay()
            stopSelf()
            return
        }

        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            return
        }

        pollDeltaEvents()

        val activePkg = trackedForegroundPkg ?: return
        if (activePkg == packageName || isTransientOverlayPackage(activePkg)) {
            if (activeOverlayPkg != null && dismissOverlayOnHomeResume) {
                removeOverlay()
            }
            return
        }

        val limitMinutes = configuredLimits[activePkg] ?: return
        if (limitMinutes <= 0) return

        if (prefs.isAppSnoozed(activePkg)) {
            if (activeOverlayPkg == activePkg) {
                removeOverlay()
            }
            return
        }

        // If our overlay is already blocking this package on screen, keep waiting cleanly
        if (activeOverlayPkg == activePkg && overlayRoot != null) {
            return
        }

        val now = System.currentTimeMillis()
        val limitMillis = limitMinutes * 60_000L

        // Fast local estimation while inside the same session; full UsageEvents verification on enter, every 5s, and on crossing
        val needExactQuery = cachedUsagePkg != activePkg ||
            (now - cachedUsageCheckedAt >= 5_000L) ||
            (cachedUsageMillis + (now - cachedUsageCheckedAt) >= limitMillis - 1_500L)

        val usedMillis = if (needExactQuery) {
            val exact = UsageHelper.getTodayUsageMillis(this, activePkg)
            cachedUsagePkg = activePkg
            cachedUsageMillis = exact
            cachedUsageCheckedAt = now
            exact
        } else {
            cachedUsageMillis + (now - cachedUsageCheckedAt)
        }

        if (usedMillis >= limitMillis) {
            enforceLockout(activePkg, usedMillis, limitMinutes)
        }
    }

    private fun enforceLockout(pkg: String, usedMillis: Long, limitMinutes: Int) {
        val appLabel = resolveAppLabel(pkg)
        val usedMinutes = (usedMillis / 60_000L).toInt().coerceAtLeast(limitMinutes)

        // Pause Reels / YouTube Shorts / video audio cleanly
        silentlyPauseMediaAudio()

        if (UsageHelper.hasOverlayPermission(this)) {
            // Show immediate full-screen lockout overlay on top of the blocked app
            showLockoutOverlay(pkg, appLabel, usedMinutes, limitMinutes)
            // Also move the blocked app out of foreground behind the overlay so its timer stops running
            tryMoveBlockedAppToBackground(keepOverlayVisible = true)
        } else {
            // Fallback when overlay permission wasn't granted yet
            LockAccessibilityService.goHome()
            val lockoutIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(MainActivity.EXTRA_LOCKOUT_PACKAGE, pkg)
                putExtra(MainActivity.EXTRA_LOCKOUT_USED_MINUTES, usedMinutes)
                putExtra(MainActivity.EXTRA_LOCKOUT_LIMIT_MINUTES, limitMinutes)
            }
            try {
                startActivity(lockoutIntent)
            } catch (e: Exception) {
                // Blocked by background launch policy without overlay permission
            }
        }
    }

    private fun tryMoveBlockedAppToBackground(keepOverlayVisible: Boolean) {
        dismissOverlayOnHomeResume = !keepOverlayVisible
        if (LockAccessibilityService.isServiceRunning) {
            LockAccessibilityService.goHome()
        }
        try {
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            // OEM background launch blocked — overlay still covers 100% of screen safely
        }
    }

    private fun showLockoutOverlay(pkg: String, appLabel: String, usedMinutes: Int, limitMinutes: Int) {
        removeOverlay()

        val wm = windowManager ?: return
        val themeResId = resolveOverlayThemeRes()
        val themedContext = ContextThemeWrapper(this, themeResId)

        val wrapper = object : FrameLayout(themedContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    performStepAway(pkg)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }

        val contentBinding = DialogGentleLockoutBinding.inflate(LayoutInflater.from(themedContext), wrapper, true)

        tvExitHintRef = contentBinding.tvExitHint
        contentBinding.tvLockoutMessage.text =
            "You've reached your ${limitMinutes}m daily limit for $appLabel (${usedMinutes}m used today).\nTake a slow breath, relax, and recharge."

        AppLimitDialogHelper.applyBottomSystemBarInsets(contentBinding.bottomControlsContainer)
        AppLimitDialogHelper.attachBreathingGuidance(contentBinding.breathingBackdrop, contentBinding.tvBreathPhase)
        AppLimitDialogHelper.animateFullscreenEntrance(
            wrapper,
            contentBinding.centerLockoutContainer,
            contentBinding.bottomControlsContainer
        )

        cooldownHandle = AppLimitDialogHelper.bindEmergencySnoozeButton(
            context = this,
            pkg = pkg,
            appLabel = appLabel,
            btnSnooze = contentBinding.btnSnoozeEmergency,
            progressCooldown = contentBinding.progressEmergencyCooldown,
            onTickHaptic = { vibrateTick() },
            onBeforeLaunchSnoozed = {
                cachedUsagePkg = null
                removeOverlay()
                // Ensure blocked app returns to foreground if goHome() backgrounded it underneath
                if (MainActivity.isForeground || trackedForegroundPkg == packageName) {
                    try {
                        val relaunch = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (relaunch != null) {
                            startActivity(relaunch)
                        }
                    } catch (e: Exception) {
                        // Ignored
                    }
                }
            }
        )

        contentBinding.btnStepAway.setOnClickListener {
            performStepAway(pkg)
        }

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(wrapper, params)
            overlayRoot = wrapper
            activeOverlayPkg = pkg
            dismissOverlayOnHomeResume = false
        } catch (e: Exception) {
            overlayRoot = null
            activeOverlayPkg = null
        }
    }

    private fun performStepAway(blockedPkg: String) {
        Toast.makeText(applicationContext, "Good choice stepping away", Toast.LENGTH_SHORT).show()

        // If Home launcher is already foreground underneath the overlay, dismiss immediately
        if (MainActivity.isForeground || trackedForegroundPkg == packageName) {
            removeOverlay()
            return
        }

        dismissOverlayOnHomeResume = true
        LockAccessibilityService.goHome()
        try {
            val homeIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            // Fallback to generic HOME intent
            try {
                val genericHome = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(genericHome)
            } catch (e2: Exception) {
                // Handled below
            }
        }

        // Verify launcher resumed within 400ms; if aggressive OEM blocked background startActivity,
        // keep overlay covering the blocked app and reveal exit hint instead of exposing the blocked app.
        stepAwayCheckRunnable?.let { handler.removeCallbacks(it) }
        val checkRunnable = Runnable {
            if (overlayRoot != null && activeOverlayPkg == blockedPkg) {
                pollDeltaEvents()
                if (MainActivity.isForeground || (trackedForegroundPkg != null && trackedForegroundPkg != blockedPkg)) {
                    removeOverlay()
                } else {
                    tvExitHintRef?.isVisible = true
                }
            }
        }
        stepAwayCheckRunnable = checkRunnable
        handler.postDelayed(checkRunnable, 400L)
    }

    private fun removeOverlay() {
        cooldownHandle?.cancel()
        cooldownHandle = null
        stepAwayCheckRunnable?.let { handler.removeCallbacks(it) }
        stepAwayCheckRunnable = null
        tvExitHintRef = null

        val view = overlayRoot ?: return
        overlayRoot = null
        activeOverlayPkg = null
        dismissOverlayOnHomeResume = false

        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            try {
                windowManager?.removeView(view)
            } catch (e2: Exception) {
                // Ignored
            }
        }
    }

    private fun resolveOverlayThemeRes(): Int {
        val themeKey = if (!prefs.isProUser) Prefs.THEME_DARK else prefs.theme
        return when (themeKey) {
            Prefs.THEME_SEPIA -> R.style.Theme_MinimalLauncher_Sepia
            Prefs.THEME_FOREST -> R.style.Theme_MinimalLauncher_Forest
            Prefs.THEME_CHARCOAL -> R.style.Theme_MinimalLauncher_Charcoal
            Prefs.THEME_CYBER -> R.style.Theme_MinimalLauncher_Cyber
            Prefs.THEME_LIGHT -> R.style.Theme_MinimalLauncher_Light
            else -> R.style.Theme_MinimalLauncher_Dark
        }
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    private fun silentlyPauseMediaAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            am.requestAudioFocus(focusRequest)
            handler.postDelayed({
                try {
                    am.abandonAudioFocusRequest(focusRequest)
                } catch (e: Exception) {
                    // Ignored
                }
            }, 300L)
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun vibrateTick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Ignored
        }
    }

    companion object {
        private const val CHANNEL_ID = "app_limits_monitor"
        private const val NOTIFICATION_ID = 1042
        private const val POLL_INTERVAL_MS = 600L

        private const val ACTION_SYNC = "com.psei.launcher.action.SYNC_LIMITS"
        private const val ACTION_LAUNCHER_RESUMED = "com.psei.launcher.action.LAUNCHER_RESUMED"
        private const val ACTION_APP_LAUNCHED = "com.psei.launcher.action.APP_LAUNCHED"
        private const val EXTRA_PACKAGE_NAME = "extra_pkg"

        private var instance: WeakReference<AppLimitMonitorService>? = null

        fun syncMonitoringState(context: Context) {
            val appCtx = context.applicationContext
            val prefs = Prefs.getInstance(appCtx)
            val hasLimits = prefs.getAllConfiguredLimits().isNotEmpty()
            val hasUsage = UsageHelper.hasUsagePermission(appCtx)

            val serviceIntent = Intent(appCtx, AppLimitMonitorService::class.java).apply {
                action = ACTION_SYNC
            }

            if (hasLimits && hasUsage) {
                try {
                    ContextCompat.startForegroundService(appCtx, serviceIntent)
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException on background OEM edge case
                }
            } else {
                try {
                    appCtx.stopService(serviceIntent)
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }

        fun onLauncherResumed(context: Context) {
            val running = instance?.get()
            if (running != null) {
                running.handler.post {
                    running.onPackageEnteredForeground(context.packageName)
                }
            }
        }

        fun onAppLaunchedFromLauncher(context: Context, packageName: String) {
            val running = instance?.get()
            if (running != null) {
                running.handler.post {
                    running.onPackageEnteredForeground(packageName)
                }
            } else {
                val prefs = Prefs.getInstance(context.applicationContext)
                if (prefs.getAllConfiguredLimits().isNotEmpty() && UsageHelper.hasUsagePermission(context)) {
                    try {
                        val intent = Intent(context.applicationContext, AppLimitMonitorService::class.java).apply {
                            action = ACTION_APP_LAUNCHED
                            putExtra(EXTRA_PACKAGE_NAME, packageName)
                        }
                        ContextCompat.startForegroundService(context.applicationContext, intent)
                    } catch (e: Exception) {
                        // Ignored
                    }
                }
            }
        }

        fun onAccessibilityPackageEntered(packageName: String) {
            val running = instance?.get() ?: return
            running.handler.post {
                if (!running.isTransientOverlayPackage(packageName)) {
                    running.onPackageEnteredForeground(packageName)
                    running.evaluateCurrentForeground()
                }
            }
        }
    }
}
