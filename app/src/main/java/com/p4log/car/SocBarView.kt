package com.p4log.car

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * 배터리 얇은 라인 (주행 탭, 2026-09-08 — 사용자: "주행 중·충전 중을 센스 있게 보이게").
 *  - 채움은 값이 바뀔 때 부드럽게 미끄러진다.
 *  - 충전 중: 초록으로 바뀌고 밝은 띠가 왼쪽→오른쪽으로 흐른다(휴대폰 충전 애니메이션 느낌, 1.8초 주기).
 *  - 주행 중/대기: 주황, 띠 없음.
 * 흐름 애니메이션은 충전 중이고 화면에 붙어 있을 때만 돈다(onDetachedFromWindow에서 정지).
 */
class SocBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pct = 0f
    private var shown = -1f
    private var charging = false
    private var flow = 0f
    private var fillAnim: ValueAnimator? = null
    private var flowAnim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26262A") }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun set(percent: Float?, isCharging: Boolean) {
        val target = (percent ?: 0f).coerceIn(0f, 100f)
        if (charging != isCharging) {
            charging = isCharging
            if (charging) startFlow() else stopFlow()
        }
        if (shown < 0f) { shown = target; pct = target; invalidate(); return }
        if (Math.abs(target - pct) < 0.05f) return
        pct = target
        fillAnim?.cancel()
        val from = shown
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 350
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { va -> shown = from + (target - from) * (va.animatedValue as Float); invalidate() }
        a.start(); fillAnim = a
    }

    private fun startFlow() {
        if (flowAnim != null) return
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1800
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { va -> flow = va.animatedValue as Float; invalidate() }
        a.start(); flowAnim = a
    }

    private fun stopFlow() { flowAnim?.cancel(); flowAnim = null; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (charging) startFlow() }
    override fun onDetachedFromWindow() { fillAnim?.cancel(); flowAnim?.cancel(); flowAnim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val fw = w * (if (shown < 0f) 0f else shown) / 100f
        if (fw <= 0f) return
        fill.shader = null
        fill.color = if (charging) 0xFF3ECF6E.toInt() else 0xFFFF7500.toInt()
        rect.set(0f, 0f, fw, h)
        canvas.drawRoundRect(rect, r, r, fill)
        if (charging && flowAnim != null) {
            // 채움 위로 지나가는 밝은 띠: 폭은 채움의 35%, 위치는 -폭 → 채움 끝
            val bw = Math.max(fw * 0.35f, h * 4f)
            val x0 = -bw + (fw + bw) * flow
            band.shader = LinearGradient(x0, 0f, x0 + bw, 0f,
                intArrayOf(0x003ECF6E, 0xB3FFFFFF.toInt(), 0x003ECF6E), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.save()
            canvas.clipRect(0f, 0f, fw, h)
            canvas.drawRect(x0, 0f, x0 + bw, h, band)
            canvas.restore()
        }
    }
}
