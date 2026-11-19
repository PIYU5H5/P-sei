package com.psei.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build

/**
 * Utility to set a pure AMOLED black static wallpaper for the system.
 *
 * Why this is essential for performance on vivo Funtouch OS:
 * Stock vivo devices ship with an animated OpenGL live wallpaper (com.vivo.livewallpaper.dynamic)
 * active by default. When opening the system Recents overview (com.android.launcher3),
 * vivo's overview applies multi-layer real-time Gaussian blur on top of that live wallpaper,
 * causing massive GPU frame drops (down to 10 FPS) when swiping through recent apps.
 * Setting a static 100% black wallpaper unbinds and terminates the live wallpaper service,
 * eliminates the old UI wallpaper from showing behind Recents, and restores 60fps smoothness.
 */
object WallpaperHelper {

    fun setPureBlackWallpaper(context: Context): Boolean {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context.applicationContext)
            // Create a small pure black bitmap (32x32 scaled automatically by WallpaperManager)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Apply to system home screen (which is what Recents displays)
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            } else {
                wallpaperManager.setBitmap(bitmap)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }
}
