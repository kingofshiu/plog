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

/**
 * 회생 회수 비율 게이지 (주행 탭, 2026-09-08 — 사용자 결정 "마지막 충전 이후 기준 + 비율이 주인공").
 * 0~30%(값이 크면 눈금이 늘어남) 가로 게이지에 지금 사이클의 회수 비율을 초록으로 채우고,
 * 흰 세로선 = 최근 주행들의 평균 비율(평소). 지금 채움이 선을 넘으면 평소보다 잘 회수하는 중.
 * 애니메이션: 값이 바뀌면 700ms 감속 곡선으로 미끄러진다 (PowerMeterView와 같은 느낌).
 */
class RatioMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var hasData = false
    private var ratio = 0f      // 지금 사이클 %
    private var avg = 0f        // 평소 %
    private var shown = 0f
    private var shownAvg = 0f
    private var anim: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E1E22") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3ECF6E") }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#4A4A50")
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#F2F2F2")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E8E93") }
    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9C9CE") }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93"); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    /** ratioPct = 지금 사이클 회수 비율(%), avgPct = 평소 비율(%). ratioPct가 null이면 아직 데이터 없음 */
    fun setValues(ratioPct: Float?, avgPct: Float) {
        hasData = ratioPct != null
        ratio = ratioPct ?: 0f; avg = avgPct
        anim?.cancel()
        val fromR = shown; val fromA = shownAvg
        if (Math.abs(fromR - ratio) < 0.05f && Math.abs(fromA - avg) < 0.05f) { invalidate(); return }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 400
        a.interpolator = DecelerateInterpolator(1.8f)
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            shown = fromR + (ratio - fromR) * f
            shownAvg = fromA + (avg - fromA) * f
            invalidate()
        }
        a.start(); anim = a
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        if (!hasData) {
            hintPaint.textSize = 20f * dp
            canvas.drawText("충전 후 주행이 쌓이면 표시됩니다", w / 2f, h / 2f + hintPaint.textSize / 3f, hintPaint)
            return
        }
        // 눈금 최대: 30%가 기본, 값이 넘으면 10% 단위로 늘린다
        val need = Math.max(Math.max(ratio, avg) * 1.15f, 30f)
        val top = Math.ceil((need / 10f).toDouble()).toFloat() * 10f
        val step = if (top <= 30f) 10f else 20f

        val gTop = 26f * dp; val gH = 40f * dp
        fun x(v: Float) = (v / top).coerceIn(0f, 1f) * w
        rect.set(0f, gTop, w, gTop + gH)
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, trackPaint)
        if (shown > 0.05f) {
            rect.set(0f, gTop, x(shown), gTop + gH)
            canvas.drawRoundRect(rect, 8f * dp, 8f * dp, fillPaint)
        }
        if (shownAvg > 0.05f) {
            val ax = x(shownAvg)
            canvas.drawLine(ax, gTop - 4f * dp, ax, gTop + gH + 4f * dp, avgPaint)
        }
        // 눈금
        tickTextPaint.textSize = 20f * dp
        val tickY = gTop + gH
        var v = 0f
        while (v <= top + 0.01f) {
            val tx = x(v)
            canvas.drawLine(tx, tickY, tx, tickY + 6f * dp, tickPaint)
            tickTextPaint.textAlign = when {
                v == 0f -> Paint.Align.LEFT
                v >= top - 0.01f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(String.format("%.0f%%", v), tx, tickY + 8f * dp + tickTextPaint.textSize, tickTextPaint)
            v += step
        }
        labelPaint.textSize = 19f * dp
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("▏평소 " + String.format("%.0f%%", avg), 0f, gTop - 8f * dp, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("회수 비율", w, gTop - 8f * dp, labelPaint)
    }
}
