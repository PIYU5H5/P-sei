package com.psei.launcher.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Handles gestures on empty spaces of the launcher home screen:
 * - Double-tap: lock screen
 * - Swipe up: open app drawer
 * - Swipe down: expand notification shade
 * - Swipe left: launch assigned shortcut
 * - Swipe right: launch assigned shortcut
 */
class GestureHandler(
    context: Context,
    private val callbacks: Callbacks
) : View.OnTouchListener {

    interface Callbacks {
        fun onDoubleTap()
        fun onSwipeUp()
        fun onSwipeDown()
        fun onSwipeLeft()
        fun onSwipeRight()
    }

    private val gestureDetector: GestureDetector

    init {
        val listener = object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 120
            private val SWIPE_VELOCITY_THRESHOLD = 200

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                callbacks.onDoubleTap()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                return if (abs(diffX) > abs(diffY)) {
                    // Horizontal swipe
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            callbacks.onSwipeRight()
                        } else {
                            callbacks.onSwipeLeft()
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    // Vertical swipe
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY > 0) {
                            callbacks.onSwipeDown()
                        } else {
                            callbacks.onSwipeUp()
                        }
                        true
                    } else {
                        false
                    }
                }
            }
        }

        gestureDetector = GestureDetector(context, listener)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }
}


