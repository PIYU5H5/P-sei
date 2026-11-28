package com.psei.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.psei.launcher.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Minimalist hardware-accelerated breathing halo background.
 * Renders subtle concentric circles following a slow sinusoidal inhale/exhale curve (~7.2s cycle),
 * adapting automatically to the active launcher theme color.
 */
class BreathingBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var themeAccentColor: Int = Color.WHITE
    private var breathCyclePhase: Float = 0f
    private var lastIsInhaling: Boolean? = null

    var onBreathPhaseChanged: ((isInhaling: Boolean) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var animator: ValueAnimator? = null

    init {
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(R.attr.primaryTextColor, typedValue, true)) {
            themeAccentColor = typedValue.data
        }
        val density = resources.displayMetrics.density
        strokePaint.strokeWidth = 1.25f * density
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val raw = anim.animatedValue as Float
                breathCyclePhase = raw
                val inhaling = raw < 0.5f
                if (lastIsInhaling != inhaling) {
                    lastIsInhaling = inhaling
                    onBreathPhaseChanged?.invoke(inhaling)
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Position slightly above center so bottom action controls stay uncluttered
        val cx = w * 0.5f
        val cy = h * 0.42f
        val maxBaseRadius = min(w, h) * 0.34f

        // Smooth natural sinusoidal breath curve: 0 -> 1 (inhale), 1 -> 0 (exhale)
        val breath = (1f - cos(breathCyclePhase * 2f * PI.toFloat())) * 0.5f

        val baseR = Color.red(themeAccentColor)
        val baseG = Color.green(themeAccentColor)
        val baseB = Color.blue(themeAccentColor)

        // Outer subtle breathing mist circle
        val rOuter = maxBaseRadius * (0.76f + 0.40f * breath)
        fillPaint.color = Color.argb(((0.025f + 0.045f * breath) * 255).toInt(), baseR, baseG, baseB)
        canvas.drawCircle(cx, cy, rOuter, fillPaint)

        // Middle ring
        val rMid = maxBaseRadius * (0.54f + 0.34f * breath)
        strokePaint.color = Color.argb(((0.07f + 0.11f * breath) * 255).toInt(), baseR, baseG, baseB)
        canvas.drawCircle(cx, cy, rMid, strokePaint)

        // Inner core ring + soft glow
        val rInner = maxBaseRadius * (0.34f + 0.28f * breath)
        fillPaint.color = Color.argb(((0.03f + 0.05f * breath) * 255).toInt(), baseR, baseG, baseB)
        canvas.drawCircle(cx, cy, rInner, fillPaint)

        strokePaint.color = Color.argb(((0.14f + 0.22f * breath) * 255).toInt(), baseR, baseG, baseB)
        canvas.drawCircle(cx, cy, rInner, strokePaint)
    }
}
