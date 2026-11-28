package com.psei.launcher.ui

import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.psei.launcher.R
import com.psei.launcher.data.AppModel
import com.psei.launcher.data.Prefs
import com.psei.launcher.data.UsageHelper
import com.psei.launcher.service.AppLimitMonitorService
import com.psei.launcher.service.LockAccessibilityService

/**
 * Shared UI dialogs and lockout snooze animations for daily app limits, avoiding
 * duplicated countdown and permission logic between MainActivity, SettingsActivity,
 * and AppLimitMonitorService.
 */
object AppLimitDialogHelper {

    class CooldownHandle(
        private val timer: CountDownTimer? = null,
        private val animator: ObjectAnimator? = null
    ) {
        fun cancel() {
            timer?.cancel()
            animator?.cancel()
        }
    }

    private val LIMIT_MINUTES = intArrayOf(0, 1, 5, 10, 15, 30, 45, 60, 90, 120)
    private val LIMIT_LABELS = arrayOf(
        "None (No limit)",
        "1 minute",
        "5 minutes",
        "10 minutes",
        "15 minutes",
        "30 minutes",
        "45 minutes",
        "1 hour (60m)",
        "1.5 hours (90m)",
        "2 hours (120m)"
    )

    fun showUsageAccessPromptDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Usage Access Required")
            .setMessage("To track daily screen time accurately and enforce app timers, P-Sei needs Usage Access permission.")
            .setPositiveButton("Open Settings") { _, _ ->
                UsageHelper.openUsageAccessSettings(context)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showOverlayPermissionPromptDialog(context: Context, onContinueAnyway: (() -> Unit)? = null) {
        val builder = AlertDialog.Builder(context)
            .setTitle("Display Over Other Apps")
            .setMessage(
                "To automatically close limited apps when time runs out—and block opening them from notifications, Recents, or links—allow P-Sei to display over other apps.\n\n" +
                    "This does not use Accessibility Services and keeps Google Pay / UPI working normally."
            )
            .setPositiveButton("Allow in Settings") { _, _ ->
                UsageHelper.openOverlayPermissionSettings(context)
            }

        if (onContinueAnyway != null) {
            builder.setNegativeButton("Later") { _, _ ->
                onContinueAnyway()
            }
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    fun showSetAppLimitDialog(
        context: Context,
        app: AppModel,
        onComplete: (() -> Unit)? = null
    ) {
        val prefs = Prefs.getInstance(context)
        val currentLimit = prefs.getAppScreenTimeLimit(app.packageName)
        val currentIdx = LIMIT_MINUTES.indexOf(currentLimit).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(context)
            .setTitle("Daily Limit: ${app.label}")
            .setSingleChoiceItems(LIMIT_LABELS, currentIdx) { dialog, which ->
                val chosen = LIMIT_MINUTES[which]
                prefs.setAppScreenTimeLimit(app.packageName, chosen)

                AppLimitMonitorService.syncMonitoringState(context)
                LockAccessibilityService.syncEventSubscription(context)

                if (chosen > 0) {
                    Toast.makeText(context, "Daily limit set to ${chosen}m for ${app.label}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Daily limit removed for ${app.label}", Toast.LENGTH_SHORT).show()
                }

                dialog.dismiss()
                onComplete?.invoke()

                if (chosen > 0 && !UsageHelper.hasOverlayPermission(context)) {
                    showOverlayPermissionPromptDialog(context)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Binds the 10-second mindful cooldown timer and smooth 2dp progress animation onto
     * an Emergency +5m button in either a dialog or system overlay window.
     */
    fun bindEmergencySnoozeButton(
        context: Context,
        pkg: String,
        appLabel: String,
        btnSnooze: TextView,
        progressCooldown: ProgressBar,
        onTickHaptic: () -> Unit,
        onBeforeLaunchSnoozed: () -> Unit
    ): CooldownHandle {
        val prefs = Prefs.getInstance(context)
        if (prefs.hasUsedEmergencySnoozeToday(pkg)) {
            btnSnooze.text = "Emergency +5m already used today"
            btnSnooze.isEnabled = false
            btnSnooze.alpha = 0.4f
            progressCooldown.isVisible = false
            return CooldownHandle()
        }

        btnSnooze.isEnabled = false
        btnSnooze.alpha = 0.4f
        btnSnooze.text = "Emergency +5m (10s)"
        progressCooldown.isVisible = true
        progressCooldown.progress = 0

        val animator = ObjectAnimator.ofInt(progressCooldown, "progress", 0, 1000).apply {
            duration = 10_000L
            interpolator = LinearInterpolator()
            start()
        }

        var timer: CountDownTimer? = null
        timer = object : CountDownTimer(10_000L, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished + 999) / 1000
                btnSnooze.text = "Emergency +5m (${secondsLeft}s)"
                val fraction = (10_000L - millisUntilFinished).toFloat() / 10_000f
                btnSnooze.alpha = 0.4f + (0.35f * fraction)
            }

            override fun onFinish() {
                progressCooldown.isVisible = false
                btnSnooze.isEnabled = true
                btnSnooze.text = "Emergency +5 Minutes (Once today)"
                onTickHaptic()
                btnSnooze.animate()
                    .alpha(1.0f)
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(180)
                    .withEndAction {
                        btnSnooze.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(120)
                            .start()
                    }
                    .start()
            }
        }.start()

        val handle = CooldownHandle(timer, animator)
        btnSnooze.setOnClickListener {
            handle.cancel()
            prefs.useEmergencySnooze(pkg, 5)
            Toast.makeText(context.applicationContext, "Emergency 5 minutes granted for $appLabel", Toast.LENGTH_SHORT).show()
            onBeforeLaunchSnoozed()
        }
        return handle
    }

    /**
     * Configures a Dialog window to span edge-to-edge full screen matching the launcher theme.
     */
    fun configureFullscreenDialog(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val bgTyped = TypedValue()
        if (dialog.context.theme.resolveAttribute(R.attr.launcherBackground, bgTyped, true)) {
            val isLightBg = ColorUtils.calculateLuminance(bgTyped.data) > 0.5
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLightBg
                isAppearanceLightNavigationBars = isLightBg
            }
        }
    }

    /**
     * Keeps bottom action buttons cleanly clear of the system gesture/navigation bar.
     */
    fun applyBottomSystemBarInsets(bottomView: View) {
        val baseBottom = bottomView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomView) { view, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                baseBottom + navInsets.bottom
            )
            insets
        }
        bottomView.requestApplyInsets()
    }

    /**
     * Synchronizes calm breathing guidance cue transitions with the background breathing halo.
     */
    fun attachBreathingGuidance(backdrop: BreathingBackdropView, tvBreathPhase: TextView) {
        backdrop.onBreathPhaseChanged = { isInhaling ->
            val targetText = if (isInhaling) "Breathe in slowly" else "Breathe out and relax"
            if (tvBreathPhase.text != targetText) {
                tvBreathPhase.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction {
                        tvBreathPhase.text = targetText
                        tvBreathPhase.animate()
                            .alpha(0.75f)
                            .setDuration(260)
                            .start()
                    }
                    .start()
            }
        }
    }

    /**
     * Smooth calm entrance animation across full-screen backdrop, reflection text, and bottom actions.
     */
    fun animateFullscreenEntrance(root: View, centerView: View, bottomView: View) {
        val density = root.resources.displayMetrics.density
        val interp = DecelerateInterpolator()

        root.alpha = 0f
        root.animate()
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(interp)
            .start()

        centerView.alpha = 0f
        centerView.translationY = 14f * density
        centerView.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(60)
            .setDuration(320)
            .setInterpolator(interp)
            .start()

        bottomView.alpha = 0f
        bottomView.translationY = 20f * density
        bottomView.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(100)
            .setDuration(320)
            .setInterpolator(interp)
            .start()
    }
}
