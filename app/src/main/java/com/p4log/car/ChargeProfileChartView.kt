package com.p4log.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 충전 속도 프로파일 차트: x=경과 분, y=kW.
 * 주황 곡선 + 은은한 면 채움, 상단에 최대 kW 점선.
 */
class ChargeProfileChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<Pair<Double, Double>> = emptyList() // (분, kW)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF7500")
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2EFF7500")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#3A3B40")
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9C9CE")
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val fillPath = Path()

    fun setData(profile: List<Pair<Double, Double>>) {
        points = profile
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val dp = resources.displayMetrics.density
        labelPaint.textSize = 14f * dp
        linePaint.strokeWidth = 3f * dp

        if (points.size < 2) {
            hintPaint.textSize = 16f * dp
            canvas.drawText(
                "충전 속도 데이터가 없습니다",
                w / 2f, h / 2f + hintPaint.textSize / 3f, hintPaint
            )
            return
        }

        val topPad = 22f * dp
        val bottomPad = 24f * dp
        val leftPad = 6f * dp
        val rightPad = 10f * dp
        val chartW = w - leftPad - rightPad
        val chartH = h - topPad - bottomPad

        val maxKw = points.maxOf { it.second }.coerceAtLeast(1.0)
        val maxMin = points.maxOf { it.first }.coerceAtLeast(1.0)
        val yMax = maxKw * 1.15

        fun px(min: Double) = leftPad + (min / maxMin * chartW).toFloat()
        fun py(kw: Double) = topPad + (chartH - kw / yMax * chartH).toFloat()

        // 최대 kW 점선 + 라벨
        val peakY = py(maxKw)
        canvas.drawLine(leftPad, peakY, w - rightPad, peakY, gridPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format("최대 %.1f kW", maxKw), leftPad, peakY - 6f * dp, labelPaint)

        // 곡선 + 면
        path.reset()
        fillPath.reset()
        for ((i, p) in points.withIndex()) {
            val x = px(p.first)
            val y = py(p.second)
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, topPad + chartH); fillPath.lineTo(x, y) }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(px(points.last().first), topPad + chartH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // x축 라벨: 0분, 끝 분
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("0분", leftPad, h - 6f * dp, labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format("%.0f분", maxMin), w - rightPad, h - 6f * dp, labelPaint)
    }
}
