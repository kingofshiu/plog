package com.p4log.car

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

/**
 * 주행 기록 화면 v2 (2026-09-06, 사용자 요청).
 *  - 기간 탭: 일간 / 주간 / 월간 / 연간 / 전체 (◀ ▶ 로 기간 이동, 전체는 이동 없음)
 *  - 기간 요약: 운행 건수 · 총 거리 · 총 시간 · 배터리 사용(kWh) · 배터리 소모(%) · 회생 회수 · 전비
 *  - 왼쪽 목록을 누르면 **같은 화면 오른쪽**에 그 주행의 정보 + 경로 지도가 나온다 (새 창 아님)
 * Activity(TripsActivity)와 주행 중 오버레이 스택이 같이 쓴다. 주행 탭 표의 [전체 기록] 버튼으로 연다.
 */
object TripsScreen {
    private const val DAY = 0; private const val WEEK = 1; private const val MONTH = 2; private const val YEAR = 3; private const val ALL = 4

    @SuppressLint("SetJavaScriptEnabled")
    fun bind(host: AppHost, root: View): Boolean {
        val tabs = root.findViewById<ViewGroup>(R.id.tr_tabs)
        val tvPeriod = root.findViewById<TextView>(R.id.tr_period)
        val btnPrev = root.findViewById<View>(R.id.tr_prev)
        val btnNext = root.findViewById<View>(R.id.tr_next)
        val listView = root.findViewById<ListView>(R.id.tr_list)
        val tvEmpty = root.findViewById<TextView>(R.id.tr_empty)
        val detail = root.findViewById<View>(R.id.tr_d)
        val tvDetailEmpty = root.findViewById<View>(R.id.tr_d_empty)
        val web = root.findViewById<WebView>(R.id.tr_d_web)
        val ctx = host.context

        var period = MONTH
        val cal: Calendar = Calendar.getInstance()
        var items: List<Trip> = emptyList()
        var selectedId = Long.MIN_VALUE
        val dayFmt = java.text.SimpleDateFormat("M.d (E) HH:mm", java.util.Locale.KOREA)
        val fullFmt = java.text.SimpleDateFormat("yyyy.M.d (E) HH:mm", java.util.Locale.KOREA)

        // ---- 지도: 한 번만 로드하고, 주행을 고를 때마다 showTrip으로 다시 그린다 ----
        var mapReady = false
        var pendingPolyline: String? = null
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                pendingPolyline?.let { web.evaluateJavascript("showTrip($it);", null) }
                pendingPolyline = null
            }
        })
        web.loadUrl(Prefs.mapUrl(web.context))

        // 숫자 카운트업 (사용자 2026-09-08: 기간·주행이 바뀔 때 숫자가 굴러가게)
        fun tv(id: Int) = root.findViewById<TextView>(id)
        val twCount = NumberTween(tv(R.id.tr_s_count)) { String.format("%.0f건", it) }
        val twKm = NumberTween(tv(R.id.tr_s_km)) { Fmt.km(it) }
        val twTime = NumberTween(tv(R.id.tr_s_time)) { Fmt.duration(0L, (it * 60_000.0).toLong()) }
        val twKwh = NumberTween(tv(R.id.tr_s_kwh)) { Fmt.kwh(it) }
        val twSoc = NumberTween(tv(R.id.tr_s_soc)) { String.format("%.0f%%", it) }
        val twRegen = NumberTween(tv(R.id.tr_s_regen)) { Fmt.kwh(it) }
        val twEff = NumberTween(tv(R.id.tr_s_eff)) { Fmt.eff(it) }
        val dKm = NumberTween(tv(R.id.tr_d_km)) { Fmt.km(it) }
        val dTime = NumberTween(tv(R.id.tr_d_time)) { Fmt.duration(0L, (it * 60_000.0).toLong()) }
        val dEff = NumberTween(tv(R.id.tr_d_eff)) { Fmt.eff(it) }
        val dKwh = NumberTween(tv(R.id.tr_d_kwh)) { Fmt.kwh(it) }
        val dAvg = NumberTween(tv(R.id.tr_d_avg)) { String.format("%.0f km/h", it) }
        val dMax = NumberTween(tv(R.id.tr_d_max)) { String.format("%.0f km/h", it) }
        val dRegen = NumberTween(tv(R.id.tr_d_regen)) { Fmt.kwh(it) }
        val polyCache = HashMap<Long, String>()   // 선택한 주행의 경로만 DB에서 읽고 기억

        val dp = ctx.resources.displayMetrics.density
        /** 전환 애니메이션 (사용자 2026-09-06): 살짝 아래/오른쪽에서 떠오르며 나타남 */
        fun rise(v: View, dx: Float, dy: Float, ms: Long) {
            v.animate().cancel()
            v.alpha = 0f; v.translationX = dx * dp; v.translationY = dy * dp
            v.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(ms)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }

        fun showDetail(t: Trip, animate: Boolean = true) {
            val changed = selectedId != t.id
            selectedId = t.id
            tvDetailEmpty.visibility = View.GONE
            detail.visibility = View.VISIBLE
            if (animate && changed) rise(detail, 24f, 0f, 260)
            root.findViewById<TextView>(R.id.tr_d_title).text = fullFmt.format(java.util.Date(t.startTs)) + " 주행"
            dKm.set(t.distanceKm)
            dTime.set(((t.endTs - t.startTs) / 60_000L).toDouble(), epsilon = 0.5)
            dEff.set(t.effKmPerKwh)
            dKwh.set(t.energyKwh)
            val hasSoc = t.socStart != null && t.socEnd != null
            root.findViewById<TextView>(R.id.tr_d_soc).text =
                if (hasSoc) String.format("%.0f%% → %.0f%%", t.socStart, t.socEnd) else "-"
            (root.findViewById<View>(R.id.tr_d_soc).parent as View).visibility = if (hasSoc) View.VISIBLE else View.INVISIBLE
            dAvg.set(t.avgKmh, epsilon = 0.5)
            dMax.set(t.maxKmh, epsilon = 0.5)
            dRegen.set(if (t.regenKwh != null && t.regenKwh > 0.005) t.regenKwh else null)
            // 경로는 목록 조회에서 뺐으므로(가벼운 조회) 선택한 주행만 읽는다. 데모 주행은 메모리에 있음
            val poly = if (t.polyline.isNotBlank()) t.polyline
                else polyCache.getOrPut(t.id) { Db.get(ctx).tripPolyline(t.id)?.ifBlank { "[]" } ?: "[]" }
            if (mapReady) web.evaluateJavascript("showTrip($poly);", null) else pendingPolyline = poly
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.row_trip_list, parent, false)
                val t = items[position]
                v.findViewById<TextView>(R.id.rl_when).text = dayFmt.format(java.util.Date(t.startTs))
                // 부제 = 출발 → 도착 동네 (사용자 2026-09-06). 아직 없으면 조회를 걸고 "위치 확인 중"
                val sub = v.findViewById<TextView>(R.id.rl_sub)
                if (t.startPlace != null || t.endPlace != null) {
                    sub.text = (t.startPlace ?: "?") + "  →  " + (t.endPlace ?: "?")
                } else if (t.startLat != null || t.endLat != null) {
                    sub.text = "위치 확인 중…"
                    PlaceNames.resolveAsync(ctx, t.id, t.startLat, t.startLon, t.endLat, t.endLon) { sp, ep ->
                        if (sp == null && ep == null) return@resolveAsync
                        items = items.map { if (it.id == t.id) it.copy(startPlace = sp, endPlace = ep) else it }
                        notifyDataSetChanged()
                    }
                } else {
                    sub.text = Fmt.duration(t.startTs, t.endTs) + "  ·  위치 정보 없음"
                }
                v.findViewById<TextView>(R.id.rl_km).text = Fmt.km(t.distanceKm)
                v.setBackgroundColor(if (t.id == selectedId) Color.parseColor("#161618") else Color.TRANSPARENT)
                return v
            }
        }

        /** 기간 시작/끝 (끝은 배타). 전체는 0 ~ MAX */
        fun range(): LongArray {
            if (period == ALL) return longArrayOf(0L, Long.MAX_VALUE)
            val c = cal.clone() as Calendar
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            when (period) {
                WEEK -> { c.firstDayOfWeek = Calendar.MONDAY; c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
                MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
                YEAR -> { c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.MONTH, Calendar.JANUARY) }
            }
            val from = c.timeInMillis
            when (period) {
                DAY -> c.add(Calendar.DAY_OF_MONTH, 1)
                WEEK -> c.add(Calendar.DAY_OF_MONTH, 7)
                MONTH -> c.add(Calendar.MONTH, 1)
                YEAR -> c.add(Calendar.YEAR, 1)
            }
            return longArrayOf(from, c.timeInMillis)
        }

        fun periodLabel(r: LongArray): String {
            val loc = java.util.Locale.KOREA
            return when (period) {
                DAY -> java.text.SimpleDateFormat("yyyy.M.d (E)", loc).format(java.util.Date(r[0]))
                WEEK -> java.text.SimpleDateFormat("M.d", loc).format(java.util.Date(r[0])) + " ~ " +
                    java.text.SimpleDateFormat("M.d", loc).format(java.util.Date(r[1] - 1))
                MONTH -> Fmt.monthTitle(cal)
                YEAR -> java.text.SimpleDateFormat("yyyy년", loc).format(java.util.Date(r[0]))
                else -> "전체 기간"
            }
        }

        fun reload() {
            val r = range()
            // 데모 주행도 기간으로 걸러 실제와 같은 동작을 보인다 (2026-09-08: 데모가 기간 무관이라 "전부 일간 같다"는 오해가 있었음)
            items = DashboardPage.demoTripList()?.filter { it.startTs >= r[0] && it.startTs < r[1] }
                ?: Db.get(ctx).tripsBetweenLite(r[0], r[1])
            tvPeriod.text = periodLabel(r)
            btnPrev.visibility = if (period == ALL) View.INVISIBLE else View.VISIBLE
            btnNext.visibility = btnPrev.visibility

            // 기간 요약 (목록에서 직접 합산 — 시간·배터리 %는 DB 합계 쿼리에 없다)
            var km = 0.0; var ms = 0L; var kwh = 0.0; var soc = 0.0; var regen = 0.0
            for (t in items) {
                km += t.distanceKm; ms += (t.endTs - t.startTs)
                if (t.energyKwh != null) kwh += t.energyKwh
                if (t.socStart != null && t.socEnd != null) soc += (t.socStart - t.socEnd)
                if (t.regenKwh != null) regen += t.regenKwh
            }
            twCount.set(items.size.toDouble(), epsilon = 0.5)
            twKm.set(km)
            twTime.set(if (items.isEmpty()) null else (ms / 60_000L).toDouble(), epsilon = 0.5)
            twKwh.set(if (kwh > 0.005) kwh else null)
            twSoc.set(if (soc > 0.5) soc else null, epsilon = 0.5)
            // 배터리 %가 없는 주행뿐이면 "-" 칸을 보이지 않게 (2026-09-13)
            (tv(R.id.tr_s_soc).parent as View).visibility = if (soc > 0.5) View.VISIBLE else View.GONE
            twRegen.set(if (regen > 0.005) regen else null)
            twEff.set(if (kwh > 0.05) km / kwh else null)

            tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            rise(root.findViewById(R.id.tr_summary), 0f, 10f, 240)
            // 선택 유지, 없으면 첫 주행을 자동 선택
            val keep = items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
            rise(listView, 0f, 18f, 240)
            if (keep != null) showDetail(keep) else {
                selectedId = Long.MIN_VALUE
                detail.visibility = View.GONE
                tvDetailEmpty.visibility = View.VISIBLE
            }
            adapter.notifyDataSetChanged()
        }

        fun selectTab(p: Int) {
            period = p
            for (i in 0 until tabs.childCount) {
                val tv = tabs.getChildAt(i) as TextView
                val sel = i == p
                tv.setTextColor(if (sel) Color.parseColor("#FF7500") else Color.parseColor("#C9C9CE"))
                if (sel) tv.setBackgroundResource(R.drawable.bg_tab_selected) else tv.background = null
                tv.setPadding((28 * dp).toInt(), (12 * dp).toInt(), (28 * dp).toInt(), (12 * dp).toInt())
            }
            reload()
        }

        // 개인 최고 기록 한 줄 (2026-09-15): 최고 전비(5km 이상 주행) · 최장 주행. 데모는 데모 목록에서
        run {
            val all = DashboardPage.demoTripList()
            val best = all?.filter { it.distanceKm >= 5 && it.effKmPerKwh != null }?.maxByOrNull { it.effKmPerKwh!! } ?: if (all == null) Db.get(ctx).bestEffTrip(5000.0) else null
            val longest = all?.maxByOrNull { it.distanceM } ?: if (all == null) Db.get(ctx).longestTrip() else null
            val tvBest = root.findViewById<TextView>(R.id.tr_best)
            val md = java.text.SimpleDateFormat("M.d", java.util.Locale.KOREA)
            val parts = ArrayList<String>()
            if (best?.effKmPerKwh != null) parts.add("최고 전비 " + String.format("%.1f", best.effKmPerKwh) + " km/kWh (" + md.format(java.util.Date(best.startTs)) + ")")
            if (longest != null) parts.add("최장 주행 " + Fmt.km(longest.distanceKm) + " (" + md.format(java.util.Date(longest.startTs)) + ")")
            tvBest.text = "최고 기록  ·  " + parts.joinToString("  ·  ")
            tvBest.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).setOnClickListener { selectTab(i) }
        root.findViewById<View>(R.id.tr_back).setOnClickListener { host.back() }
        btnPrev.setOnClickListener {
            when (period) { DAY -> cal.add(Calendar.DAY_OF_MONTH, -1); WEEK -> cal.add(Calendar.DAY_OF_MONTH, -7)
                MONTH -> cal.add(Calendar.MONTH, -1); YEAR -> cal.add(Calendar.YEAR, -1) }
            reload()
        }
        btnNext.setOnClickListener {
            when (period) { DAY -> cal.add(Calendar.DAY_OF_MONTH, 1); WEEK -> cal.add(Calendar.DAY_OF_MONTH, 7)
                MONTH -> cal.add(Calendar.MONTH, 1); YEAR -> cal.add(Calendar.YEAR, 1) }
            reload()
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            showDetail(items[position]); adapter.notifyDataSetChanged()
        }
        selectTab(MONTH)
        return true
    }
}
