package com.psei.launcher

import android.app.Dialog
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.psei.launcher.billing.BillingManager
import com.psei.launcher.data.AppModel
import com.psei.launcher.data.AppRepository
import com.psei.launcher.data.Prefs
import com.psei.launcher.data.UsageHelper
import com.psei.launcher.databinding.ActivitySettingsBinding
import com.psei.launcher.databinding.DialogAppPickerBinding
import com.psei.launcher.databinding.DialogKansoProBinding
import com.psei.launcher.databinding.DialogSecretVipBinding
import com.psei.launcher.service.LauncherAdminReceiver
import com.psei.launcher.service.LockAccessibilityService
import com.psei.launcher.ui.AppListAdapter
import com.psei.launcher.ui.InsetsUtils

/**
 * Text-only minimalist settings screen.
 * Persists all configuration with SharedPreferences.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var appRepo: AppRepository
    private var currentAppliedTheme: String = ""

    private val hiddenAppsAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startActivity(Intent(this, HiddenAppsActivity::class.java))
        } else {
            Toast.makeText(this, "Authentication required to view hidden apps", Toast.LENGTH_SHORT).show()
        }
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)

        appRepo = AppRepository.getInstance(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        setupListeners()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupInsets() {
        InsetsUtils.applySystemWindowInsets(
            topView = binding.settingsHeader,
            bottomView = binding.settingsContent
        )
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Secret VIP Easter Egg: Tap "Settings" title 5 times
        binding.tvSettingsTitle.setOnClickListener {
            handleSecretEasterEggTap()
        }

        // P-Sei Pro Showcase Card
        binding.cardPSeiPro.setOnClickListener {
            showProShowcaseDialog()
        }

        // Set as Default Launcher
        binding.rowDefaultLauncher.setOnClickListener {
            openDefaultLauncherSettings()
        }

        // Home Slots Count
        binding.rowHomeSlotsCount.setOnClickListener {
            showSlotsCountDialog()
        }

        // Theme
        binding.rowTheme.setOnClickListener {
            showThemeDialog()
        }

        // Text Size
        binding.rowTextSize.setOnClickListener {
            showTextSizeDialog()
        }

        // Widgets: Calendar Toggle (Pro)
        binding.switchCalendarWidget.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !prefs.isProUser) {
                buttonView.isChecked = false
                showProShowcaseDialog("Google Calendar events widget is a P-Sei Pro feature.")
                return@setOnCheckedChangeListener
            }
            prefs.showCalendarWidget = isChecked
            updateUiState()
        }
        binding.rowCalendarWidget.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Google Calendar events widget is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            binding.switchCalendarWidget.toggle()
        }

        // Widgets: Duolingo Toggle (Pro)
        binding.switchDuolingoWidget.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !prefs.isProUser) {
                buttonView.isChecked = false
                showProShowcaseDialog("Duolingo streak widget is a P-Sei Pro feature.")
                return@setOnCheckedChangeListener
            }
            prefs.showDuolingoWidget = isChecked
            updateUiState()
        }
        binding.rowDuolingoWidget.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Duolingo streak widget is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            binding.switchDuolingoWidget.toggle()
        }

        // Widgets: Duolingo Streak Editor (Pro)
        binding.rowDuolingoStreak.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Duolingo streak tracking is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            showDuolingoEditDialog()
        }

        // Widgets: Duolingo Auto-Sync Notification Settings (Pro)
        binding.rowDuolingoAutoSync.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Duolingo notification auto-sync is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Notification Access settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Widgets: Daily Focus / Intent
        binding.switchDailyIntent.setOnCheckedChangeListener { _, isChecked ->
            prefs.showDailyIntent = isChecked
            updateUiState()
        }
        binding.rowDailyIntent.setOnClickListener {
            binding.switchDailyIntent.toggle()
        }

        // Widgets: Daily Screen-Time Glance
        binding.switchScreenTimeGlance.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !UsageHelper.hasUsagePermission(this)) {
                Toast.makeText(this, R.string.screen_time_permission_toast, Toast.LENGTH_LONG).show()
                UsageHelper.openUsageAccessSettings(this)
            }
            prefs.showScreenTimeGlance = isChecked
            updateUiState()
        }
        binding.rowScreenTimeGlance.setOnClickListener {
            binding.switchScreenTimeGlance.toggle()
        }

        // Auto Open Keyboard
        binding.switchAutoKeyboard.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoOpenKeyboard = isChecked
        }
        binding.rowAutoKeyboard.setOnClickListener {
            binding.switchAutoKeyboard.toggle()
        }

        // Auto Launch Single Result (Pro)
        binding.switchAutoLaunch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !prefs.isProUser) {
                buttonView.isChecked = false
                showProShowcaseDialog("Auto-launch single search result is a P-Sei Pro feature.")
                return@setOnCheckedChangeListener
            }
            prefs.autoLaunchSingleResult = isChecked
            updateUiState()
        }
        binding.rowAutoLaunch.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Auto-launch single search result is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            binding.switchAutoLaunch.toggle()
        }

        // Manage Hidden Apps
        binding.rowHiddenApps.setOnClickListener {
            if (prefs.isBiometricLockEnabled && prefs.isProUser) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (km?.isDeviceSecure == true) {
                    val intent = km.createConfirmDeviceCredentialIntent(
                        "Hidden Apps",
                        "Authenticate with fingerprint, face, or PIN"
                    )
                    if (intent != null) {
                        hiddenAppsAuthLauncher.launch(intent)
                        return@setOnClickListener
                    }
                }
            }
            startActivity(Intent(this, HiddenAppsActivity::class.java))
        }

        // Biometric Lock Toggle (Pro)
        binding.switchBiometricLock.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !prefs.isProUser) {
                buttonView.isChecked = false
                showProShowcaseDialog("Biometric Lock on Hidden Apps is a P-Sei Pro feature.")
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (km?.isDeviceSecure != true) {
                    buttonView.isChecked = false
                    Toast.makeText(this, "Please set a PIN, pattern, or fingerprint in Android Settings first", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
            }
            prefs.isBiometricLockEnabled = isChecked
            updateUiState()
        }
        binding.rowBiometricLock.setOnClickListener {
            binding.switchBiometricLock.toggle()
        }

        // Mindful Delay Toggle (Pro)
        binding.switchMindfulDelay.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !prefs.isProUser) {
                buttonView.isChecked = false
                showProShowcaseDialog("Mindful Delay is a P-Sei Pro feature.")
                return@setOnCheckedChangeListener
            }
            prefs.isMindfulDelayEnabled = isChecked
            updateUiState()
        }
        binding.rowMindfulDelay.setOnClickListener {
            binding.switchMindfulDelay.toggle()
        }

        // Mindful Delay Duration (Pro)
        binding.rowMindfulDuration.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Custom pause duration is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            showMindfulDurationDialog()
        }

        // Manage Mindful Apps (Pro)
        binding.rowMindfulApps.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("Managing mindful apps is a P-Sei Pro feature.")
                return@setOnClickListener
            }
            showMindfulAppsDialog()
        }

        // App Screen-Time Limits (Pro)
        binding.rowAppLimits.setOnClickListener {
            if (!prefs.isProUser) {
                showProShowcaseDialog("App Screen-Time Limits with gentle lockouts are a P-Sei Pro feature.")
                return@setOnClickListener
            }
            if (!UsageHelper.hasUsagePermission(this)) {
                showUsageAccessPromptDialog()
            } else {
                showAppLimitsManagerDialog()
            }
        }

        // Lock Method
        binding.rowLockMethod.setOnClickListener {
            showLockMethodDialog()
        }

        binding.btnDisableAccessibility.setOnClickListener {
            val disabled = LockAccessibilityService.disableService()
            prefs.lockMethod = Prefs.LOCK_METHOD_DEVICE_ADMIN
            if (!LauncherAdminReceiver.isActive(this)) {
                LauncherAdminReceiver.requestAdmin(this)
            }
            if (disabled) {
                Toast.makeText(this, "Accessibility disabled. Google Pay is now safe to use! 💳", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Please turn off P-Sei in Accessibility settings to unblock Google Pay.", Toast.LENGTH_LONG).show()
                LockAccessibilityService.openSettings(this)
            }
            updateUiState()
        }

        // Bottom Left Shortcut
        binding.rowShortcutBottomLeft.setOnClickListener {
            openShortcutPicker(isBottomLeft = true)
        }

        // Bottom Right Shortcut
        binding.rowShortcutBottomRight.setOnClickListener {
            openShortcutPicker(isBottomLeft = false)
        }

        // Swipe Left Shortcut
        binding.rowShortcutSwipeLeft.setOnClickListener {
            openSwipeShortcutPicker(isSwipeLeft = true)
        }

        // Swipe Right Shortcut
        binding.rowShortcutSwipeRight.setOnClickListener {
            openSwipeShortcutPicker(isSwipeLeft = false)
        }

        // vivo / Funtouch OS Help
        binding.rowFuntouchHelp.setOnClickListener {
            showFuntouchHelpDialog()
        }
    }

    private fun updateUiState() {
        // Pro Status Badge
        if (prefs.isProUser) {
            binding.tvProBadge.text = "PRO"
            binding.tvProBadge.setBackgroundResource(R.drawable.bg_pro_badge)
            binding.tvProSubtitle.text = "All Pro features unlocked (Preview Mode) ⭐"
        } else {
            binding.tvProBadge.text = "FREE"
            binding.tvProBadge.setBackgroundColor(Color.parseColor("#444444"))
            binding.tvProSubtitle.text = "Widgets, Mindful Delay, Screen Limits, Auto-Launch, Pro Themes & Biometrics"
        }

        binding.tvHomeSlotsSummary.text = getString(R.string.pref_home_slots_count_summary, prefs.homeSlotsCount)

        binding.tvThemeSummary.text = if (!prefs.isProUser) {
            getString(R.string.pref_theme_dark)
        } else {
            when (prefs.theme) {
                Prefs.THEME_LIGHT -> "Light (Clean White) ⭐ PRO"
                Prefs.THEME_SYSTEM -> "System default ⭐ PRO"
                Prefs.THEME_SEPIA -> "Warm Paper (Sepia / E-Ink) ⭐ PRO"
                Prefs.THEME_FOREST -> "Forest Night (Botanical Dark) ⭐ PRO"
                Prefs.THEME_CHARCOAL -> "Charcoal Slate ⭐ PRO"
                Prefs.THEME_CYBER -> "Cyberpunk Midnight ⭐ PRO"
                else -> getString(R.string.pref_theme_dark)
            }
        }

        binding.tvTextSizeSummary.text = when (prefs.textSize) {
            Prefs.TEXT_SIZE_SMALL -> getString(R.string.pref_text_size_small)
            Prefs.TEXT_SIZE_LARGE -> getString(R.string.pref_text_size_large)
            else -> getString(R.string.pref_text_size_medium)
        }

        binding.switchAutoKeyboard.isChecked = prefs.autoOpenKeyboard
        binding.switchAutoLaunch.isChecked = prefs.isProUser && prefs.autoLaunchSingleResult
        binding.tvHiddenAppsSummary.text = getString(R.string.pref_manage_hidden_summary, prefs.hiddenPackages.size)

        // Widgets state (Pro)
        binding.switchCalendarWidget.isChecked = prefs.isProUser && prefs.showCalendarWidget
        binding.switchDuolingoWidget.isChecked = prefs.isProUser && prefs.showDuolingoWidget
        val statusText = if (prefs.isDuolingoPracticedToday) "Practiced today (Streak safe)" else "Streak at risk (Not practiced today)"
        binding.tvDuolingoStreakSummary.text = "${prefs.duolingoStreak} days • $statusText"
        binding.switchDailyIntent.isChecked = prefs.showDailyIntent
        binding.switchScreenTimeGlance.isChecked = prefs.showScreenTimeGlance

        // Digital Detox (Pro) state
        binding.switchMindfulDelay.isChecked = prefs.isMindfulDelayEnabled
        binding.tvMindfulDurationSummary.text = "${prefs.mindfulDelaySeconds} seconds"
        val installedApps = appRepo.getCachedApps()
        if (installedApps.isNotEmpty()) {
            val installedPackageNames = installedApps.map { it.packageName }.toSet()
            val validMindful = prefs.mindfulPackages.filter { installedPackageNames.contains(it) }.toSet()
            if (validMindful != prefs.mindfulPackages) {
                prefs.mindfulPackages = validMindful
            }
        }
        val mindfulCount = prefs.mindfulPackages.size
        binding.tvMindfulAppsSummary.text = if (mindfulCount == 1) {
            "1 app configured with pause"
        } else {
            "$mindfulCount apps configured with pause"
        }
        val configuredLimitsCount = prefs.getAllConfiguredLimits().size
        binding.tvAppLimitsSummary.text = if (configuredLimitsCount > 0) {
            "$configuredLimitsCount apps with daily caps"
        } else {
            "In-app daily usage caps with gentle lockouts"
        }
        binding.switchBiometricLock.isChecked = prefs.isBiometricLockEnabled

        val isAccessibilityActive = LockAccessibilityService.isServiceRunning
        binding.cardAccessibilityWarning.isVisible = isAccessibilityActive

        binding.tvLockMethodSummary.text = if (prefs.lockMethod == Prefs.LOCK_METHOD_DEVICE_ADMIN) {
            "Device Administrator (Recommended • Google Pay Safe)"
        } else {
            if (isAccessibilityActive) {
                "Accessibility Service (⚠️ Active • Blocks Google Pay)"
            } else {
                "Accessibility Service (⚠️ Incompatible with Google Pay)"
            }
        }

        // Shortcuts
        val leftKey = prefs.shortcutBottomLeft
        binding.tvShortcutBottomLeft.text = if (leftKey == Prefs.SPECIAL_SHORTCUT_PHONE) {
            getString(R.string.default_phone_name)
        } else {
            appRepo.findApp(leftKey)?.label ?: getString(R.string.default_phone_name)
        }

        val rightKey = prefs.shortcutBottomRight
        binding.tvShortcutBottomRight.text = if (rightKey == Prefs.SPECIAL_SHORTCUT_CAMERA) {
            getString(R.string.default_camera_name)
        } else {
            appRepo.findApp(rightKey)?.label ?: getString(R.string.default_camera_name)
        }

        val swipeLeftKey = prefs.shortcutSwipeLeft
        binding.tvShortcutSwipeLeft.text = if (swipeLeftKey.isNullOrBlank()) {
            getString(R.string.none_assigned)
        } else {
            appRepo.findApp(swipeLeftKey)?.label ?: getString(R.string.none_assigned)
        }

        val swipeRightKey = prefs.shortcutSwipeRight
        binding.tvShortcutSwipeRight.text = if (swipeRightKey.isNullOrBlank()) {
            getString(R.string.none_assigned)
        } else {
            appRepo.findApp(swipeRightKey)?.label ?: getString(R.string.none_assigned)
        }
    }

    private fun openDefaultLauncherSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    startActivity(intent)
                    return
                } else {
                    Toast.makeText(this, "P-Sei is already your default launcher", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }

        // Fallback to Settings.ACTION_HOME_SETTINGS
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Unable to open Home Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSlotsCountDialog() {
        val options = (0..10).map { count ->
            if (count > 6) "$count apps ⭐ PRO" else "$count apps"
        }.toTypedArray()
        val currentIndex = prefs.homeSlotsCount.coerceIn(0, 10)

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_home_slots_count)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                if (which > 6 && !prefs.isProUser) {
                    dialog.dismiss()
                    showProShowcaseDialog("Pinning up to 10 apps on your home screen is a P-Sei Pro feature.")
                    return@setSingleChoiceItems
                }
                prefs.homeSlotsCount = which
                updateUiState()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.pref_theme_dark) + " (Free)",
            "Light (Clean White) ⭐ PRO",
            "System default ⭐ PRO",
            "Warm Paper (Sepia / E-Ink) ⭐ PRO",
            "Forest Night (Botanical Dark) ⭐ PRO",
            "Charcoal Slate (Soft Slate) ⭐ PRO",
            "Cyberpunk Midnight (Neon Cyan) ⭐ PRO"
        )
        val currentIndex = if (!prefs.isProUser) 0 else when (prefs.theme) {
            Prefs.THEME_LIGHT -> 1
            Prefs.THEME_SYSTEM -> 2
            Prefs.THEME_SEPIA -> 3
            Prefs.THEME_FOREST -> 4
            Prefs.THEME_CHARCOAL -> 5
            Prefs.THEME_CYBER -> 6
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_theme)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                if (which != 0 && !prefs.isProUser) {
                    dialog.dismiss()
                    showProShowcaseDialog("Dark AMOLED is the free theme. Light mode, System default, and aesthetic themes are part of P-Sei Pro.")
                    return@setSingleChoiceItems
                }
                val newTheme = when (which) {
                    1 -> Prefs.THEME_LIGHT
                    2 -> Prefs.THEME_SYSTEM
                    3 -> Prefs.THEME_SEPIA
                    4 -> Prefs.THEME_FOREST
                    5 -> Prefs.THEME_CHARCOAL
                    6 -> Prefs.THEME_CYBER
                    else -> Prefs.THEME_DARK
                }
                prefs.theme = newTheme
                when (newTheme) {
                    Prefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    Prefs.THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                updateUiState()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMindfulDurationDialog() {
        val durations = intArrayOf(3, 5, 10, 15)
        val options = arrayOf(
            "3 seconds (Quick reflection)",
            "5 seconds (Recommended)",
            "10 seconds (Deep pause)",
            "15 seconds (Intense detox)"
        )
        val currentIdx = durations.indexOf(prefs.mindfulDelaySeconds).let { if (it >= 0) it else 1 }

        AlertDialog.Builder(this)
            .setTitle("Pause Duration 🧘")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                prefs.mindfulDelaySeconds = durations[which]
                updateUiState()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMindfulAppsDialog() {
        val allApps = appRepo.getCachedApps().sortedBy { it.label.lowercase() }
        val installedPackageNames = allApps.map { it.packageName }.toSet()
        // Prune any stale or uninstalled packages
        val validCurrent = prefs.mindfulPackages.filter { installedPackageNames.contains(it) }.toSet()
        if (validCurrent != prefs.mindfulPackages) {
            prefs.mindfulPackages = validCurrent
        }

        val labels = allApps.map { it.label }.toTypedArray()
        val checked = allApps.map { validCurrent.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select Mindful Apps 🧘")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val app = allApps[which]
                val current = prefs.mindfulPackages.toMutableSet()
                if (isChecked) {
                    current.add(app.packageName)
                } else {
                    current.remove(app.packageName)
                }
                prefs.mindfulPackages = current
            }
            .setPositiveButton("Done") { _, _ ->
                updateUiState()
            }
            .show()
    }

    private fun showProShowcaseDialog(reason: String? = null) {
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
                updateUiState()
                Toast.makeText(this@SettingsActivity, "Welcome to P-Sei Pro! ⭐ Enjoy intentional living.", Toast.LENGTH_LONG).show()
            }

            override fun onPurchaseFailed(errorMessage: String) {
                Toast.makeText(this@SettingsActivity, errorMessage, Toast.LENGTH_SHORT).show()
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
                updateUiState()
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

        dialog.show()
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

    private fun showAppLimitsManagerDialog() {
        val dialog = Dialog(this, android.R.style.Theme_NoTitleBar)
        val pickerBinding = DialogAppPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = "App Screen-Time Limits ⏳"
        pickerBinding.btnClearOption.isVisible = false

        val pickerAdapter = AppListAdapter(
            onAppClick = { chosenApp ->
                showSetAppLimitDialog(chosenApp) {
                    dialog.dismiss()
                }
            }
        )

        pickerBinding.rvPickerApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
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

        dialog.show()
    }

    private fun showSetAppLimitDialog(app: AppModel, onComplete: (() -> Unit)? = null) {
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
                updateUiState()
                if (chosen > 0) {
                    Toast.makeText(this, "Daily limit set to ${chosen}m for ${app.label}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Daily limit removed for ${app.label}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
                onComplete?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTextSizeDialog() {
        val options = arrayOf(
            getString(R.string.pref_text_size_small),
            getString(R.string.pref_text_size_medium),
            getString(R.string.pref_text_size_large)
        )
        val currentIndex = when (prefs.textSize) {
            Prefs.TEXT_SIZE_SMALL -> 0
            Prefs.TEXT_SIZE_LARGE -> 2
            else -> 1
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_text_size)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val newSize = when (which) {
                    0 -> Prefs.TEXT_SIZE_SMALL
                    2 -> Prefs.TEXT_SIZE_LARGE
                    else -> Prefs.TEXT_SIZE_MEDIUM
                }
                prefs.textSize = newSize
                updateUiState()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLockMethodDialog() {
        val options = arrayOf(
            "Device Administrator (Recommended • Google Pay & Banking Safe)",
            "Accessibility Service (⚠️ Incompatible with Google Pay & UPI)"
        )
        val currentIndex = if (prefs.lockMethod == Prefs.LOCK_METHOD_DEVICE_ADMIN) 0 else 1

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_lock_method)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                if (which == 0) {
                    prefs.lockMethod = Prefs.LOCK_METHOD_DEVICE_ADMIN
                    if (LockAccessibilityService.isServiceRunning) {
                        LockAccessibilityService.disableService()
                    }
                    if (!LauncherAdminReceiver.isActive(this)) {
                        Toast.makeText(this, R.string.device_admin_prompt, Toast.LENGTH_LONG).show()
                        LauncherAdminReceiver.requestAdmin(this)
                    }
                    updateUiState()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ Google Pay & UPI Notice")
                        .setMessage("Enabling Accessibility Service may cause Google Pay, PhonePe, and other UPI banking apps in India to block payments for security.\n\nDo you still want to enable Accessibility?")
                        .setPositiveButton("Enable Anyway") { _, _ ->
                            prefs.lockMethod = Prefs.LOCK_METHOD_ACCESSIBILITY
                            if (!LockAccessibilityService.isServiceRunning) {
                                Toast.makeText(this, R.string.accessibility_prompt, Toast.LENGTH_LONG).show()
                                LockAccessibilityService.openSettings(this)
                            }
                            updateUiState()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            prefs.lockMethod = Prefs.LOCK_METHOD_DEVICE_ADMIN
                            updateUiState()
                        }
                        .show()
                }
                updateUiState()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDuolingoEditDialog() {
        val dialog = Dialog(this)
        val dialogBinding = com.psei.launcher.databinding.DialogDuolingoEditBinding.inflate(layoutInflater)
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
            val launchIntent = packageManager.getLaunchIntentForPackage("com.duolingo")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, R.string.duolingo_not_installed, Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnCancelDuolingo.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveDuolingo.setOnClickListener {
            val count = dialogBinding.etStreakCount.text.toString().toIntOrNull() ?: prefs.duolingoStreak
            prefs.duolingoStreak = count
            prefs.isDuolingoPracticedToday = dialogBinding.switchPracticedToday.isChecked
            updateUiState()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openShortcutPicker(isBottomLeft: Boolean) {
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
            updateUiState()
            dialog.dismiss()
        }

        val pickerAdapter = AppListAdapter(
            onAppClick = { chosenApp ->
                if (isBottomLeft) {
                    prefs.shortcutBottomLeft = chosenApp.componentKey
                } else {
                    prefs.shortcutBottomRight = chosenApp.componentKey
                }
                updateUiState()
                dialog.dismiss()
            }
        )

        pickerBinding.rvPickerApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
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

        dialog.show()
    }

    private fun openSwipeShortcutPicker(isSwipeLeft: Boolean) {
        val dialog = Dialog(this, android.R.style.Theme_NoTitleBar)
        val pickerBinding = DialogAppPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = if (isSwipeLeft) {
            getString(R.string.pref_shortcut_swipe_left)
        } else {
            getString(R.string.pref_shortcut_swipe_right)
        }

        pickerBinding.btnClearOption.text = getString(R.string.none_assigned)
        pickerBinding.btnClearOption.setOnClickListener {
            if (isSwipeLeft) {
                prefs.shortcutSwipeLeft = null
            } else {
                prefs.shortcutSwipeRight = null
            }
            updateUiState()
            dialog.dismiss()
        }

        val pickerAdapter = AppListAdapter(
            onAppClick = { chosenApp ->
                if (isSwipeLeft) {
                    prefs.shortcutSwipeLeft = chosenApp.componentKey
                } else {
                    prefs.shortcutSwipeRight = chosenApp.componentKey
                }
                updateUiState()
                dialog.dismiss()
            }
        )

        pickerBinding.rvPickerApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
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

        dialog.show()
    }

    /**
     * vivo Funtouch OS 11.1 Stability Guide Dialog.
     * Details steps to prevent Funtouch OS's aggressive background killer from killing launcher
     * or resetting the default home app.
     */
    private fun showFuntouchHelpDialog() {
        val message = """
            vivo / Funtouch OS 11.1 Stability Tips:

            1. Enable Autostart:
            Go to Settings -> Applications and Permissions -> Autostart (or open iManager -> App manager -> Autostart) and turn ON P-Sei.

            2. Battery Optimization:
            Go to Settings -> Battery -> High background power consumption, and allow P-Sei to continue running.

            3. Set as Default Launcher:
            Go to Settings -> Applications and Permissions -> Permission management -> Default app settings -> Home screen -> select P-Sei.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_funtouch_help)
            .setMessage(message)
            .setPositiveButton("Open Autostart Settings") { _, _ ->
                tryOpenAutostartSettings()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun tryOpenAutostartSettings() {
        // Try vivo proprietary autostart intent first
        val vivoIntents = listOf(
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        for (intent in vivoIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try next intent
            }
        }

        // Generic fallback to app details settings
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open system settings", Toast.LENGTH_SHORT).show()
        }
    }

    private var secretTapCount = 0
    private var lastSecretTapTime = 0L

    private fun handleSecretEasterEggTap() {
        val now = System.currentTimeMillis()
        if (now - lastSecretTapTime > 2000L) {
            secretTapCount = 0
        }
        lastSecretTapTime = now
        secretTapCount++
        vibrateTick()

        if (secretTapCount in 13..14) {
            val remaining = 15 - secretTapCount
            Toast.makeText(this, "$remaining taps to VIP unlock", Toast.LENGTH_SHORT).show()
        } else if (secretTapCount >= 15) {
            secretTapCount = 0
            showSecretRedeemDialog()
        }
    }

    private fun showSecretRedeemDialog() {
        val dialog = Dialog(this)
        val dialogBinding = DialogSecretVipBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialogBinding.btnCancelSecret.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnUnlockSecret.setOnClickListener {
            val code = dialogBinding.etSecretCode.text.toString().trim().uppercase()
            if (code == "PIYU5HVIP") {
                prefs.isVipUser = true
                prefs.isProUser = true
                updateUiState()
                dialog.dismiss()
                Toast.makeText(this, "🎉 VIP Access Granted! P-Sei Pro Unlocked.", Toast.LENGTH_LONG).show()
            } else {
                vibrateTick()
                Toast.makeText(this, "Invalid VIP passcode", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun vibrateTick() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(20, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }
}


