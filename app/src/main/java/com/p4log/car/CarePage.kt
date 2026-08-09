package com.p4log.car

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** 차량 탭: 주차 위치 + 소모품 관리 */
class CarePage(private val activity: Activity, root: View) : PageController {

    private val tvParkWhen: TextView = root.findViewById(R.id.care_park_when)
    private val tvParkCoord: TextView = root.findViewById(R.id.care_park_coord)
    private val btnParkMap: Button = root.findViewById(R.id.care_park_map)
    private val listView: ListView = root.findViewById(R.id.care_list)

    private var items: List<Consumable> = emptyList()
    private var totalKm: Double = 0.0

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: activity.layoutInflater.inflate(R.layout.row_consumable, parent, false)
            val c = items[position]
            val usedKm = totalKm - c.baseKm
            val monthsUsed = (System.currentTimeMillis() - c.baseTs) / (30.44 * 24 * 3_600_000.0)

            v.findViewById<TextView>(R.id.rcons_name).text = c.name

            // 남은량(주 표시)과 세부(보조 표시)를 분리
            val detailParts = ArrayList<String>()
            var remainMain = "-"
            var ratio = 0.0
            if (c.cycleKm > 0) {
                val remain = c.cycleKm - usedKm
                remainMain = String.format("%,d km", Math.round(remain))
                detailParts.add(String.format("사용 %,d / %,d km", Math.round(usedKm), c.cycleKm))
                ratio = usedKm / c.cycleKm
            }
            if (c.cycleMonths > 0) {
                val remainM = c.cycleMonths - monthsUsed
                if (c.cycleKm <= 0) remainMain = String.format("%.1f개월", remainM)
                detailParts.add(String.format("%.1f개월 남음 (주기 %d개월)", remainM, c.cycleMonths))
                ratio = Math.max(ratio, monthsUsed / c.cycleMonths)
            }
            v.findViewById<TextView>(R.id.rcons_remain).text = remainMain
            v.findViewById<TextView>(R.id.rcons_detail).text = detailParts.joinToString("  ·  ")

            // 상태 칩 + 게이지 색: 여유(회색) / 곧 교체(노랑) / 교체 필요(빨강)
            val tvState = v.findViewById<TextView>(R.id.rcons_state)
            val stateFg: Int
            val stateBg: Int
            val barTint: Int
            when {
                ratio >= 1.0 -> {
                    tvState.text = "교체 필요"
                    stateFg = 0xFFFF5C5C.toInt(); stateBg = 0xFF3A1414.toInt(); barTint = 0xFFFF5C5C.toInt()
                }
                ratio >= 0.85 -> {
                    tvState.text = "곧 교체"
                    stateFg = 0xFFFFB020.toInt(); stateBg = 0xFF3A2A08.toInt(); barTint = 0xFFFFB020.toInt()
                }
                else -> {
                    tvState.text = "여유"
                    stateFg = 0xFFC9C9CE.toInt(); stateBg = 0xFF1C1C1E.toInt(); barTint = 0xFFFF7500.toInt()
                }
            }
            tvState.setTextColor(stateFg)
            tvState.backgroundTintList = android.content.res.ColorStateList.valueOf(stateBg)

            val bar = v.findViewById<ProgressBar>(R.id.rcons_bar)
            bar.max = 100
            bar.progress = Math.min(100, Math.max(0, (ratio * 100).toInt()))
            bar.progressTintList = android.content.res.ColorStateList.valueOf(barTint)

            v.findViewById<Button>(R.id.rcons_replace).setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle(c.name)
                    .setMessage("지금 교체한 것으로 기록할까요?\n사용량이 0부터 다시 계산됩니다.")
                    .setPositiveButton("교체함") { _, _ ->
                        Db.get(activity).resetConsumable(c.id, totalKm)
                        reload()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            v.setOnClickListener { showEditDialog(c) }
            return v
        }
    }

    init {
        listView.adapter = adapter
        btnParkMap.setOnClickListener {
            val p = Db.get(activity).parking()
            if (p == null) {
                Toast.makeText(activity, "주차 기록이 아직 없습니다 — 첫 주행이 끝나면 자동 저장됩니다", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val i = Intent(activity, MapWebActivity::class.java)
            i.putExtra("mode", "point")
            i.putExtra("lat", p.lat)
            i.putExtra("lon", p.lon)
            i.putExtra("title", "주차 위치")
            activity.startActivity(i)
        }
    }

    private fun showEditDialog(c: Consumable) {
        val input = EditText(activity)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "이미 사용한 km"
        val usedKm = Math.max(0.0, totalKm - c.baseKm)
        input.setText(Math.round(usedKm).toString())
        AlertDialog.Builder(activity)
            .setTitle("${c.name} — 사용량 보정")
            .setMessage("이 부품을 이미 사용한 거리를 입력하세요.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                if (v != null && v >= 0) {
                    Db.get(activity).setConsumableUsedKm(c.id, totalKm, v)
                    reload()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onShow() = reload()

    private fun reload() {
        totalKm = Prefs.totalKm(activity)
        items = Db.get(activity).consumables()
        adapter.notifyDataSetChanged()

        val p = Db.get(activity).parking()
        if (p == null) {
            tvParkWhen.text = "주차 기록 없음"
            tvParkCoord.text = "첫 주행이 끝나면 자동으로 저장됩니다"
            // 버튼은 눌리게 두고 클릭 시 이유를 토스트로 안내 (비활성화하면 "고장"으로 오해됨)
        } else {
            tvParkWhen.text = "주차 · " + Fmt.agoText(p.ts) +
                (if (p.socPct != null) " · 배터리 " + Fmt.pct(p.socPct) else "")
            tvParkCoord.text = String.format("%.5f, %.5f", p.lat, p.lon)
        }
    }
}
