package com.p4log.car

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 충전 타임라인 (충전 상세 v3, 2026-09-08 — 사용자: "막대 말고 센스 있는 방식").
 * 위: 충전 전력의 가는 곡선(축 없음) + 최고점 표시. 가운데: 시간축을 따라 **열 띠** — 전력이 셀수록 진하게 채워져
 * "처음엔 빠르고 뒤로 갈수록 느려졌다"가 색으로 읽힌다. 양 끝에 시작·끝 배터리 %. 아래: 시각 눈금과 평균·충전 속도.
 * 열릴 때 띠가 왼쪽에서 오른쪽으로 채워지고 곡선이 함께 그려진다(900ms). 급속 주황 / 완속 파랑.
 */
class ChargeStripView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pts: List<Pair<Double, Double>> = emptyList()
    private var totalMin = 1f
    private var socStart: Float? = null
    private var socEnd: Float? = null
    private var accent = 0xFFFF7500.toInt()
    private var reveal = 1f
    private var anim: ValueAnimator? = null

    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E1E22") }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.parseColor("#2A2A2E") }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E8E93") }
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F2F2") }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F2F2"); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E8E93"); textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    fun setData(profile: List<Pair<Double, Double>>, isDc: Boolean, socFrom: Float?, socTo: Float?) {
        accent = if (isDc) 0xFFFF7500.toInt() else 0xFF4DA3FF.toInt()
        pts = profile.sortedBy { it.first }
        totalMin = (pts.lastOrNull()?.first ?: 1.0).toFloat().coerceAtLeast(1f)
        socStart = socFrom; socEnd = socTo
        anim?.cancel()
        reveal = 0f
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 900
        va.interpolator = DecelerateInterpolator(1.6f)
        va.addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
        va.start(); anim = va
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    private fun kwAt(m: Float): Float {
        if (pts.isEmpty()) return 0f
        if (m <= pts.first().first) return pts.first().second.toFloat()
        for (i in 1 until pts.size) {
            val (m0, k0) = pts[i - 1]; val (m1, k1) = pts[i]
            if (m <= m1) { val f = if (m1 > m0) (m - m0) / (m1 - m0) else 0.0; return (k0 + (k1 - k0) * f).toFloat() }
        }
        return pts.last().second.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        if (pts.size < 2) {
            hint.textSize = 20f * dp
            canvas.drawText("충전 속도 기록이 없습니다", w / 2f, h / 2f, hint)
            return
        }
        label.textSize = 18f * dp; strong.textSize = 20f * dp; big.textSize = 34f * dp
        val socW = 78f * dp                              // 양 끝 배터리 % 자리
        val x0 = socW; val x1 = w - socW
        val cw = x1 - x0
        val maxKw = pts.maxOf { it.second }.toFloat().coerceAtLeast(1f)
        val avgKw = run { var s = 0f; var n = 0; var m = 0f; while (m <= totalMin) { s += kwAt(m); n++; m += 0.5f }; if (n > 0) s / n else 0f }
        val peakM = pts.maxByOrNull { it.second }!!.first.toFloat()
        fun x(m: Float) = x0 + (m / totalMin) * cw

        // ---- 위: 전력 곡선 (축 없음) ----
        val curveTop = 40f * dp; val curveH = (h * 0.34f).coerceAtLeast(60f * dp)
        val curveBase = curveTop + curveH
        canvas.drawLine(x0, curveBase, x1, curveBase, base)
        curve.color = accent
        path.reset()
        val endM = totalMin * reveal
        var m = 0f; var first = true
        while (m <= endM) {
            val px = x(m); val py = curveBase - (kwAt(m) / maxKw) * curveH
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            m += totalMin / 120f
        }
        canvas.drawPath(path, curve)
        if (reveal >= 0.999f) {
            val px = x(peakM); val py = curveBase - curveH
            dot.color = accent
            canvas.drawCircle(px, py, 6f * dp, dot)
            // 라벨은 점 위에 (곡선과 겹치지 않게). 점이 오른쪽 끝이면 오른쪽 정렬
            strong.textAlign = if (peakM > totalMin * 0.7f) Paint.Align.RIGHT else if (peakM < totalMin * 0.3f) Paint.Align.LEFT else Paint.Align.CENTER
            canvas.drawText(String.format("최대 %.0f kW · %.0f분", maxKw, peakM), px, py - 14f * dp, strong)
        }

        // ---- 가운데: 열 띠 (전력이 셀수록 진하게) ----
        val stripTop = curveBase + 22f * dp; val stripH = 44f * dp
        rect.set(x0, stripTop, x1, stripTop + stripH)
        canvas.drawRoundRect(rect, 10f * dp, 10f * dp, track)
        canvas.save()
        path.reset(); path.addRoundRect(rect, 10f * dp, 10f * dp, Path.Direction.CW)
        canvas.clipPath(path)
        val n = 90
        for (i in 0 until n) {
            val ma = totalMin * i / n; val mb = totalMin * (i + 1) / n
            if (ma > endM) break
            val k = kwAt((ma + mb) / 2f) / maxKw
            seg.color = accent
            seg.alpha = (60 + 195 * k).toInt().coerceIn(40, 255)
            canvas.drawRect(x(ma), stripTop, x(Math.min(mb, endM)) + 1f, stripTop + stripH, seg)
        }
        canvas.restore()
        // 양 끝 배터리 %
        big.textAlign = Paint.Align.RIGHT
        val socY = stripTop + stripH / 2f + big.textSize / 3f
        canvas.drawText(socStart?.let { String.format("%.0f%%", it) } ?: "-", x0 - 14f * dp, socY, big)
        big.textAlign = Paint.Align.LEFT
        val shownEnd = if (socStart != null && socEnd != null) socStart!! + (socEnd!! - socStart!!) * reveal else socEnd
        canvas.drawText(shownEnd?.let { String.format("%.0f%%", it) } ?: "-", x1 + 14f * dp, socY, big)

        // ---- 아래: 시각 눈금 + 평균·충전 속도 ----
        val tickY = stripTop + stripH + 8f * dp + label.textSize
        label.textAlign = Paint.Align.LEFT; canvas.drawText("0분", x0, tickY, label)
        label.textAlign = Paint.Align.CENTER; canvas.drawText(String.format("%.0f분", totalMin / 2f), x0 + cw / 2f, tickY, label)
        label.textAlign = Paint.Align.RIGHT; canvas.drawText(String.format("%.0f분", totalMin), x1, tickY, label)
        val infoY = tickY + 30f * dp
        strong.textAlign = Paint.Align.LEFT
        var info = String.format("평균 %.0f kW", avgKw)
        if (socStart != null && socEnd != null && totalMin > 1f)
            info += String.format("   ·   10분당 +%.1f%%", (socEnd!! - socStart!!) / totalMin * 10f)
        if (infoY + 4f * dp <= h) canvas.drawText(info, x0, infoY, strong)
    }
}
