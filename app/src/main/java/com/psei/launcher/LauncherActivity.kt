package com.psei.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Normal application entry point registered ONLY with android.intent.category.LAUNCHER.
 *
 * Why this is crucial:
 * Many OEM home launchers (such as vivo Funtouch OS and Samsung One UI) deliberately filter out
 * any Activity containing android.intent.category.HOME from their app drawer to avoid nested launcher loops.
 * By exposing LauncherActivity with ONLY category.LAUNCHER, P Launcher is guaranteed to appear
 * as an ordinary app with its logo in the user's current app drawer and home screen.
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            val directIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(directIntent)
        }
        finish()
    }
}


