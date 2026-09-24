package com.p4log.car

import org.json.JSONArray
import java.util.Calendar

/**
 * 내 충전기(집·회사) 요금 (2026-09-24, 사용자: "집밥은 어떻게 표시하는 게 좋을까 → 전부 좋다, 절약 금액만 빼자").
 *
 * 1) 계량 kWh: 앱이 읽는 kWh는 배터리에 들어간 양(차량 API EV_BATTERY_INSTANTANEOUS_CHARGE_RATE / EV_BATTERY_LEVEL 델타)이고,
 *    집·회사 완속 충전기의 계량기는 교류 쪽을 잰다. 실차: 11 kW 충전기 → 클러스터 10 kW → 앱 8.8 kW = 88%. 그래서 완속은 ÷0.88.
 *    급속(DC)은 충전기가 직류를 바로 넣어 차이가 거의 없으므로 그대로.
 * 2) 한전 "전기자동차 충전전력요금" 저압 (2023-11-09 시행, cyber.kepco.co.kr 요금표 CYEEHP00108): 계절·시간대별 원/kWh.
 *    개정되면 RATES 숫자만 고친다. 기본요금(2,390원/kW·월)은 충전 건당이 아니라 여기 넣지 않는다.
 *    시간대: 경부하 22~08시 / 중간부하 08~14·17~22시 / 최대부하 14~17시. 계절: 여름 6~8월, 겨울 11~2월, 나머지 봄·가을.
 * 3) 충전 1건의 kWh를 1분 단위 전력 곡선(profile [[분,kW],…])에 비례해 분 단위로 나눠 각 분이 속한 시간대 단가를 곱한다.
 */
object HomeTariff {
    const val AC_METER_EFF = 0.88
    val BAND_NAMES = arrayOf("경부하", "중간부하", "최대부하")
    // [계절][시간대] 계절 0 여름 · 1 봄가을 · 2 겨울 / 시간대 0 경부하 · 1 중간 · 2 최대
    private val RATES = arrayOf(
        doubleArrayOf(84.3, 172.0, 259.2),
        doubleArrayOf(85.4, 97.2, 102.1),
        doubleArrayOf(107.4, 154.9, 217.5)
    )

    /** 계량기 기준 kWh: 내 충전기(kind != null) + 완속이면 배터리 kWh ÷ 0.88, 아니면 그대로 */
    fun meterKwh(kwh: Double, kind: String?, type: String): Double =
        if (kind != null && type == "AC") kwh / AC_METER_EFF else kwh

    fun season(month1to12: Int): Int = when (month1to12) { 6, 7, 8 -> 0; 11, 12, 1, 2 -> 2; else -> 1 }
    fun band(hour: Int): Int = when { hour >= 22 || hour < 8 -> 0; hour in 14..16 -> 2; else -> 1 }
    fun rate(ts: Long): Double {
        val c = Calendar.getInstance(); c.timeInMillis = ts
        return RATES[season(c.get(Calendar.MONTH) + 1)][band(c.get(Calendar.HOUR_OF_DAY))]
    }

    class Result(val cost: Double, val kwhByBand: DoubleArray) {
        /** "경부하 82% · 중간부하 18%" (0%인 구간 생략) */
        fun summary(): String {
            val total = kwhByBand.sum()
            if (total <= 0.001) return ""
            return kwhByBand.indices.filter { kwhByBand[it] / total >= 0.005 }
                .joinToString(" · ") { BAND_NAMES[it] + " " + String.format("%.0f%%", kwhByBand[it] / total * 100) }
        }
    }

    /** 시간대 요금 계산. meterKwh = 계량 kWh(이미 ÷0.88 한 값). profileJson = [[분,kW],…] (없으면 시간 균등) */
    fun touCost(startTs: Long, endTs: Long, profileJson: String, meterKwh: Double): Result {
        val totalMin = Math.max(1, ((endTs - startTs) / 60_000L).toInt())
        val pts = ArrayList<Pair<Double, Double>>()
        try {
            val arr = JSONArray(profileJson)
            for (i in 0 until arr.length()) { val p = arr.optJSONArray(i) ?: continue; pts.add(p.optDouble(0, 0.0) to p.optDouble(1, 0.0)) }
        } catch (e: Throwable) { }
        pts.sortBy { it.first }
        fun kwAt(m: Double): Double {
            if (pts.size < 2) return 1.0
            if (m <= pts.first().first) return pts.first().second
            for (i in 1 until pts.size) {
                val (m0, k0) = pts[i - 1]; val (m1, k1) = pts[i]
                if (m <= m1) { val f = if (m1 > m0) (m - m0) / (m1 - m0) else 0.0; return k0 + (k1 - k0) * f }
            }
            return pts.last().second
        }
        val w = DoubleArray(totalMin) { Math.max(0.0, kwAt(it + 0.5)) }
        var sum = w.sum()
        if (sum <= 0.0) { for (i in w.indices) w[i] = 1.0; sum = totalMin.toDouble() }
        val byBand = DoubleArray(3)
        var cost = 0.0
        val c = Calendar.getInstance()
        for (i in 0 until totalMin) {
            val kwh = meterKwh * w[i] / sum
            val ts = startTs + i * 60_000L
            c.timeInMillis = ts
            val b = band(c.get(Calendar.HOUR_OF_DAY))
            byBand[b] += kwh
            cost += kwh * RATES[season(c.get(Calendar.MONTH) + 1)][b]
        }
        return Result(cost, byBand)
    }

    fun kindLabel(kind: String?): String? = when (kind) { "home" -> "집"; "work" -> "회사"; else -> null }
    /** "집으로" / "회사로" — 받침 유무에 따른 조사 */
    fun kindLabelRo(kind: String?): String = when (kind) { "home" -> "집으로"; "work" -> "회사로"; else -> "" }
}
