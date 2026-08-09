package com.p4log.car

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView

/** 대시보드 탭: 실시간 상태 */
class DashboardPage(private val activity: Activity, root: View) : PageController {

    private val tvSocBig: TextView = root.findViewById(R.id.dash_soc_big)
    private val tvSocCaption: TextView = root.findViewById(R.id.dash_soc_caption)
    private val socBar: android.widget.ProgressBar = root.findViewById(R.id.dash_soc_bar)
    private val tvPower: TextView = root.findViewById(R.id.dash_power)
    private val tvRange: TextView = root.findViewById(R.id.dash_range)
    private val tvToday: TextView = root.findViewById(R.id.dash_today)
    private val tvEff: TextView = root.findViewById(R.id.dash_eff)
    private val tvSpeed: TextView = root.findViewById(R.id.dash_speed)
    private val tvTotal: TextView = root.findViewById(R.id.dash_total)
    private val tvStateLine: TextView = root.findViewById(R.id.dash_state_line)
    private val warnCard: View = root.findViewById(R.id.dash_warn_card)
    private val tvWarn: TextView = root.findViewById(R.id.dash_warn_text)

    init {
        warnCard.setOnClickListener {
            activity.startActivity(Intent(activity, DiagnosticsActivity::class.java))
        }
    }

    override fun onShow() = onTick()

    override fun onTick() {
        val s = LoggerService.lastSnapshot

        // 배터리 히어로: 대형 %, 슬림 바 (충전 중 초록)
        val pct = s.socPct
        tvSocBig.text = if (pct != null) String.format("%.0f%%", pct) else "--"
        if (pct != null) socBar.setProgress(Math.round(pct), true)
        val accent = if (s.chargeActive) 0xFF3ECF6E.toInt() else 0xFFFF7500.toInt()
        socBar.progressTintList = android.content.res.ColorStateList.valueOf(accent)
        tvSocCaption.text = if (s.chargeActive) "배터리 · 충전 중" else "배터리"
        tvSocCaption.setTextColor(
            if (s.chargeActive) 0xFF3ECF6E.toInt() else 0xFFC9C9CE.toInt()
        )

        tvPower.text = when {
            s.chargeRateKw == null -> "-"
            else -> String.format("%.1f kW", Math.abs(s.chargeRateKw.toDouble()))
        }
        tvRange.text = if (s.rangeKm != null) Fmt.kmInt(s.rangeKm.toDouble()) else "-"
        tvToday.text = Fmt.km(s.todayKm)
        tvEff.text = Fmt.eff(s.todayEff)
        // 차량 속도가 0으로 고정 보고될 수 있어 GPS 속도와 비교해 큰 쪽을 표시
        val carV = s.speedKmh
        val gpsV = s.gpsSpeedKmh
        tvSpeed.text = when {
            carV != null && (gpsV == null || carV >= gpsV) -> String.format("%.0f km/h", carV)
            gpsV != null -> String.format("%.0f km/h (GPS)", gpsV)
            else -> "-"
        }
        tvTotal.text = Fmt.kmInt(Prefs.totalKm(activity))

        // 상태 칩: 상태별 색으로 강조 (충전=초록, 주행=주황, 대기=회색)
        tvStateLine.text = when {
            s.chargeActive -> "⚡ 충전 중 · ${Fmt.kwh(s.chargeKwh)} 들어옴 · " +
                (if (s.chargeRateKw != null) Fmt.kw(s.chargeRateKw.toDouble()) else "")
            s.tripActive -> "주행 중 · ${Fmt.km(s.tripKm)} · ${s.tripMinutes}분"
            else -> "대기 중" + (if (s.gpsFix) " · GPS 수신" else " · GPS 대기")
        }
        val chipBg: Int
        val chipFg: Int
        when {
            s.chargeActive -> { chipBg = 0xFF1E4230.toInt(); chipFg = 0xFF3ECF6E.toInt() }
            s.tripActive -> { chipBg = 0xFF43290F.toInt(); chipFg = 0xFFFF7500.toInt() }
            else -> { chipBg = 0xFF141414.toInt(); chipFg = 0xFFC9C9CE.toInt() }
        }
        tvStateLine.backgroundTintList = android.content.res.ColorStateList.valueOf(chipBg)
        tvStateLine.setTextColor(chipFg)

        // 차량 데이터 경고
        if (!s.carConnected || s.batteryWh == null) {
            warnCard.visibility = View.VISIBLE
            tvWarn.text = if (!s.carConnected)
                "차량 연결 안 됨 — 눌러서 진단 보기"
            else
                "배터리 데이터 없음 (권한/미지원) — 눌러서 진단 보기"
        } else {
            warnCard.visibility = View.GONE
        }
    }
}
