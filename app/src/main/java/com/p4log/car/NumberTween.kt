package com.p4log.car

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * 숫자 카운트업 (2026-09-08, 사용자: "숫자가 바뀔 때 어색하지 않은 애니메이션").
 * 새 값이 오면 500ms 동안 이전 표시값에서 새 값으로 굴러간다. 처음 값이거나 null(값 없음)이면 바로 바꾼다.
 * 표시 형식은 fmt가 정한다(단위 span 포함 가능). 주행 탭 히어로·주행 기록 화면 요약/상세가 같이 쓴다.
 * 부하: 값이 실제로 바뀔 때만 ValueAnimator 하나(텍스트 갱신뿐).
 */
class NumberTween(private val tv: TextView, private val fmt: (Double) -> CharSequence) {
    private var shown = Double.NaN
    private var key = ""            // 형식이 바뀌면(단위 등) 굴리지 않고 바로 교체
    private var anim: ValueAnimator? = null

    /** value가 null이면 placeholder를 그대로 표시. formatKey가 바뀌면 애니메이션 없이 교체 */
    fun set(value: Double?, placeholder: String = "-", formatKey: String = "", epsilon: Double = 0.05) {
        if (value == null) { anim?.cancel(); shown = Double.NaN; tv.text = placeholder; return }
        if (shown.isNaN() || formatKey != key) {
            anim?.cancel(); key = formatKey; shown = value; tv.text = fmt(value); return
        }
        if (Math.abs(shown - value) < epsilon) { tv.text = fmt(value); shown = value; return }
        anim?.cancel()
        val from = shown
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 260
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { va ->
            val f = (va.animatedValue as Float).toDouble()
            shown = from + (value - from) * f
            tv.text = fmt(shown)
        }
        a.start(); anim = a
    }

    fun cancel() { anim?.cancel(); anim = null }
}
