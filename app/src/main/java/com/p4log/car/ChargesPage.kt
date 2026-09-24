package com.p4log.car

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

/**
 * 충전 기록 탭 (월별) v2 (2026-09-13): "이번 달 한눈에" 히어로(요금·충전량·kWh당, 숫자 카운트업) +
 * 급속/완속 kWh 비율 띠 + 목록(색 점 + 한 줄 요약). 주행 탭과 같은 문법.
 * 기간 탭 주간/월간/연간/전체 (2026-09-13, 사용자 요청 — 주행 기록 화면과 같은 방식).
 */
class ChargesPage(private val host: AppHost, root: View) : PageController {

    private val tvMonth: TextView = root.findViewById(R.id.charges_month)
    private val tabs: ViewGroup = root.findViewById(R.id.charges_tabs)
    private val btnPrev: View = root.findViewById(R.id.charges_prev)
    private val btnNext: View = root.findViewById(R.id.charges_next)
    private var period = MONTH
    private val tvCount: TextView = root.findViewById(R.id.charges_count)
    private val listView: ListView = root.findViewById(R.id.charges_list)
    private val tvEmpty: TextView = root.findViewById(R.id.charges_empty)
    private val summary: View = root.findViewById(R.id.charges_summary)
    private val splitDc: View = root.findViewById(R.id.charges_split_dc)
    private val splitAc: View = root.findViewById(R.id.charges_split_ac)
    private val tvSplitDc: TextView = root.findViewById(R.id.charges_split_dc_text)
    private val tvSplitAc: TextView = root.findViewById(R.id.charges_split_ac_text)
    private val splitHome: View = root.findViewById(R.id.charges_split_home)
    private val splitOut: View = root.findViewById(R.id.charges_split_out)
    private val tvSplitHome: TextView = root.findViewById(R.id.charges_split_home_text)
    private val tvSplitOut: TextView = root.findViewById(R.id.charges_split_out_text)

    private val twCost = NumberTween(root.findViewById(R.id.charges_cost)) { Fmt.won(it) }
    private val twKwh = NumberTween(root.findViewById(R.id.charges_kwh)) { Fmt.kwh(it) }
    private val twUnit = NumberTween(root.findViewById(R.id.charges_unit)) { String.format("%,d원", Math.round(it)) }

