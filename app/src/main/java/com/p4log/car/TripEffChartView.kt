package com.p4log.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 최근 주행별 전비(km/kWh) 막대 그래프.
 * 이번 주행은 주황으로 강조, 내 평균은 점선으로 표시 — "내 운전 스타일 대비 이번 주행" 비교용.
 */
class TripEffChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var items: List<Pair<Long, Double>> = emptyList() // (tripId, 전비) 시간순
    private var highlightId: Long = -1L

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#C9C9CE")
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9C9CE")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7500")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setData(effs: List<Pair<Long, Double>>, highlightTripId: Long) {
        items = effs
        highlightId = highlightTripId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val dp = resources.displayMetrics.density

        if (items.size < 2) {
            hintPaint.textSize = 16f * dp
            canvas.drawText(
                "전비 기록이 2건 이상 쌓이면 비교 그래프가 표시됩니다",
                w / 2f, h / 2f + hintPaint.textSize / 3f, hintPaint
            )
            return
        }

        val topPad = 18f * dp
        val bottomPad = 6f * dp
        val rightPad = 64f * dp   // 평균 라벨 자리
        val chartW = w - rightPad
        val chartH = h - topPad - bottomPad
        val maxEff = (items.maxOf { it.second } * 1.2).coerceAtLeast(1.0)
        val avg = items.sumOf { it.second } / items.size

        // 막대
        val slot = chartW / items.size
        val barW = slot * 0.55f
        for ((i, item) in items.withIndex()) {
            val (id, eff) = item
            val barH = ((eff / maxEff) * chartH).toFloat()
            val left = i * slot + (slot - barW) / 2f
            rect.set(left, topPad + chartH - barH, left + barW, topPad + chartH)
            barPaint.color =
                if (id == highlightId) Color.parseColor("#FF7500")
                else Color.parseColor("#3A3B40")
            val r = 2f * dp
            canvas.drawRoundRect(rect, r, r, barPaint)
            if (id == highlightId) {
                valuePaint.textSize = 15f * dp
                canvas.drawText(
                    String.format("%.1f", eff),
                    left + barW / 2f, rect.top - 6f * dp, valuePaint
                )
            }
        }

        // 평균 점선 + 라벨
        val avgY = topPad + chartH - ((avg / maxEff) * chartH).toFloat()
        canvas.drawLine(0f, avgY, chartW, avgY, avgPaint)
        textPaint.textSize = 14f * dp
        canvas.drawText(
            String.format("평균 %.1f", avg),
            chartW + 8f * dp, avgY + textPaint.textSize / 3f, textPaint
        )
    }
}
