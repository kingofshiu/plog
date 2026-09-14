package com.p4log.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 실시간 전력 계기 (주행 탭 v11, 2026-09-06).
 * 사용자: "실시간이니까 시간축도, 막대 그래프도 빼고 다른 방식으로" → 자동차 계기판의 파워 바처럼 **가로 게이지 하나**.
 * 지금 값만큼 채우고 아래 kW 눈금. 게이지 위 흰 선 = 평균, 작은 ▼ = 최고.
 *
 * v12: 평균·최고는 LoggerService가 이번 주행 전체로 계산 (setValues(now, avg, peak)).
 * v13 (사용자: "게이지는 그대로, 아래 숫자만 게이지와 어울리게"): 숫자를 게이지 바로 아래 한 줄로 두고
 *  게이지의 표시 기호와 같은 아이콘(평균 = 흰 세로선, 최고 = 작은 삼각)을 앞에 붙여 한 세트로 읽히게 했다.
 *  - MODE_CONSUME: 소비, 주황 / MODE_REGEN: 회생, 초록
 */
class PowerMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MODE_CONSUME = 1
        const val MODE_REGEN = 2
    }

    private var mode = MODE_CONSUME
    private var hasData = false
    private var now = 0f
    private var avg = 0f
    private var peak = 0f
    private var shown = 0f          // 게이지가 실제로 그리는 값 (애니메이션으로 목표까지 따라감)
    private var shownAvg = 0f       // 평균 표시선도 같이 미끄러진다
    private var anim: android.animation.ValueAnimator? = null
    // 전력 흐름 띠 (2026-09-15): 채움 위를 밝은 띠가 흘러 "에너지가 움직이는 중"을 보여준다. 값이 있을 때만 돈다 (SocBarView 충전 띠와 같은 방식)
    private var flow = 0f
    private var flowAnim: android.animation.ValueAnimator? = null
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun startFlow() {
        if (flowAnim != null) return
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1400; a.repeatCount = android.animation.ValueAnimator.INFINITE
        a.interpolator = android.view.animation.LinearInterpolator()
        a.addUpdateListener { va -> flow = va.animatedValue as Float; invalidate() }
        a.start(); flowAnim = a
    }
    private fun stopFlow() { flowAnim?.cancel(); flowAnim = null }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (hasData && now > 0.3f) startFlow() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E1E22") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.parseColor("#4A4A50")
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.parseColor("#F2F2F2")
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9C9CE") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E8E93") }
    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C9C9CE") }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2F2F2"); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E8E93") }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93"); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val tri = android.graphics.Path()

    fun setMode(m: Int) { mode = m; invalidate() }

    private var accentOverride: Int? = null
    /** 색 덮어쓰기 (충전 중엔 소비 게이지를 초록으로, 2026-09-08). null이면 모드 기본색 */
    fun setAccent(color: Int?) { if (accentOverride != color) { accentOverride = color; invalidate() } }

    /** now = 지금 kW(크기), avg/peak = 이번 주행 평균·최고 kW. now가 null이면 차량 데이터 없음 */
    fun setValues(nowKw: Float?, avgKw: Float, peakKw: Float) {
        hasData = nowKw != null
        now = nowKw ?: 0f; avg = avgKw; peak = peakKw
        if (hasData && now > 0.3f && isAttachedToWindow) startFlow() else stopFlow()
        animateTo(now, avg)
    }

    /**
     * 게이지 애니메이션 (사용자 2026-09-08): 새 값이 오면 700ms 동안 감속 곡선으로 목표까지 미끄러진다.
     * 서비스 샘플은 2초, UI 티커는 1초라 값이 계단처럼 튀던 것을 자연스럽게 잇는다.
     */
    private fun animateTo(targetNow: Float, targetAvg: Float) {
        anim?.cancel()
        val fromNow = shown; val fromAvg = shownAvg
        if (Math.abs(fromNow - targetNow) < 0.05f && Math.abs(fromAvg - targetAvg) < 0.05f) { invalidate(); return }
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 300
        a.interpolator = android.view.animation.DecelerateInterpolator(1.8f)
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            shown = fromNow + (targetNow - fromNow) * f
            shownAvg = fromAvg + (targetAvg - fromAvg) * f
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        stopFlow()
        super.onDetachedFromWindow()
    }

    /** 눈금 간격: 최대값을 4~5칸으로 (1·2·5·10·20·50·100) */
    private fun niceStep(maxV: Float): Float {
        val raw = maxV / 4f
        for (s in floatArrayOf(1f, 2f, 5f, 10f, 20f, 50f, 100f)) if (s >= raw) return s
        return 100f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        val regen = mode == MODE_REGEN
        if (!hasData) {
            hintPaint.textSize = 20f * dp
            canvas.drawText("차량 데이터를 기다리는 중", w / 2f, h / 2f + hintPaint.textSize / 3f, hintPaint)
            return
        }
        // 눈금 최대: 지금·최고 중 큰 값 ×1.15를 눈금에 맞춰 올림. 최소 소비 30 / 회생 10
        val floorMax = if (regen) 10f else 30f
        val need = Math.max(Math.max(now, peak) * 1.15f, floorMax)
        val step = niceStep(need)
        val top = Math.ceil((need / step).toDouble()).toFloat() * step
        val color = accentOverride ?: if (regen) 0xFF3ECF6E.toInt() else 0xFFFF7500.toInt()

        // ---- 게이지 ----
        val gx0 = 0f; val gx1 = w
        val gTop = 26f * dp; val gH = 44f * dp
        fun x(v: Float) = gx0 + (v / top).coerceIn(0f, 1f) * (gx1 - gx0)
        rect.set(gx0, gTop, gx1, gTop + gH)
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, trackPaint)
        if (shown > 0.05f) {
            fillPaint.color = color
            rect.set(gx0, gTop, x(shown), gTop + gH)
            canvas.drawRoundRect(rect, 8f * dp, 8f * dp, fillPaint)
            if (flowAnim != null) {
                // 채움 위로 왼쪽→오른쪽 흐르는 밝은 띠 (폭 = 채움의 35%)
                val fw = x(shown) - gx0
                val bw = Math.max(fw * 0.35f, gH)
                val bx = gx0 - bw + (fw + bw) * flow
                bandPaint.shader = android.graphics.LinearGradient(bx, 0f, bx + bw, 0f,
                    intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
                canvas.save()
                canvas.clipRect(gx0, gTop, gx0 + fw, gTop + gH)
                canvas.drawRoundRect(rect, 8f * dp, 8f * dp, bandPaint)
                canvas.restore()
            }
        }
        // 최고: 게이지 위 작은 삼각형
        if (peak > 0.05f) {
            val px = x(peak)
            tri.reset()
            tri.moveTo(px, gTop - 3f * dp); tri.lineTo(px - 6f * dp, gTop - 12f * dp); tri.lineTo(px + 6f * dp, gTop - 12f * dp); tri.close()
            canvas.drawPath(tri, peakPaint)
        }
        // 평균: 게이지를 가로지르는 흰 선 (애니메이션 값)
        if (shownAvg > 0.05f) {
            val ax = x(shownAvg)
            canvas.drawLine(ax, gTop - 4f * dp, ax, gTop + gH + 4f * dp, avgPaint)
        }
        // 눈금
        tickTextPaint.textSize = 20f * dp
        var v = 0f
        val tickY = gTop + gH
        while (v <= top + 0.01f) {
            val tx = x(v)
            canvas.drawLine(tx, tickY, tx, tickY + 6f * dp, tickPaint)
            tickTextPaint.textAlign = when {
                v == 0f -> Paint.Align.LEFT
                v >= top - 0.01f -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(String.format("%.0f", v), tx, tickY + 8f * dp + tickTextPaint.textSize, tickTextPaint)
            v += step
        }
        labelPaint.textSize = 19f * dp
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("kW", gx1, gTop - 8f * dp, labelPaint)

        // ---- 게이지 아래: [▏평균] / 16.5 kW    [▲최고] / 45.8 kW ----
        // 게이지 위 표시와 같은 기호를 라벨 앞에 그려 "저 선·저 삼각이 이 숫자"로 읽히게 한다
        labelPaint.textSize = 22f * dp
        labelPaint.textAlign = Paint.Align.LEFT
        bigPaint.textSize = 44f * dp
        unitPaint.textSize = 21f * dp
        val labelY = tickY + 8f * dp + tickTextPaint.textSize + 40f * dp + labelPaint.textSize
        val baseY = labelY + 10f * dp + bigPaint.textSize
        val half = w / 2f
        fun stat(x0: Float, icon: Int, label: String, value: Float) {
            // 아이콘: 0 = 평균 세로선, 1 = 최고 삼각형 (게이지 위와 같은 모양·색). 라벨 줄에 세로 중앙
            val iconCx = x0 + 6f * dp
            val iconCy = labelY - labelPaint.textSize * 0.36f
            if (icon == 0) canvas.drawLine(iconCx, iconCy - 10f * dp, iconCx, iconCy + 10f * dp, avgPaint)
            else {
                tri.reset()
                tri.moveTo(iconCx, iconCy + 6f * dp); tri.lineTo(iconCx - 6f * dp, iconCy - 5f * dp); tri.lineTo(iconCx + 6f * dp, iconCy - 5f * dp); tri.close()
                canvas.drawPath(tri, peakPaint)
            }
            canvas.drawText(label, x0 + 20f * dp, labelY, labelPaint)
            val s = String.format("%.1f", value)
            canvas.drawText(s, x0, baseY, bigPaint)
            canvas.drawText("kW", x0 + bigPaint.measureText(s) + 6f * dp, baseY, unitPaint)
        }
        if (baseY <= h) {
            stat(gx0, 0, "평균", avg)
            stat(half, 1, "최고", peak)
        }
    }
}
