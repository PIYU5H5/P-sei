package com.psei.launcher.ui

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Utility functions for robust edge-to-edge window insets handling.
 * Specifically handles the vivo Y53s waterdrop notch (display cutouts), tall status bar,
 * and gesture navigation bar / 3-button nav without clipping text.
 */
object InsetsUtils {

    /**
     * Applies system status bar and display cutout insets as top padding,
     * and system navigation bar insets as bottom padding.
     */
    fun applySystemWindowInsets(
        topView: View?,
        bottomView: View? = null,
        onInsetsApplied: ((insets: Insets) -> Unit)? = null
    ) {
        val targetView = topView ?: bottomView ?: return

        ViewCompat.setOnApplyWindowInsetsListener(targetView) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            topView?.setPadding(
                topView.paddingLeft,
                systemBars.top,
                topView.paddingRight,
                topView.paddingBottom
            )

            bottomView?.setPadding(
                bottomView.paddingLeft,
                bottomView.paddingTop,
                bottomView.paddingRight,
                systemBars.bottom
            )

            onInsetsApplied?.invoke(systemBars)
            windowInsets
        }
    }
}


