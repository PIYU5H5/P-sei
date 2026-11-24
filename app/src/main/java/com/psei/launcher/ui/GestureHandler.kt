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
    private val swipeThreshold = (60 * context.resources.displayMetrics.density).toInt().coerceAtLeast(80)
    private val swipeVelocityThreshold = 150

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var flingHandled: Boolean = false

    init {
        val listener = object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                downX = e.x
                downY = e.y
                flingHandled = false
                return true
            }

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
                    if (abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold) {
                        flingHandled = true
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
                    if (abs(diffY) > swipeThreshold && (abs(velocityY) > swipeVelocityThreshold || abs(diffY) > swipeThreshold * 1.5f)) {
                        flingHandled = true
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            flingHandled = false
        }
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !flingHandled) {
            val diffX = event.x - downX
            val diffY = event.y - downY
            if (abs(diffX) > abs(diffY)) {
                if (abs(diffX) > swipeThreshold * 1.5f) {
                    if (diffX > 0) callbacks.onSwipeRight() else callbacks.onSwipeLeft()
                    return true
                }
            } else {
                if (abs(diffY) > swipeThreshold * 1.5f) {
                    if (diffY > 0) callbacks.onSwipeDown() else callbacks.onSwipeUp()
                    return true
                }
            }
        }
        return handled
    }
}


