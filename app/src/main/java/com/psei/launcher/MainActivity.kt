package com.psei.launcher

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.psei.launcher.billing.BillingManager
import com.psei.launcher.data.AppModel
import com.psei.launcher.data.AppRepository
import com.psei.launcher.data.CalendarEventModel
import com.psei.launcher.data.CalendarRepository
import com.psei.launcher.data.UsageHelper
import android.os.CountDownTimer
import com.psei.launcher.data.Prefs
import com.psei.launcher.databinding.ActivityMainBinding
import com.psei.launcher.databinding.DialogAppActionsBinding
import com.psei.launcher.databinding.DialogAppPickerBinding
import com.psei.launcher.databinding.DialogDuolingoEditBinding
import com.psei.launcher.databinding.DialogGentleLockoutBinding
import com.psei.launcher.databinding.DialogKansoProBinding
import com.psei.launcher.databinding.DialogMindfulDelayBinding
import com.psei.launcher.databinding.DialogRenameBinding
import com.psei.launcher.databinding.ItemCalendarEventBinding
import com.psei.launcher.databinding.ItemHomeSlotBinding
import com.psei.launcher.service.DuolingoNotificationListener
import com.psei.launcher.service.LauncherAdminReceiver
import com.psei.launcher.service.LockAccessibilityService
import com.psei.launcher.ui.AppListAdapter
import com.psei.launcher.ui.GestureHandler
import com.psei.launcher.ui.InsetsUtils
import java.lang.reflect.Method

/**
 * Main Home Launcher Activity.
 *
 * Implements strict lightweight launcher standards:
 * - launchMode="singleTask" handling home button presses.
 * - Non-destructive back button handling (closes drawer or ignores).
 * - Zero heavy libraries, zero Jetpack Compose overhead, zero image icon decoding.
 * - Full edge-to-edge support for vivo Y53s notch and gesture pill.
 */
