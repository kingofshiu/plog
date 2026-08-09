package com.p4log.car

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * 배터리 % 원형 게이지 (충전 중이면 초록, 평시 주황).
 * 값 변화는 부드럽게 차오르고, 충전 중에는 밝은 광택이 링을 따라 돈다.
 */
class SocRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var targetPct: Float? = null
    private var shownPct: Float = 0f
    private var hasValue = false
    private var charging: Boolean = false

    private var fillAnim: ValueAnimator? = null
    private var spinAnim: ValueAnimator? = null
    private var spinDeg = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#26262A")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#80FFFFFF")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2F2F2")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9C9CE")
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setState(socPct: Float?, isCharging: Boolean) {
        if (isCharging != charging) {
            charging = isCharging
            if (charging) startSpin() else stopSpin()
        }
        targetPct = socPct
        if (socPct == null) {
            hasValue = false
            invalidate()
            return
        }
        if (!hasValue) {
            // 첫 값: 0에서 현재 값까지 차오르는 연출
            hasValue = true
            animateTo(0f, socPct, 900L)
        } else if (Math.abs(socPct - shownPct) > 0.4f) {
            animateTo(shownPct, socPct, 500L)
        } else {
            shownPct = socPct
            invalidate()
        }
    }

    private fun animateTo(from: Float, to: Float, durationMs: Long) {
        fillAnim?.cancel()
        fillAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { shownPct = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startSpin() {
        if (spinAnim != null) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1800L
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { spinDeg = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        fillAnim?.cancel()
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = if (w < h) w else h
        val stroke = size * 0.075f
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        glowPaint.strokeWidth = stroke * 0.55f
        val accentColor =
            if (charging) Color.parseColor("#3ECF6E") else Color.parseColor("#FF7500")
        arcPaint.color = accentColor
        arcPaint.setShadowLayer(stroke * 0.7f, 0f, 0f, accentColor)

        val pad = stroke / 2f + size * 0.05f
        rect.set((w - size) / 2f + pad, (h - size) / 2f + pad,
                 (w + size) / 2f - pad, (h + size) / 2f - pad)

        canvas.drawArc(rect, 135f, 270f, false, trackPaint)

        val sweep = if (hasValue) 270f * (shownPct.coerceIn(0f, 100f) / 100f) else 0f
        if (sweep > 0f) {
            canvas.drawArc(rect, 135f, sweep, false, arcPaint)
            // 충전 중: 채워진 구간을 따라 도는 광택
            if (charging) {
                val pos = (spinDeg / 360f) * sweep
                val glowSweep = 22f.coerceAtMost(sweep)
                val start = 135f + (pos - glowSweep).coerceAtLeast(0f)
                canvas.drawArc(rect, start, glowSweep, false, glowPaint)
            }
        }

        textPaint.textSize = size * 0.26f
        subPaint.textSize = size * 0.095f
        val cx = w / 2f
        val cy = h / 2f
        val label = if (hasValue) String.format("%.0f%%", shownPct) else "--"
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)
        canvas.drawText(
            if (charging) "충전 중" else "배터리",
            cx, cy + textPaint.textSize * 0.35f + subPaint.textSize * 1.8f, subPaint
        )
    }

    init {
        // setShadowLayer(글로우)는 소프트웨어 레이어에서만 그려짐
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
}