    private val cal: Calendar = Calendar.getInstance()
    private var items: List<ChargeSession> = emptyList()
    private val dayFmt = java.text.SimpleDateFormat("M.d (E) HH:mm", java.util.Locale.KOREA)
    private val dp = host.context.resources.displayMetrics.density

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(host.context).inflate(R.layout.row_charge, parent, false)
            val s = items[position]
            val dc = s.type == "DC"
            v.findViewById<TextView>(R.id.rc_when).text = dayFmt.format(java.util.Date(s.startTs))
            v.findViewById<TextView>(R.id.rc_kwh).text = Fmt.kwh(s.kwh)
            v.findViewById<TextView>(R.id.rc_cost).text = Fmt.won(s.cost)
            v.findViewById<View>(R.id.rc_dot).backgroundTintList = ColorStateList.valueOf(if (dc) ACCENT else BLUE)
            v.findViewById<TextView>(R.id.rc_detail).text = buildString {
                append(if (dc) "급속" else "완속")
                append("  ·  ")
                // 충전 위치 동네 이름 (2026-09-25, 주행 목록처럼). 없으면 지금 조회를 걸고 잠시 "위치 확인 중"
                if (s.place != null) { append(s.place); append("  ·  ") }
                else if (s.stLat != null && s.stLon != null) {
                    append("위치 확인 중…  ·  ")
                    PlaceNames.resolveChargeAsync(host.context, s.id, s.stLat, s.stLon) { p ->
                        if (p == null) return@resolveChargeAsync
                        items = items.map { if (it.id == s.id) it.copy(place = p) else it }
                        notifyDataSetChanged()
                    }
                }
                if (s.station != null) { append(s.station); append("  ·  ") }
                if (s.socStart != null && s.socEnd != null) {
                    append(String.format("%.0f → %.0f%%", s.socStart, s.socEnd)); append("  ·  ")
                }
                append(Fmt.duration(s.startTs, s.endTs))
                append("  ·  최대 ")
                append(Fmt.kw(s.maxKw))
            }
            return v
        }
    }

    init {
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> host.openCharge(items[position].id) }
        btnPrev.setOnClickListener { move(-1); reload(animate = true) }
        btnNext.setOnClickListener { move(1); reload(animate = true) }
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).setOnClickListener { selectTab(i) }
        selectTab(Prefs.chargesPeriod(host.context, MONTH).coerceIn(0, tabs.childCount - 1), animate = false)   // 마지막 선택 기억 (2026-09-25)
    }

    override fun onShow() = reload(animate = false)

    private fun move(dir: Int) {
        when (period) {
            WEEK -> cal.add(Calendar.DAY_OF_MONTH, 7 * dir)
            MONTH -> cal.add(Calendar.MONTH, dir)
            YEAR -> cal.add(Calendar.YEAR, dir)
        }
    }

    private fun selectTab(p: Int, animate: Boolean = true) {
        period = p
        Prefs.setChargesPeriod(host.context, p)
        for (i in 0 until tabs.childCount) {
            val tv = tabs.getChildAt(i) as TextView
            val sel = i == p
            tv.setTextColor(if (sel) Color.parseColor("#FF7500") else Color.parseColor("#C9C9CE"))
            if (sel) tv.setBackgroundResource(R.drawable.bg_tab_selected) else tv.background = null
            tv.setPadding((28 * dp).toInt(), (12 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
        }
        reload(animate)
    }

    /** 기간 [시작, 끝) — 주행 기록 화면과 같은 규칙 (주는 월요일 시작). 전체는 0 ~ MAX */
    private fun range(): LongArray {
        if (period == ALL) return longArrayOf(0L, Long.MAX_VALUE)
        if (period == MONTH) return Fmt.monthRange(cal)
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (period == WEEK) { c.firstDayOfWeek = Calendar.MONDAY; c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
        else { c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.MONTH, Calendar.JANUARY) }
        val from = c.timeInMillis
        if (period == WEEK) c.add(Calendar.DAY_OF_MONTH, 7) else c.add(Calendar.YEAR, 1)
        return longArrayOf(from, c.timeInMillis)
    }

    private fun periodLabel(r: LongArray): String {
        val loc = java.util.Locale.KOREA
        return when (period) {
            WEEK -> java.text.SimpleDateFormat("M.d", loc).format(java.util.Date(r[0])) + " ~ " +
                java.text.SimpleDateFormat("M.d", loc).format(java.util.Date(r[1] - 1))
            MONTH -> Fmt.monthTitle(cal)
            YEAR -> java.text.SimpleDateFormat("yyyy년", loc).format(java.util.Date(r[0]))
            else -> "전체 기간"
        }
    }

    /** 전환: 살짝 아래에서 떠오르며 나타남 (주행 기록 화면과 같은 연출) */
    private fun rise(v: View, dy: Float, ms: Long) {
        v.animate().cancel()
        v.alpha = 0f; v.translationY = dy * dp
        v.animate().alpha(1f).translationY(0f).setDuration(ms)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun reload(animate: Boolean) {
        val range = range()
        val db = Db.get(host.context)
        // 데모 모드면 가짜 4건 (DB 아님). 기간으로 걸러 실제와 같게
        val demoList = DashboardPage.demoChargeList()?.filter { it.startTs >= range[0] && it.startTs < range[1] }
        items = demoList ?: db.chargesBetween(range[0], range[1])
        adapter.notifyDataSetChanged()

        tvMonth.text = periodLabel(range)
        btnPrev.visibility = if (period == ALL) View.INVISIBLE else View.VISIBLE
        btnNext.visibility = btnPrev.visibility
        val n = items.size
        val nDc = items.count { it.type == "DC" }
        tvCount.text = if (n == 0) "" else String.format("%d회 · 급속 %d · 완속 %d", n, nDc, n - nDc)

        val kwh = items.sumOf { it.kwh }
        val cost = items.sumOf { it.cost }
        val dcKwh = items.filter { it.type == "DC" }.sumOf { it.kwh }
        val acKwh = kwh - dcKwh
        twCost.set(if (n > 0) cost else null, epsilon = 0.5)
        twKwh.set(if (n > 0) kwh else null)
        twUnit.set(if (kwh > 0.01) cost / kwh else null, epsilon = 0.5)

        // 급속/완속 비율 띠 (kWh 기준)
        val dcW = if (kwh > 0.01) (dcKwh / kwh).toFloat() else 0.5f
        (splitDc.layoutParams as LinearLayout.LayoutParams).weight = Math.max(0.02f, dcW)
        (splitAc.layoutParams as LinearLayout.LayoutParams).weight = Math.max(0.02f, 1f - dcW)
        splitDc.requestLayout()
        tvSplitDc.text = if (n > 0) String.format("급속 %s · %.0f%%", Fmt.kwh(dcKwh), dcW * 100) else "급속"
        tvSplitAc.text = if (n > 0) String.format("완속 %s · %.0f%%", Fmt.kwh(acKwh), (1 - dcW) * 100) else "완속"
        // 집·회사 / 외부 비율 띠 (2026-09-24)
        val homeKwh = items.filter { it.kind != null }.sumOf { it.kwh }
        val hW = if (kwh > 0.01) (homeKwh / kwh).toFloat() else 0.5f
        (splitHome.layoutParams as LinearLayout.LayoutParams).weight = Math.max(0.02f, hW)
        (splitOut.layoutParams as LinearLayout.LayoutParams).weight = Math.max(0.02f, 1f - hW)
        splitHome.requestLayout()
        tvSplitHome.text = if (n > 0) String.format("집·회사 %s · %.0f%%", Fmt.kwh(homeKwh), hW * 100) else "집·회사"
        tvSplitOut.text = if (n > 0) String.format("외부 %s · %.0f%%", Fmt.kwh(kwh - homeKwh), (1 - hW) * 100) else "외부"

        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (animate) { rise(summary, 10f, 240); rise(listView, 18f, 240) }
    }

    companion object {
        private const val WEEK = 0; private const val MONTH = 1; private const val YEAR = 2; private const val ALL = 3
        private const val ACCENT = 0xFFFF7500.toInt()
        private const val BLUE = 0xFF4DA3FF.toInt()
    }
}
