package com.psei.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Normal application entry point registered with android.intent.category.LAUNCHER.
 * Launches MainActivity directly so tapping the app icon in any launcher immediately
 * opens P-Sei.
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val directIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(directIntent)
        } catch (e: Exception) {
            // Defensive
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
