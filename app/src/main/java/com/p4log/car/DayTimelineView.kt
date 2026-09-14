package com.p4log.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 오늘의 타임라인 (주행 탭 주차 모드, 2026-09-15 — "아침 출근 · 저녁 퇴근이 한눈에").
 * 0시~24시 가로선 위에 오늘 주행을 주황 막대(시작~끝)로 찍는다. 눈금 0·6·12·18·24, 지금 시각은 흰 점.
 * 그리기만 하는 뷰 — 애니메이션 없음(부하 없음).
 */
class DayTimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var spans: List<LongArray> = emptyList()   // [startTs, endTs]
    private var dayStart = 0L
    private var nowTs = 0L

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2A2E"); strokeWidth = 2f }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A4A50"); strokeWidth = 1.5f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E8E93"); textAlign = Paint.Align.CENTER }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7500") }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F2F2F2") }
    private val rect = RectF()

    fun set(tripSpans: List<LongArray>, dayStartTs: Long, now: Long) {
        spans = tripSpans; dayStart = dayStartTs; nowTs = now
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val dp = resources.displayMetrics.density
        textPaint.textSize = 16f * dp
        val lineY = h * 0.42f
        val dayMs = 86_400_000f
        fun x(ts: Long) = ((ts - dayStart) / dayMs).coerceIn(0f, 1f) * (w - 2f * dp) + 1f * dp
        canvas.drawLine(0f, lineY, w, lineY, linePaint)
        for (hh in intArrayOf(0, 6, 12, 18, 24)) {
            val tx = x(dayStart + hh * 3_600_000L)
            canvas.drawLine(tx, lineY - 5f * dp, tx, lineY + 5f * dp, tickPaint)
            textPaint.textAlign = when (hh) { 0 -> Paint.Align.LEFT; 24 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(if (hh == 24) "24시" else hh.toString() + "시", tx, h - 2f * dp, textPaint)
        }
        val barH = 8f * dp
        for (s in spans) {
            val x0 = x(s[0]); val x1 = Math.max(x(s[1]), x0 + 6f * dp)
            rect.set(x0, lineY - barH / 2f, x1, lineY + barH / 2f)
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, barPaint)
        }
        if (nowTs > dayStart) canvas.drawCircle(x(nowTs), lineY, 4.5f * dp, nowPaint)
    }
}
