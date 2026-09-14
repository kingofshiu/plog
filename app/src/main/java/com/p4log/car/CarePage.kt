package com.p4log.car

import android.app.AlertDialog
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView

/** 차량 탭: 소모품 관리. 주차 위치는 차 안에서 볼 이유가 없어 폰 앱에만 (사용자 결정 2026-09-15) */
class CarePage(private val host: AppHost, root: View) : PageController {
    private val ctx get() = host.context

    private val listView: ListView = root.findViewById(R.id.care_list)

    private var items: List<Consumable> = emptyList()
    private var totalKm: Double = 0.0

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(ctx).inflate(R.layout.row_consumable, parent, false)
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

            // 상태 글자 + 게이지 색 단계 (2026-09-13): 여유 = 흰색 / 곧 교체(85%↑) = 주황 / 교체 필요 = 빨강. 칩 없음
            val tvState = v.findViewById<TextView>(R.id.rcons_state)
            val tint: Int
            when {
                ratio >= 1.0 -> { tvState.text = "교체 필요"; tint = 0xFFFF5C5C.toInt() }
                ratio >= 0.85 -> { tvState.text = "곧 교체"; tint = 0xFFFF7500.toInt() }
                else -> { tvState.text = String.format("%.0f%% 사용", ratio * 100); tint = 0xFFF2F2F2.toInt() }
            }
            tvState.setTextColor(if (ratio >= 0.85) tint else 0xFF8E8E93.toInt())

            val bar = v.findViewById<ProgressBar>(R.id.rcons_bar)
            bar.max = 100
            bar.progress = Math.min(100, Math.max(0, (ratio * 100).toInt()))
            bar.progressTintList = android.content.res.ColorStateList.valueOf(tint)

            v.findViewById<Button>(R.id.rcons_replace).setOnClickListener {
                host.showDialog(
                    AlertDialog.Builder(ctx)
                        .setTitle(c.name)
                        .setMessage("지금 교체한 것으로 기록할까요?\n사용량이 0부터 다시 계산됩니다.")
                        .setPositiveButton("교체함") { _, _ ->
                            Db.get(ctx).resetConsumable(c.id, totalKm)
                            reload()
                        }
                        .setNegativeButton("취소", null)
                )
            }
            v.setOnClickListener { showEditDialog(c) }
            return v
        }
    }

    init {
        listView.adapter = adapter
    }

    private fun showEditDialog(c: Consumable) {
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "이미 사용한 km"
        val usedKm = Math.max(0.0, totalKm - c.baseKm)
        input.setText(Math.round(usedKm).toString())
        host.showDialog(
            AlertDialog.Builder(ctx)
                .setTitle("${c.name} — 사용량 보정")
                .setMessage("이 부품을 이미 사용한 거리를 입력하세요.")
                .setView(input)
                .setPositiveButton("저장") { _, _ ->
                    val v = input.text.toString().toDoubleOrNull()
                    if (v != null && v >= 0) {
                        Db.get(ctx).setConsumableUsedKm(c.id, totalKm, v)
                        reload()
                    }
                }
                .setNegativeButton("취소", null)
        )
    }

    override fun onShow() = reload()

    private fun reload() {
        totalKm = Prefs.totalKm(ctx)
        items = Db.get(ctx).consumables()
        adapter.notifyDataSetChanged()
    }
}