class MainActivity : AppCompatActivity(), AppRepository.OnAppsChangedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var appRepo: AppRepository
    private lateinit var drawerAdapter: AppListAdapter

    private var activeDialog: Dialog? = null
    private var duolingoLaunchTime: Long = 0L
    private var lastCalendarQueryTime: Long = 0L

    private val duolingoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderDuolingoWidget()
        }
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadTodayCalendarEvents()
        } else {
            Toast.makeText(this, R.string.calendar_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (prefs.isLockoutActive) {
                return
            }
            if (activeDialog?.isShowing == true) {
                activeDialog?.dismiss()
                activeDialog = null
                return
            }
            if (binding.drawerContainer.isVisible) {
                closeDrawer()
                return
            }
            // Intentionally consume back press on home screen without finishing activity
        }
    }

    private var currentAppliedTheme: String = ""
    private var lastAppliedWallpaperFlag: Boolean? = null

    private fun applyCustomTheme() {
        val themeToApply = if (!prefs.isProUser) Prefs.THEME_DARK else prefs.theme
        when (themeToApply) {
            Prefs.THEME_SEPIA -> setTheme(R.style.Theme_MinimalLauncher_Sepia)
            Prefs.THEME_FOREST -> setTheme(R.style.Theme_MinimalLauncher_Forest)
            Prefs.THEME_CHARCOAL -> setTheme(R.style.Theme_MinimalLauncher_Charcoal)
            Prefs.THEME_CYBER -> setTheme(R.style.Theme_MinimalLauncher_Cyber)
            Prefs.THEME_LIGHT -> setTheme(R.style.Theme_MinimalLauncher_Light)
            else -> setTheme(R.style.Theme_MinimalLauncher_Dark)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.getInstance(this)
        currentAppliedTheme = prefs.theme
        applyCustomTheme()
        super.onCreate(savedInstanceState)

        // Edge-to-edge window setup
        WindowCompat.setDecorFitsSystemWindows(window, false)

        appRepo = AppRepository.getInstance(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        setupInsets()
        setupWallpaperFlag()
        setupRecyclerView()
        setupSearch()
        setupGestures()
        setupShortcuts()
        setupCalendarWidget()
        setupDuolingoWidget()
        setupLockoutUi()

        try {
            val filter = IntentFilter(DuolingoNotificationListener.ACTION_DUOLINGO_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(duolingoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(duolingoReceiver, filter)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    override fun onStart() {
        super.onStart()
        // Connect to AppRepository; registers LauncherApps.Callback and delivers cached apps
        appRepo.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        // Disconnect listener to avoid leaks
        appRepo.onStop(this)

        if (prefs.isLockoutActive && !isFinishing) {
            val reopenIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(reopenIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.theme != currentAppliedTheme) {
            recreate()
            return
        }

        checkLockoutState()
        if (prefs.isLockoutActive) {
            return
        }

        // Check if returning from Duolingo session
        if (duolingoLaunchTime > 0L) {
            val duration = System.currentTimeMillis() - duolingoLaunchTime
            duolingoLaunchTime = 0L
            if (duration >= 15_000L && !prefs.isDuolingoPracticedToday) {
                if (prefs.duolingoStreak > 0) {
                    prefs.duolingoStreak += 1
                }
                prefs.isDuolingoPracticedToday = true
                Toast.makeText(this, "Duolingo streak extended! 🔥", Toast.LENGTH_SHORT).show()
            }
        }

        // Refresh wallpaper flag and UI state in case user changed settings
        setupWallpaperFlag()
        renderHomeSlots()
        updateShortcutsText()
        updateTextSizes()
        renderDuolingoWidget()
        loadTodayCalendarEvents()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && prefs.isLockoutActive) {
            collapseNotificationPanel()
        }
    }

    override fun onPause() {
        super.onPause()
        lockoutTimer?.cancel()
        lockoutTimer = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // System Home gesture / button tapped: return immediately to home screen
        activeDialog?.dismiss()
        activeDialog = null
        if (prefs.isLockoutActive) {
            checkLockoutState()
            return
        }
        if (binding.drawerContainer.isVisible) {
            closeDrawer()
        }
    }

    /**
     * WindowInsets padding ensures vivo Y53s waterdrop notch and gesture navigation bar
     * do not overlap text views.
     */
    private fun setupInsets() {
        InsetsUtils.applySystemWindowInsets(
            topView = binding.clockArea,
            bottomView = binding.bottomShortcutsContainer
        )

        // Also pad drawer search header and app list
        InsetsUtils.applySystemWindowInsets(
            topView = binding.searchHeader,
            bottomView = binding.rvAppDrawer
        )

        // Lockout screen insets
        InsetsUtils.applySystemWindowInsets(
            topView = binding.tvLockoutSubhead,
            bottomView = binding.btnLockoutEmergencyExit
        )
    }

    private fun setupWallpaperFlag() {
        val showWallpaper = prefs.showWallpaper
        if (lastAppliedWallpaperFlag != showWallpaper) {
            lastAppliedWallpaperFlag = showWallpaper
            if (showWallpaper) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                binding.rootLayout.setBackgroundColor(Color.TRANSPARENT)
                binding.homeContainer.setBackgroundColor(Color.TRANSPARENT)
                // Slightly translucent drawer background so wallpaper subtly shines through
                binding.drawerContainer.setBackgroundColor(Color.parseColor("#E6000000"))
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                val bgColor = getThemeColor(R.attr.launcherBackground)
                binding.rootLayout.setBackgroundColor(bgColor)
                binding.homeContainer.setBackgroundColor(Color.TRANSPARENT)
                binding.drawerContainer.setBackgroundColor(bgColor)
            }
        }
    }

    private fun setupRecyclerView() {
        drawerAdapter = AppListAdapter(
            onAppClick = { app ->
                launchApp(app)
                closeDrawer()
            },
            onAppLongClick = { app ->
                showAppActionsDialog(app)
            }
        )

        binding.rvAppDrawer.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = drawerAdapter
            setHasFixedSize(true)
            itemAnimator = null // Disable animations for instant rendering
        }

        // Immediately populate from persistent disk cache so apps are visible on frame zero
        val cached = appRepo.getCachedApps()
        if (cached.isNotEmpty()) {
            val hiddenKeys = prefs.hiddenPackages
            val visibleApps = cached.filterNot { hiddenKeys.contains(it.componentKey) }
            drawerAdapter.submitList(visibleApps)
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                binding.btnClearSearch.isVisible = query.isNotEmpty()
                drawerAdapter.filter(query)

                val displayed = drawerAdapter.getDisplayedApps()
                binding.tvEmptyApps.isVisible = displayed.isEmpty()

                // Auto-launch single search result if enabled (Pro)
                if (prefs.isProUser && prefs.autoLaunchSingleResult && displayed.size == 1 && query.isNotEmpty()) {
                    launchApp(displayed[0])
                    closeDrawer()
                }
            }
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
        }
    }

    private fun setupGestures() {
        val gestureCallbacks = object : GestureHandler.Callbacks {
            override fun onDoubleTap() {
                handleDoubleTapLock()
            }

            override fun onSwipeUp() {
                openDrawer()
            }

            override fun onSwipeDown() {
                handleSwipeDownNotifications()
            }

            override fun onSwipeLeft() {
                handleCustomShortcut(prefs.shortcutSwipeLeft)
            }

            override fun onSwipeRight() {
                handleCustomShortcut(prefs.shortcutSwipeRight)
            }
        }

        val gestureHandler = GestureHandler(this, gestureCallbacks)
        binding.homeContainer.setOnTouchListener(gestureHandler)

        // Tapping the clock/date area also opens the app drawer
        binding.clockArea.setOnClickListener {
            openDrawer()
        }
    }

    private fun setupShortcuts() {
        binding.btnBottomLeft.setOnClickListener {
            handleBottomShortcut(prefs.shortcutBottomLeft, isPhone = true)
        }
        binding.btnBottomLeft.setOnLongClickListener {
            openAppPickerForShortcut(isBottomLeft = true)
            true
        }

        binding.btnBottomRight.setOnClickListener {
            handleBottomShortcut(prefs.shortcutBottomRight, isPhone = false)
        }
        binding.btnBottomRight.setOnLongClickListener {
            openAppPickerForShortcut(isBottomLeft = false)
            true
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateShortcutsText()
    }

    private fun updateShortcutsText() {
        // Bottom-left
        val leftKey = prefs.shortcutBottomLeft
        binding.btnBottomLeft.text = if (leftKey == Prefs.SPECIAL_SHORTCUT_PHONE) {
            getString(R.string.default_phone_name)
        } else {
            appRepo.findApp(leftKey)?.label
                ?: prefs.getCustomAppName(leftKey)
                ?: leftKey?.substringAfterLast('.')?.substringAfter('/')
                ?: getString(R.string.default_phone_name)
        }

        // Bottom-right
        val rightKey = prefs.shortcutBottomRight
        binding.btnBottomRight.text = if (rightKey == Prefs.SPECIAL_SHORTCUT_CAMERA) {
            getString(R.string.default_camera_name)
        } else {
            appRepo.findApp(rightKey)?.label
                ?: prefs.getCustomAppName(rightKey)
                ?: rightKey?.substringAfterLast('.')?.substringAfter('/')
                ?: getString(R.string.default_camera_name)
        }
    }

    private fun updateTextSizes() {
        val (slotSize, drawerSize) = when (prefs.textSize) {
            Prefs.TEXT_SIZE_SMALL -> Pair(22f, 17f)
            Prefs.TEXT_SIZE_LARGE -> Pair(30f, 23f)
            else -> Pair(26f, 20f)
        }

        drawerAdapter.setTextSizeSp(drawerSize)

        val slotCount = prefs.homeSlotsCount
        val verticalPaddingDp = if (slotCount > 7) 6 else if (slotCount > 5) 8 else 12
        val density = resources.displayMetrics.density
        val padPx = (verticalPaddingDp * density).toInt()
        val horizPadPx = (24 * density).toInt()

        for (i in 0 until binding.slotsContainer.childCount) {
            val tv = binding.slotsContainer.getChildAt(i) as? TextView
            tv?.textSize = slotSize
            tv?.setPadding(horizPadPx, padPx, horizPadPx, padPx)
        }
    }

    // =========================================================================
    // GOOGLE CALENDAR DAILY EVENTS WIDGET (PRO)
    // =========================================================================

    private fun setupCalendarWidget() {
        binding.btnConnectCalendar.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Google Calendar daily events widget is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
        binding.layoutCalendarContainer.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Google Calendar daily events widget is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            openGoogleCalendarApp()
        }
        loadTodayCalendarEvents()
    }

    private fun loadTodayCalendarEvents() {
        if (!prefs.isProUser || !prefs.showCalendarWidget) {
            binding.layoutCalendarContainer.isVisible = false
            return
        }
        binding.layoutCalendarContainer.isVisible = true

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            binding.btnConnectCalendar.isVisible = true
            binding.layoutCalendarEvents.removeAllViews()
            binding.tvNoEvents.isVisible = false
            return
        }

        binding.btnConnectCalendar.isVisible = false

        val now = System.currentTimeMillis()
        if (now - lastCalendarQueryTime < 60_000L && binding.layoutCalendarEvents.childCount > 0) {
            return
        }
        lastCalendarQueryTime = now

        // Query Calendar ContentResolver in background thread to prevent UI thread frame drops
        appRepo.executeInBackground {
            val events = CalendarRepository.getTodayEvents(this@MainActivity)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    renderCalendarEvents(events)
                }
            }
        }
    }

    private fun renderCalendarEvents(events: List<CalendarEventModel>) {
        binding.layoutCalendarEvents.removeAllViews()

        if (events.isEmpty()) {
            binding.tvNoEvents.isVisible = true
        } else {
            binding.tvNoEvents.isVisible = false
            val inflater = LayoutInflater.from(this)
            val maxDisplay = 4
            val displayEvents = events.take(maxDisplay)

            for (event in displayEvents) {
                val eventBinding = ItemCalendarEventBinding.inflate(inflater, binding.layoutCalendarEvents, false)
                eventBinding.tvEventTime.text = event.getFormattedTime()
                eventBinding.tvEventTitle.text = event.title
                eventBinding.root.setOnClickListener {
                    openGoogleCalendarApp(event.startMillis)
                }
                binding.layoutCalendarEvents.addView(eventBinding.root)
            }

            if (events.size > maxDisplay) {
                val moreTv = TextView(this).apply {
                    text = "+ ${events.size - maxDisplay} more events"
                    textSize = 12f
                    setTextColor(getThemeColor(R.attr.secondaryTextColor))
                    setPadding(0, 4, 0, 4)
                    setOnClickListener { openGoogleCalendarApp() }
                }
                binding.layoutCalendarEvents.addView(moreTv)
            }
        }
    }

    private fun openGoogleCalendarApp(timeMillis: Long = System.currentTimeMillis()) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = ContentUris.appendId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), timeMillis).build()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.calendar")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "Could not open Calendar", Toast.LENGTH_SHORT).show()
                }
            } catch (e2: Exception) {
                Toast.makeText(this, "Could not open Calendar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // DUOLINGO STREAK WIDGET (PRO)
    // =========================================================================

    private fun setupDuolingoWidget() {
        binding.layoutDuolingoWidget.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Duolingo streak widget is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            if (prefs.duolingoStreak <= 0) {
                // If streak is not set yet, prompt user immediately with setup dialog
                showDuolingoEditDialog()
            } else {
                openDuolingoApp()
            }
        }
        binding.layoutDuolingoWidget.setOnLongClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Duolingo streak widget is a P-Sei Pro feature.")
                return@setOnLongClickListener true
            }
            vibrateTick()
            showDuolingoEditDialog()
            true
        }
        renderDuolingoWidget()
    }

    private fun renderDuolingoWidget() {
        if (!prefs.isProUser || !prefs.showDuolingoWidget) {
            binding.layoutDuolingoWidget.isVisible = false
            return
        }
        binding.layoutDuolingoWidget.isVisible = true

        val streak = prefs.duolingoStreak
        val isPracticed = prefs.isDuolingoPracticedToday

        binding.tvDuolingoStreak.text = if (streak > 0) streak.toString() else "Set"
        if (isPracticed) {
            // Practiced today: Fire icon is lit (RED)
            binding.ivDuolingoFlame.setImageResource(R.drawable.ic_flame)
            binding.tvDuolingoStreak.setTextColor(getThemeColor(R.attr.primaryTextColor))
        } else {
            // Not practiced yet today: Fire icon is unlit (GREY)
            binding.ivDuolingoFlame.setImageResource(R.drawable.ic_flame_grey)
            binding.tvDuolingoStreak.setTextColor(getThemeColor(R.attr.secondaryTextColor))
        }
    }

    private fun openDuolingoApp() {
        val duoPackage = "com.duolingo"
        val launchIntent = packageManager.getLaunchIntentForPackage(duoPackage)
        if (launchIntent != null) {
            duolingoLaunchTime = System.currentTimeMillis()
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, R.string.duolingo_not_installed, Toast.LENGTH_SHORT).show()
            showDuolingoEditDialog()
        }
    }

    private fun showDuolingoEditDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogDuolingoEditBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val currentStreak = prefs.duolingoStreak
        if (currentStreak > 0) {
            dialogBinding.etStreakCount.setText(currentStreak.toString())
            dialogBinding.etStreakCount.setSelection(dialogBinding.etStreakCount.text.length)
        } else {
            dialogBinding.etStreakCount.setText("")
        }
        dialogBinding.switchPracticedToday.isChecked = prefs.isDuolingoPracticedToday

        dialogBinding.rowPracticedToday.setOnClickListener {
            dialogBinding.switchPracticedToday.toggle()
        }

        dialogBinding.rowAutoSyncAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Notification Access settings", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnOpenDuolingoApp.setOnClickListener {
            dialog.dismiss()
            openDuolingoApp()
        }

        dialogBinding.btnCancelDuolingo.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveDuolingo.setOnClickListener {
            val count = dialogBinding.etStreakCount.text.toString().toIntOrNull() ?: prefs.duolingoStreak
            prefs.duolingoStreak = count
            prefs.isDuolingoPracticedToday = dialogBinding.switchPracticedToday.isChecked
            renderDuolingoWidget()
            dialog.dismiss()
        }

        activeDialog = dialog
        dialog.show()
    }

    /**
     * Renders 0 to 10 home screen app slots vertically centered.
     */
    private fun renderHomeSlots() {
        val container = binding.slotsContainer
        val slotCount = prefs.homeSlotsCount
        if (slotCount == 0) {
            container.removeAllViews()
            return
        }

        val slotTextSize = when (prefs.textSize) {
            Prefs.TEXT_SIZE_SMALL -> 22f
            Prefs.TEXT_SIZE_LARGE -> 30f
            else -> 26f
        }

        val verticalPaddingDp = if (slotCount > 7) 6 else if (slotCount > 5) 8 else 12
        val density = resources.displayMetrics.density
        val padPx = (verticalPaddingDp * density).toInt()
        val horizPadPx = (24 * density).toInt()

        // Reuse existing views to eliminate view inflation churn on every onResume
        while (container.childCount > slotCount) {
            container.removeViewAt(container.childCount - 1)
        }
        val inflater = LayoutInflater.from(this)
        while (container.childCount < slotCount) {
            val slotBinding = ItemHomeSlotBinding.inflate(inflater, container, false)
            container.addView(slotBinding.root)
        }

        for (i in 0 until slotCount) {
            val slotRoot = container.getChildAt(i)
            val tvSlotName = slotRoot.findViewById<TextView>(R.id.tvSlotName) ?: continue
            val assignedKey = prefs.getHomeSlot(i)
            val assignedApp = appRepo.findApp(assignedKey)

            tvSlotName.textSize = slotTextSize
            tvSlotName.setPadding(horizPadPx, padPx, horizPadPx, padPx)

            if (assignedApp != null) {
                tvSlotName.text = assignedApp.label
                tvSlotName.alpha = 1.0f
                tvSlotName.setOnClickListener {
                    launchApp(assignedApp)
                }
            } else if (!assignedKey.isNullOrBlank()) {
                // If assigned app is not yet resolved, show its saved custom name or last known label
                val savedCustom = prefs.getCustomAppName(assignedKey)
                val fallbackText = savedCustom ?: assignedKey.substringAfterLast('.').substringAfter('/')
                tvSlotName.text = fallbackText
                tvSlotName.alpha = 1.0f
                tvSlotName.setOnClickListener {
                    val pkg = assignedKey.substringBefore('/')
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) startActivity(launchIntent)
                    else openSlotReassignPicker(i)
                }
            } else {
                tvSlotName.text = getString(R.string.slot_empty)
                tvSlotName.alpha = 0.4f
                tvSlotName.setOnClickListener {
                    openSlotReassignPicker(i)
                }
            }

            tvSlotName.setOnLongClickListener {
                vibrateTick()
                openSlotReassignPicker(i)
                true
            }
        }
    }

    /**
     * AppRepository listener callback. Delivers loaded apps off the main thread.
     */
    override fun onAppsLoaded(apps: List<AppModel>) {
        val hiddenKeys = prefs.hiddenPackages
        val visibleApps = apps.filterNot { hiddenKeys.contains(it.componentKey) }

        drawerAdapter.submitList(visibleApps, binding.etSearch.text?.toString().orEmpty())
        renderHomeSlots()
        updateShortcutsText()
    }

    fun openDrawer() {
        if (prefs.isLockoutActive) return
        if (binding.drawerContainer.isVisible) return

        binding.drawerContainer.alpha = 0f
        binding.drawerContainer.visibility = View.VISIBLE
        binding.drawerContainer.animate().alpha(1f).setDuration(120).start()

        if (prefs.autoOpenKeyboard) {
            binding.etSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun closeDrawer() {
        if (!binding.drawerContainer.isVisible) return

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.text?.clear()
        binding.etSearch.clearFocus()

        binding.drawerContainer.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                binding.drawerContainer.visibility = View.GONE
            }
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        mindfulTimer?.cancel()
        mindfulTimer = null
        stopLockoutTimer()
        try {
            unregisterReceiver(duolingoReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    // =========================================================================
    // PRO DEEP LOCKOUT (MONK MODE)
    // =========================================================================

    private var lockoutTimer: CountDownTimer? = null

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupLockoutUi() {
        binding.layoutLockoutMode.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN && event.rawY < 140) {
                collapseNotificationPanel()
                true
            } else {
                false
            }
        }

        binding.btnLockoutPhone.setOnClickListener {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL)
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(dialIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open dialer", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLockoutEmergencyExit.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Emergency Exit")
                .setMessage("Do you need to end this focus session early?")
                .setPositiveButton("End Session") { _, _ ->
                    prefs.stopLockout()
                    checkLockoutState()
                    Toast.makeText(this, "Focus session ended.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Keep Focusing", null)
                .show()
        }
    }

    private fun checkLockoutState() {
        if (prefs.isLockoutActive) {
            LockAccessibilityService.setLockoutModeActive(true)
            binding.layoutLockoutMode.isVisible = true
            binding.homeContainer.isVisible = false
            if (binding.drawerContainer.isVisible) {
                binding.drawerContainer.isVisible = false
            }
            applyLockoutStatusBar(true)
            startLockoutTimer()
        } else {
            LockAccessibilityService.setLockoutModeActive(false)
            stopLockoutTimer()
            applyLockoutStatusBar(false)
            binding.layoutLockoutMode.isVisible = false
            binding.homeContainer.isVisible = true
        }
    }

    private fun applyLockoutStatusBar(hide: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun collapseNotificationPanel() {
        LockAccessibilityService.collapseNotificationShade()
        collapseNotificationPanelFast()

        binding.root.postDelayed({
            if (prefs.isLockoutActive) {
                LockAccessibilityService.collapseNotificationShade()
                collapseNotificationPanelFast()
            }
        }, 60)
        binding.root.postDelayed({
            if (prefs.isLockoutActive) {
                LockAccessibilityService.collapseNotificationShade()
                collapseNotificationPanelFast()
            }
        }, 180)
    }

    private fun collapseNotificationPanelFast() {
        try {
            @Suppress("DEPRECATION")
            val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            sendBroadcast(closeIntent)
        } catch (e: Exception) {}

        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val collapseMethod = statusBarManager.getMethod("collapsePanels")
            collapseMethod.isAccessible = true
            collapseMethod.invoke(statusBarService)
        } catch (e: Exception) {
            try {
                val statusBarService = getSystemService("statusbar")
                val statusBarManager = Class.forName("android.app.StatusBarManager")
                val collapseMethod = statusBarManager.getMethod("collapse")
                collapseMethod.isAccessible = true
                collapseMethod.invoke(statusBarService)
            } catch (e2: Exception) {}
        }
    }

    private fun startLockoutTimer() {
        lockoutTimer?.cancel()
        val remainingMillis = prefs.getLockoutRemainingMillis()
        if (remainingMillis <= 0L) {
            prefs.stopLockout()
            checkLockoutState()
            return
        }

        updateLockoutDisplay(remainingMillis)

        lockoutTimer = object : CountDownTimer(remainingMillis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                updateLockoutDisplay(millisUntilFinished)
            }

            override fun onFinish() {
                prefs.stopLockout()
                checkLockoutState()
                vibrateCelebration()
                Toast.makeText(this@MainActivity, "Focus session complete! 🎉 Well done.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun stopLockoutTimer() {
        lockoutTimer?.cancel()
        lockoutTimer = null
    }

    private fun updateLockoutDisplay(millis: Long) {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        binding.tvLockoutCountdown.text = if (hours > 0) {
            String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun vibrateCelebration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 150, 80, 200), -1))
                } else {
                    vibrator.vibrate(400)
                }
            }
        } catch (e: Exception) {}
    }

    private var mindfulTimer: CountDownTimer? = null

    fun launchApp(app: AppModel) {
        // 1. App Screen-Time Limit Check (Pro Digital Detox)
        val limitMinutes = prefs.getAppScreenTimeLimit(app.packageName)
        if (prefs.isProUser && limitMinutes > 0 && !prefs.isAppSnoozed(app.packageName)) {
            if (UsageHelper.hasUsagePermission(this)) {
                val usedMinutes = UsageHelper.getTodayUsageMinutes(this, app.packageName)
                if (usedMinutes >= limitMinutes) {
                    showGentleLockoutDialog(app, usedMinutes, limitMinutes)
                    return
                }
            }
        }

        // 2. Mindful Delay Check (Pro Digital Detox)
        if (prefs.isMindfulApp(app.packageName)) {
            showMindfulDelayDialog(app)
            return
        }

        executeLaunch(app)
    }

    private fun showGentleLockoutDialog(app: AppModel, usedMinutes: Int, limitMinutes: Int) {
        val dialog = Dialog(this)
        val dialogBinding = DialogGentleLockoutBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.tvLockoutMessage.text = "You've used ${app.label} for ${usedMinutes}m today.\nYour daily limit is ${limitMinutes}m.\n\nTake a gentle break and recharge."

        dialogBinding.btnStepAway.setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, "Proud of you for stepping away! 🌿", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnSnoozeEmergency.setOnClickListener {
            dialog.dismiss()
            prefs.snoozeAppLimit(app.packageName, 5)
            Toast.makeText(this, "5 extra minutes granted. Use them mindfully!", Toast.LENGTH_SHORT).show()
            if (prefs.isMindfulApp(app.packageName)) {
                showMindfulDelayDialog(app)
            } else {
                executeLaunch(app)
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun executeLaunch(app: AppModel) {
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        try {
            launcherApps.startMainActivity(app.componentName, app.userHandle, null, null)
        } catch (e: Exception) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(app.packageName)
                if (intent != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Cannot launch app", Toast.LENGTH_SHORT).show()
                }
            } catch (e2: Exception) {
                Toast.makeText(this, "Launch error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMindfulDelayDialog(app: AppModel) {
        mindfulTimer?.cancel()

        val dialog = Dialog(this)
        val dialogBinding = DialogMindfulDelayBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val secondsTotal = prefs.mindfulDelaySeconds
        dialogBinding.tvMindfulMessage.text = "Opening ${app.label}.\nIs this intentional, or just a reflex?"
        dialogBinding.tvMindfulCountdown.text = secondsTotal.toString()
        dialogBinding.btnContinueMindful.text = "Continue in ${secondsTotal}s..."
        dialogBinding.btnContinueMindful.isEnabled = false
        dialogBinding.btnContinueMindful.alpha = 0.5f

        dialogBinding.btnCancelMindful.setOnClickListener {
            mindfulTimer?.cancel()
            dialog.dismiss()
            Toast.makeText(this, "Good job stepping away! 🌿", Toast.LENGTH_SHORT).show()
        }

        dialog.setOnDismissListener {
            mindfulTimer?.cancel()
            mindfulTimer = null
            if (activeDialog == dialog) {
                activeDialog = null
            }
        }

        mindfulTimer = object : CountDownTimer(secondsTotal * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = ((millisUntilFinished + 500L) / 1000L).toInt()
                if (secondsRemaining > 0) {
                    dialogBinding.tvMindfulCountdown.text = secondsRemaining.toString()
                    dialogBinding.btnContinueMindful.text = "Continue in ${secondsRemaining}s..."
                    vibrateTick()
                }
            }

            override fun onFinish() {
                dialogBinding.tvMindfulCountdown.text = "0"
                dialogBinding.btnContinueMindful.text = "Continue to ${app.label}"
                dialogBinding.btnContinueMindful.isEnabled = true
                dialogBinding.btnContinueMindful.alpha = 1.0f
                dialogBinding.btnContinueMindful.setTextColor(getThemeColor(R.attr.primaryTextColor))
                dialogBinding.btnContinueMindful.setOnClickListener {
                    dialog.dismiss()
                    executeLaunch(app)
                }
            }
        }.start()

        activeDialog = dialog
        dialog.show()
    }

    fun showProShowcaseDialog(reason: String? = null) {
        val dialog = Dialog(this)
        val dialogBinding = DialogKansoProBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val billing = BillingManager.getInstance(this)

        fun updateUi() {
            val isPro = prefs.isProUser
            if (isPro) {
                dialogBinding.tvDialogProBadge.text = "PRO"
                dialogBinding.tvDialogProBadge.setBackgroundResource(R.drawable.bg_pro_badge)
                dialogBinding.layoutProActive.visibility = View.VISIBLE
                dialogBinding.layoutPlans.visibility = View.GONE
            } else {
                dialogBinding.tvDialogProBadge.text = "FREE"
                dialogBinding.tvDialogProBadge.setBackgroundColor(Color.parseColor("#444444"))
                dialogBinding.layoutProActive.visibility = View.GONE
                dialogBinding.layoutPlans.visibility = View.VISIBLE
            }

            val prices = billing.getFormattedPrices()
            prices[BillingManager.PRODUCT_YEARLY]?.let { dialogBinding.tvPriceYearly.text = it }
            prices[BillingManager.PRODUCT_MONTHLY]?.let { dialogBinding.tvPriceMonthly.text = it }
            prices[BillingManager.PRODUCT_LIFETIME]?.let { dialogBinding.tvPriceLifetime.text = it }
        }

        updateUi()

        billing.setCallback(object : BillingManager.BillingCallback {
            override fun onProductsLoaded(prices: Map<String, String>) {
                updateUi()
            }

            override fun onPurchaseSuccess() {
                updateUi()
                renderHomeSlots()
                updateTextSizes()
                renderDuolingoWidget()
                loadTodayCalendarEvents()
                Toast.makeText(this@MainActivity, "Welcome to P-Sei Pro! ⭐ Enjoy intentional living.", Toast.LENGTH_LONG).show()
            }

            override fun onPurchaseFailed(errorMessage: String) {
                Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_SHORT).show()
            }
        })

        if (!reason.isNullOrBlank()) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnPlanYearly.setOnClickListener {
            billing.launchBillingFlow(this, BillingManager.PRODUCT_YEARLY)
        }

        dialogBinding.btnPlanMonthly.setOnClickListener {
            billing.launchBillingFlow(this, BillingManager.PRODUCT_MONTHLY)
        }

        dialogBinding.btnPlanLifetime.setOnClickListener {
            billing.launchBillingFlow(this, BillingManager.PRODUCT_LIFETIME)
        }

        dialogBinding.btnRestorePurchases.setOnClickListener {
            Toast.makeText(this, "Checking Google Play for previous purchases…", Toast.LENGTH_SHORT).show()
            billing.queryActivePurchases { isPro ->
                updateUi()
                renderHomeSlots()
                updateTextSizes()
                renderDuolingoWidget()
                loadTodayCalendarEvents()
                if (isPro) {
                    Toast.makeText(this, "Pro purchase restored! 🎉", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No active subscription found for this Google account.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.setOnDismissListener {
            billing.setCallback(null)
        }

        dialogBinding.btnCloseProDialog.setOnClickListener {
            dialog.dismiss()
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun handleBottomShortcut(shortcutKey: String, isPhone: Boolean) {
        if (isPhone && shortcutKey == Prefs.SPECIAL_SHORTCUT_PHONE) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL))
            } catch (e: Exception) {
                Toast.makeText(this, "No dialer app found", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!isPhone && shortcutKey == Prefs.SPECIAL_SHORTCUT_CAMERA) {
            try {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            } catch (e: Exception) {
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val app = appRepo.findApp(shortcutKey)
        if (app != null) {
            launchApp(app)
        } else {
            Toast.makeText(this, getString(R.string.none_assigned), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCustomShortcut(shortcutKey: String?) {
        if (shortcutKey.isNullOrBlank()) return
        val app = appRepo.findApp(shortcutKey)
        if (app != null) {
            launchApp(app)
        }
    }

    /**
     * Double tap on empty space to lock screen.
     * Uses Accessibility Service by default (preserves fingerprint unlock on vivo Y53s / Funtouch OS).
     */
    private fun handleDoubleTapLock() {
        vibrateTick()
        if (prefs.lockMethod == Prefs.LOCK_METHOD_DEVICE_ADMIN) {
            if (LauncherAdminReceiver.isActive(this)) {
                LauncherAdminReceiver.lockScreen(this)
            } else {
                Toast.makeText(this, R.string.device_admin_prompt, Toast.LENGTH_LONG).show()
                LauncherAdminReceiver.requestAdmin(this)
            }
        } else {
            if (LockAccessibilityService.isServiceRunning) {
                LockAccessibilityService.lockScreen()
            } else {
                Toast.makeText(this, R.string.accessibility_prompt, Toast.LENGTH_LONG).show()
                LockAccessibilityService.openSettings(this)
            }
        }
    }

    /**
     * Swipe down to expand the notification shade.
     */
    private fun handleSwipeDownNotifications() {
        if (LockAccessibilityService.isServiceRunning) {
            LockAccessibilityService.expandNotifications()
            return
        }

        // Fallback to statusBarManager reflection
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManagerClass = Class.forName("android.app.StatusBarManager")
            val expandNotifications: Method = statusBarManagerClass.getMethod("expandNotificationsPanel")
            expandNotifications.invoke(statusBarService)
        } catch (e: Exception) {
            // If reflection is blocked by OS restrictions, guide user to enable Accessibility Service
            Toast.makeText(this, R.string.accessibility_prompt, Toast.LENGTH_SHORT).show()
            LockAccessibilityService.openSettings(this)
        }
    }

    /**
     * Long-press menu on an app in the drawer:
     * Rename, Hide, App info, Uninstall.
     */
    private fun showAppActionsDialog(app: AppModel) {
        val dialog = Dialog(this)
        val dialogBinding = DialogAppActionsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.tvActionAppTitle.text = app.label

        // 1. Rename
        dialogBinding.actionRename.setOnClickListener {
            dialog.dismiss()
            showRenameDialog(app)
        }

        // 2. Mindful Delay (Pro Digital Detox)
        val isCurrentlyMindful = prefs.mindfulPackages.contains(app.packageName)
        dialogBinding.actionMindfulDelay.text = if (isCurrentlyMindful) {
            "Remove Mindful Delay 🧘"
        } else {
            "Mindful Delay (Pause & Breathe) 🧘"
        }
        dialogBinding.actionMindfulDelay.setOnClickListener {
            dialog.dismiss()
            if (!prefs.isProUser) {
                showProShowcaseDialog("Mindful Delay is a P-Sei Pro feature. Take control of distracting apps with intentional breathing breaks.")
                return@setOnClickListener
            }
            val nowMindful = prefs.toggleMindfulApp(app.packageName)
            if (nowMindful) {
                Toast.makeText(this, "${app.label} will now require a mindful pause 🧘", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Mindful pause removed for ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Screen-Time Limit (Pro Digital Detox)
        val currentLimit = prefs.getAppScreenTimeLimit(app.packageName)
        dialogBinding.actionScreenTimeLimit.text = if (currentLimit > 0) {
            "Daily Limit: ${currentLimit}m ⏳"
        } else {
            "Daily Screen-Time Limit ⏳"
        }
        dialogBinding.actionScreenTimeLimit.setOnClickListener {
            dialog.dismiss()
            if (!prefs.isProUser) {
                showProShowcaseDialog("App Screen-Time Limits with gentle lockouts are a P-Sei Pro feature.")
                return@setOnClickListener
            }
            if (!UsageHelper.hasUsagePermission(this)) {
                showUsageAccessPromptDialog()
            } else {
                showSetLimitDialog(app)
            }
        }

        // 4. Hide
        dialogBinding.actionHide.setOnClickListener {
            dialog.dismiss()
            prefs.setAppHidden(app.componentKey, true)
            appRepo.reloadAppsAsync()
            Toast.makeText(this, R.string.app_hidden_toast, Toast.LENGTH_SHORT).show()
        }

        // 3. App Info
        dialogBinding.actionInfo.setOnClickListener {
            dialog.dismiss()
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open App Info", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. Uninstall
        dialogBinding.actionUninstall.setOnClickListener {
            dialog.dismiss()
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not uninstall app", Toast.LENGTH_SHORT).show()
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun showRenameDialog(app: AppModel) {
        val dialog = Dialog(this)
        val dialogBinding = DialogRenameBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.etRename.setText(app.label)
        dialogBinding.etRename.setSelection(dialogBinding.etRename.text.length)

        dialogBinding.btnResetRename.setOnClickListener {
            dialog.dismiss()
            appRepo.updateCustomLabel(app.componentKey, null)
        }

        dialogBinding.btnCancelRename.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveRename.setOnClickListener {
            val newName = dialogBinding.etRename.text.toString().trim()
            dialog.dismiss()
            appRepo.updateCustomLabel(
                app.componentKey,
                if (newName.isEmpty() || newName == app.originalLabel) null else newName
            )
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun showSetLimitDialog(app: AppModel) {
        val currentLimit = prefs.getAppScreenTimeLimit(app.packageName)
        val limits = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val options = arrayOf(
            "None (No limit)",
            "15 minutes",
            "30 minutes",
            "45 minutes",
            "1 hour (60m)",
            "1.5 hours (90m)",
            "2 hours (120m)"
        )
        val currentIdx = limits.indexOf(currentLimit).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("Daily Limit for ${app.label} ⏳")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val chosen = limits[which]
                prefs.setAppScreenTimeLimit(app.packageName, chosen)
                if (chosen > 0) {
                    Toast.makeText(this, "Daily limit set to ${chosen}m for ${app.label}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Daily limit removed for ${app.label}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUsageAccessPromptDialog() {
        AlertDialog.Builder(this)
            .setTitle("Usage Access Required ⏳")
            .setMessage("To track daily screen time and enforce gentle lockouts, P-Sei needs Usage Access permission.")
            .setPositiveButton("Open Settings") { _, _ ->
                UsageHelper.openUsageAccessSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openSlotReassignPicker(slotIndex: Int) {
        val dialog = Dialog(this, android.R.style.Theme_NoTitleBar)
        val pickerBinding = DialogAppPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = getString(R.string.reassign_slot) + " " + (slotIndex + 1)
        pickerBinding.btnClearOption.isVisible = true
        pickerBinding.btnClearOption.setOnClickListener {
            prefs.setHomeSlot(slotIndex, null)
            renderHomeSlots()
            dialog.dismiss()
        }

        val pickerAdapter = AppListAdapter(
            onAppClick = { chosenApp ->
                prefs.setHomeSlot(slotIndex, chosenApp.componentKey)
                renderHomeSlots()
                dialog.dismiss()
            }
        )

        pickerBinding.rvPickerApps.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pickerAdapter
            setHasFixedSize(true)
        }

        val allApps = appRepo.getCachedApps().filterNot { prefs.isAppHidden(it.componentKey) }
        pickerAdapter.submitList(allApps)

        pickerBinding.etPickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pickerAdapter.filter(s?.toString().orEmpty())
            }
        })

        activeDialog = dialog
        dialog.show()
    }

    private fun openAppPickerForShortcut(isBottomLeft: Boolean) {
        val dialog = Dialog(this, android.R.style.Theme_NoTitleBar)
        val pickerBinding = DialogAppPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = if (isBottomLeft) {
            getString(R.string.pref_shortcut_bottom_left)
        } else {
            getString(R.string.pref_shortcut_bottom_right)
        }

        pickerBinding.btnClearOption.text = getString(R.string.reset_default)
        pickerBinding.btnClearOption.setOnClickListener {
            if (isBottomLeft) {
                prefs.shortcutBottomLeft = Prefs.SPECIAL_SHORTCUT_PHONE
            } else {
                prefs.shortcutBottomRight = Prefs.SPECIAL_SHORTCUT_CAMERA
            }
            updateShortcutsText()
            dialog.dismiss()
        }

        val pickerAdapter = AppListAdapter(
            onAppClick = { chosenApp ->
                if (isBottomLeft) {
                    prefs.shortcutBottomLeft = chosenApp.componentKey
                } else {
                    prefs.shortcutBottomRight = chosenApp.componentKey
                }
                updateShortcutsText()
                dialog.dismiss()
            }
        )

        pickerBinding.rvPickerApps.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pickerAdapter
            setHasFixedSize(true)
        }

        val allApps = appRepo.getCachedApps().filterNot { prefs.isAppHidden(it.componentKey) }
        pickerAdapter.submitList(allApps)

        pickerBinding.etPickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pickerAdapter.filter(s?.toString().orEmpty())
            }
        })

        activeDialog = dialog
        dialog.show()
    }

    private fun vibrateTick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun getThemeColor(attrId: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }
}

