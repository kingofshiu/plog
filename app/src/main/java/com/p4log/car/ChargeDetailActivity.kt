package com.p4log.car

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 충전 상세: kWh·요금 히어로 + 통계 + 충전 속도 프로파일 차트 + 단가 수정 */
class ChargeDetailActivity : Activity() {

    companion object {
        /** UI 확인용 데모 (외부에서 도달 불가, charge_id로 -999를 넘겼을 때만) */
        const val DEMO_ID = -999L
    }

    private val dayFmt = SimpleDateFormat("M.d (E) HH:mm", Locale.KOREA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charge_detail)
        findViewById<TextView>(R.id.cd_back).setOnClickListener { finish() }

        val chargeId = intent.getLongExtra("charge_id", -1L)
        val s = if (chargeId == DEMO_ID) demoSession() else Db.get(this).chargeById(chargeId)
        if (s == null) { finish(); return }
        bind(s)

        findViewById<Button>(R.id.cd_edit_rate).setOnClickListener { showRateDialog(s) }
    }

    private fun bind(s: ChargeSession) {
        findViewById<TextView>(R.id.cd_title).text = dayFmt.format(Date(s.startTs)) + " 충전"

        val typeView = findViewById<TextView>(R.id.cd_type)
        typeView.text = if (s.type == "DC") "급속" else "완속"
        val fg = if (s.type == "DC") Color.parseColor("#FF7500") else Color.parseColor("#4DA3FF")
        val bg = if (s.type == "DC") Color.parseColor("#43290F") else Color.parseColor("#152C45")
        typeView.setTextColor(fg)
        typeView.backgroundTintList = ColorStateList.valueOf(bg)

        findViewById<TextView>(R.id.cd_kwh).text = Fmt.kwh(s.kwh)
        findViewById<TextView>(R.id.cd_cost).text = Fmt.won(s.cost)
        findViewById<TextView>(R.id.cd_time).text = Fmt.duration(s.startTs, s.endTs)
        findViewById<TextView>(R.id.cd_soc).text =
            if (s.socStart != null && s.socEnd != null)
                String.format("%.0f → %.0f%%", s.socStart, s.socEnd)
            else "-"
        findViewById<TextView>(R.id.cd_rate).text =
            if (s.kwh > 0.01) String.format("%.1f원/kWh", s.cost / s.kwh) else "-"
        findViewById<TextView>(R.id.cd_station).text = s.station ?: "미확인"

        // 충전 속도 프로파일: profile JSON [[분,kW],...]
        val pts = ArrayList<Pair<Double, Double>>()
        try {
            val arr = JSONArray(s.profile)
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                pts.add(p.optDouble(0, 0.0) to p.optDouble(1, 0.0))
            }
        } catch (e: Throwable) { /* 손상된 프로파일은 무시 */ }
        findViewById<ChargeProfileChartView>(R.id.cd_chart).setData(pts)
    }

    /** 단가 수정: 요금 재계산 + 충전소 프로필에 기억 (다음부터 자동 적용) */
    private fun showRateDialog(s: ChargeSession) {
        if (s.kwh <= 0.01) return
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(String.format("%.1f", s.cost / s.kwh))
        AlertDialog.Builder(this)
            .setTitle(s.station ?: "충전 단가 수정")
            .setMessage(
                "실제 낸 단가(원/kWh)를 입력하면 요금이 다시 계산됩니다." +
                    if (s.stLat != null) "\n이 충전소에서는 다음부터 이 단가가 자동 적용됩니다." else ""
            )
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val rate = input.text.toString().toDoubleOrNull()
                if (rate == null || rate <= 0) {
                    Toast.makeText(this, "단가를 올바르게 입력하세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (s.id == DEMO_ID) return@setPositiveButton
                val db = Db.get(this)
                db.updateChargeRate(s.id, s.kwh, rate)
                if (s.stLat != null && s.stLon != null) {
                    db.upsertStationProfile(s.stLat, s.stLon, s.station ?: "직접 지정 충전소", null, rate)
                }
                db.chargeById(s.id)?.let { bind(it) }
                Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** UI 점검용 샘플 데이터 */
    private fun demoSession(): ChargeSession {
        val now = System.currentTimeMillis()
        val profile = JSONArray("[[0,9],[1,34],[2,48],[3,51.5],[5,50.2],[8,49.1]," +
            "[12,44.0],[17,38.2],[22,33.5],[26,30.1],[30,27.8],[35,26.0]]")
        return ChargeSession(
            id = DEMO_ID, startTs = now - 35 * 60_000L, endTs = now,
            kwh = 28.7, cost = 10725.0, socStart = 36f, socEnd = 65f,
            maxKw = 51.5, type = "DC", profile = profile.toString(),
            station = "데모 충전소"
        )
    }
}
