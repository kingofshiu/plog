package com.p4log.car

import android.annotation.SuppressLint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import java.util.Calendar

/**
 * 주행 탭: 실시간 상태 + 상황별 하단 패널 + 주행 기록.
 * MainActivity 탭과 주행 중 오버레이 창(DriveOverlay)이 같이 쓴다 (2026-09-05).
 * v8 (2026-09-06): 테슬라 에너지 앱·폴스타 순정 화면을 참고한 박스 없는 타이포 레이아웃.
 * v14 (2026-09-15, 사용자 "더 센스 있게"): 하단이 상황을 따라간다 —
 *   주행 중 = 실시간 전력 | 충전 사이클 | 지금 달리는 경로 라이브 지도
 *   주차 중 = 이번 주·이번 달 요약 | 충전 사이클 | 오늘 타임라인 + 주행 기록 표
 *   + 새 기록(최고 전비·최장 주행·누적 1,000km 단위) 달성 시 상태 줄에 10분간 표시.
 */
class DashboardPage(private val host: AppHost, root: View) : PageController {

    private val tvSocBig: TextView = root.findViewById(R.id.dash_soc_big)
    private val tvSocCaption: TextView = root.findViewById(R.id.dash_soc_caption)
    private val socBar: SocBarView = root.findViewById(R.id.dash_soc_bar)
    private var dotAnim: android.animation.ObjectAnimator? = null   // 상태 점 숨쉬기 (주행/충전 중만)
    private val tvRange: TextView = root.findViewById(R.id.dash_range)
    private val tvSpeed: TextView = root.findViewById(R.id.dash_speed)
    private val tvPower: TextView = root.findViewById(R.id.dash_power)
    private val tvPowerLabel: TextView = root.findViewById(R.id.dash_power_label)
    private val tvTripKm: TextView = root.findViewById(R.id.dash_trip_km)
    private val tvTripEff: TextView = root.findViewById(R.id.dash_trip_eff)
    private val tvToday: TextView = root.findViewById(R.id.dash_today)
    private val tvEff: TextView = root.findViewById(R.id.dash_eff)
    private val tvTotal: TextView = root.findViewById(R.id.dash_total)
    private val tvTotalLabel: TextView = root.findViewById(R.id.dash_total_label)
    private val tvHero1Label: TextView = root.findViewById(R.id.dash_hero1_label)
    private val tvHero2Label: TextView = root.findViewById(R.id.dash_hero2_label)
    private val tvHero3Label: TextView = root.findViewById(R.id.dash_hero3_label)
    private val tvStateLine: TextView = root.findViewById(R.id.dash_state_line)
    private val tvClock: TextView = root.findViewById(R.id.dash_clock)
    private val tvDate: TextView = root.findViewById(R.id.dash_date)
    private val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
    private val dateFmt = java.text.SimpleDateFormat("M월 d일 (E)", java.util.Locale.KOREA)
    private var lastClockMin = -1L
    private val stateDot: View = root.findViewById(R.id.dash_state_dot)
    private val tvWarn: TextView = root.findViewById(R.id.dash_warn_card)
    private val powerChart: PowerMeterView = root.findViewById(R.id.dash_power_chart)
    // 충전 사이클 패널 (2026-09-15 확장): 충전 이후 km · 배터리 사용 · 회생 회수 비율(주인공은 유지, 2026-09-08 사용자 결정)
    private val regenChart: RatioMeterView = root.findViewById(R.id.dash_regen_chart)
    private val tvCycleTitle: TextView = root.findViewById(R.id.dash_cycle_title)
    private val tvCycleKm: TextView = root.findViewById(R.id.dash_cycle_km)
    private val tvCycleSub: TextView = root.findViewById(R.id.dash_cycle_sub)
    private val tvCycleRatio: TextView = root.findViewById(R.id.dash_cycle_ratio)
    private val tvCycleKwh: TextView = root.findViewById(R.id.dash_cycle_kwh)
    private var shownRatio = -1f
    private var ratioAnim: android.animation.ValueAnimator? = null
    private val sinceFmt = java.text.SimpleDateFormat("M.d HH:mm", java.util.Locale.KOREA)
    private val tvRegenTrip: TextView = root.findViewById(R.id.dash_regen_trip)
    private val tvRegenTripLabel: TextView = root.findViewById(R.id.dash_regen_trip_label)
    private val tvRegenToday: TextView = root.findViewById(R.id.dash_regen_today)
    // 3열: 표 / 라이브 지도
    private val recentList: android.widget.LinearLayout = root.findViewById(R.id.dash_recent_list)
    private val tvRecentEmpty: TextView = root.findViewById(R.id.dash_recent_empty)
    private val timeline: DayTimelineView = root.findViewById(R.id.dash_timeline)
    private val colPower: View = root.findViewById(R.id.dash_col_power)
    private val colWeek: View = root.findViewById(R.id.dash_col_week)
    private val colTable: View = root.findViewById(R.id.dash_col_table)
    private val colMap: View = root.findViewById(R.id.dash_col_map)
    private val liveMap: WebView = root.findViewById(R.id.dash_live_map)
    // 1열 주차 모드: 이번 주·이번 달
    private val tvWeekKm: TextView = root.findViewById(R.id.dash_week_km)
    private val tvWeekSub: TextView = root.findViewById(R.id.dash_week_sub)
    private val tvWeekCmp: TextView = root.findViewById(R.id.dash_week_cmp)
    private val tvMonthKm: TextView = root.findViewById(R.id.dash_month_km)
    private val tvMonthSub: TextView = root.findViewById(R.id.dash_month_sub)
    private var seenTripCount = -1
    private val dayFmt = java.text.SimpleDateFormat("M.d (E) HH:mm", java.util.Locale.KOREA)  // 최근 주행 한 줄용

    init {
        tvWarn.setOnClickListener { host.openDiagnostics() }
        root.findViewById<View>(R.id.dash_all_trips).setOnClickListener { host.openTrips() }
        powerChart.setMode(PowerMeterView.MODE_CONSUME)
    }

    // 실시간 구독 값 (2026-09-13): 콜백이 오면 폴링을 기다리지 않고 속도·전력 칸과 게이지를 바로 갱신
    @Volatile private var liveSpeed: Float? = null
    @Volatile private var liveKw: Float? = null
    private var liveTs = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var liveStatusLogged = false

    private var liveWanted = false

    override fun onShow() {
        seenTripCount = -1
        if (!demo) { liveWanted = true; subscribeLive(0) }
        onTick()
        if (drivingMode) startLiveMap()
    }

    /** 앱을 막 켰을 땐 서비스가 아직 안 떠 있어 "서비스 없음"이 나온다 → 1.5초 간격으로 몇 번 다시 시도 */
    private fun subscribeLive(attempt: Int) {
        if (!liveWanted) return
        val r = LoggerService.liveSubscribe { name, v ->
            when (name) {
                "PERF_VEHICLE_SPEED" -> liveSpeed = v
                "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" -> liveKw = v
            }
            liveTs = System.currentTimeMillis()
            main.post { renderLive() }
        }
        if (r == "서비스 없음" && attempt < 6) { main.postDelayed({ subscribeLive(attempt + 1) }, 1500); return }
        if (!liveStatusLogged) { liveStatusLogged = true; ServiceLog.add(host.context, "실시간 구독: " + r) }
    }

    override fun onHide() {
        liveWanted = false
        LoggerService.liveUnsubscribe()
        liveSpeed = null; liveKw = null
        stopLiveMap()
    }

    /** 구독 이벤트 → 속도·실시간 전력만 즉시 그린다 (나머지는 1초 티커) */
    private fun renderLive() {
        if (demo) return
        val s = LoggerService.lastSnapshot
        if (heroParkedNow(s)) return
        liveSpeed?.let { sp ->
            val gpsV = s.gpsSpeedKmh
            // 구독 속도가 융합 속도(바퀴 포함)나 GPS보다 작으면(폴스타4 0 고정) 무시 — 터널에서 0으로 떨어지지 않게
            val fused = s.speedKmh ?: 0f
            if (sp >= fused && (gpsV == null || sp >= gpsV)) { speedUnit = "km/h"; speedTween.set(sp.toDouble(), formatKey = speedUnit, epsilon = 0.5) }
        }
        liveKw?.let { kw ->
            val consume = noNegZero(if (s.chargeActive) kw.coerceAtLeast(0f) else (-kw).coerceAtLeast(0f))
            powerTween.set(consume.toDouble())
            powerChart.setValues(consume, s.tripConsumeAvgKw, s.tripConsumePeakKw)
        }
    }

    /** "64" + 작은 회색 " km/h" — 큰 숫자 옆에 단위를 작게 붙인다 (v8) */
    private fun big(value: String, unit: String): CharSequence {
        val s = SpannableString(value + " " + unit)
        val from = value.length
        s.setSpan(RelativeSizeSpan(0.4f), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(GRAY), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    // 히어로 숫자 카운트업 — 공용 NumberTween (단위는 big()으로 작은 회색 span)
    private val speedTween = NumberTween(tvSpeed) { big(String.format("%.0f", it), speedUnit) }
    private var speedUnit = "km/h"
    private val powerTween = NumberTween(tvPower) { big(String.format("%.1f", it), "kW") }
    private val tripKmTween = NumberTween(tvTripKm) { big(String.format("%.1f", it), "km") }
    private val tripEffTween = NumberTween(tvTripEff) { big(String.format("%.1f", it), "km/kWh") }
    // 주차 중 히어로 (2026-09-13): 마지막 주행 거리 · 전비 · 평소 대비 % — 같은 칸을 다른 형식으로 쓴다
    private val lastKmTween = NumberTween(tvSpeed) { big(String.format("%.1f", it), "km") }
    private val lastEffTween = NumberTween(tvTripKm) { big(String.format("%.1f", it), "km/kWh") }
    private val lastDeltaTween = NumberTween(tvTripEff) { big((if (it >= 0) "+" else "−") + String.format("%.0f", Math.abs(it)), "%") }
    // 충전 사이클 · 주간/월간 요약 카운트업 (2026-09-15)
    private val cycleKmTween = NumberTween(tvCycleKm) { big(String.format("%.0f", it), "km") }
    private val weekKmTween = NumberTween(tvWeekKm) { big(String.format("%.1f", it), "km") }
    private val monthKmTween = NumberTween(tvMonthKm) { big(String.format("%.1f", it), "km") }
    private var heroParked = false          // 지금 히어로가 주차 모드로 그려져 있는지
    private var lastTrip: Trip? = null       // 마지막 저장 주행 (tripSavedCount가 바뀔 때만 다시 읽음)
    private var usualEff: Double? = null     // 최근 30일 평소 전비
    private val heroIcons = intArrayOf(R.drawable.ic_st_speed, R.drawable.ic_st_dist, R.drawable.ic_st_eff)
    private val heroIconsParked = intArrayOf(R.drawable.ic_st_log, R.drawable.ic_st_eff, R.drawable.ic_st_compare)

    private fun setHeroLabel(tv: TextView, text: String, icon: Int) {
        tv.text = text
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    /** (-0.0f).coerceAtLeast(0f)는 -0.0을 돌려줘 "-0.0 kW"로 찍힌다 (주차 중 화면에서 발견, 2026-09-13) */
    private fun noNegZero(v: Float): Float = if (v <= 0f) 0f else v

    private fun heroParkedNow(s: StatusSnapshot): Boolean = lastTripForHero(s) != null && !s.chargeActive

    /** 주행이 끝난 뒤 6시간 안이면 "방금 주행" 요약을 히어로에 (요즘 앱 감성: 비교와 한마디). 그 뒤엔 다시 빈 칸 */
    private fun lastTripForHero(s: StatusSnapshot): Trip? {
        if (s.tripActive) return null
        val t = lastTrip ?: return null
        return if (System.currentTimeMillis() - t.endTs < 6 * 3_600_000L) t else null
    }

    /** 회수 비율 큰 숫자 카운트업 (2026-09-08): 바뀔 때 600ms 동안 이전 값에서 새 값으로 굴러간다 */
    private fun animateRatio(target: Float?) {
        if (target == null) { ratioAnim?.cancel(); shownRatio = -1f; tvCycleRatio.text = "-"; return }
        if (shownRatio < 0f) { shownRatio = target; tvCycleRatio.text = String.format("%.0f", target); return }
        if (Math.abs(shownRatio - target) < 0.05f) return
        ratioAnim?.cancel()
        val from = shownRatio
        val a = android.animation.ValueAnimator.ofFloat(from, target)
        a.duration = 600
        a.interpolator = android.view.animation.DecelerateInterpolator(1.8f)
        a.addUpdateListener { va ->
            shownRatio = va.animatedValue as Float
            tvCycleRatio.text = String.format("%.0f", shownRatio)
        }
        a.start(); ratioAnim = a
    }

    // ---------- 주행/주차 모드 (v14) ----------
    private var drivingMode = false
    private var mapLoaded = false
    private var liveMapRunning = false
    private val liveMapTick = object : Runnable {
        override fun run() {
            if (!liveMapRunning) return
            val poly = if (demo) demoTrips()[0].polyline else LoggerService.currentTripPolyline()
            if (mapLoaded && poly != null) liveMap.evaluateJavascript("liveTrip($poly);", null)
            main.postDelayed(this, 5000)
        }
    }

    /** 라이브 지도는 주행 중 + 화면이 보일 때만 돈다 (5초마다 경로 다시 그림). 지도는 처음 필요할 때 한 번만 로드 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startLiveMap() {
        if (liveMapRunning) return
        liveMapRunning = true
        if (!mapLoaded) {
            liveMap.settings.javaScriptEnabled = true
            liveMap.settings.domStorageEnabled = true
            liveMap.setWebViewClient(object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { mapLoaded = true }
            })
            liveMap.loadUrl(Prefs.mapUrl(liveMap.context))
        }
        main.removeCallbacks(liveMapTick)
        main.postDelayed(liveMapTick, 600)
    }

    private fun stopLiveMap() {
        liveMapRunning = false
        main.removeCallbacks(liveMapTick)
    }

    private fun applyMode(driving: Boolean, force: Boolean = false) {
        if (driving == drivingMode && !force) return
        drivingMode = driving
        colPower.visibility = if (driving) View.VISIBLE else View.GONE
        colMap.visibility = if (driving) View.VISIBLE else View.GONE
        colWeek.visibility = if (driving) View.GONE else View.VISIBLE
        colTable.visibility = if (driving) View.GONE else View.VISIBLE
        if (driving) { startLiveMap(); liveMap.evaluateJavascript("liveZoomed=false;", null) } else { stopLiveMap(); reloadWeek() }
        // 전환 연출: 새로 보이는 칸이 살짝 떠오른다
        for (v in if (driving) listOf(colPower, colMap) else listOf(colWeek, colTable)) {
            v.alpha = 0f; v.translationY = 16f * host.context.resources.displayMetrics.density
            v.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /** 이번 주(월요일 시작)·지난주·이번 달 합계 (주차 중 1열). 주행이 저장될 때와 모드가 바뀔 때만 계산 */
    private fun reloadWeek() {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.firstDayOfWeek = Calendar.MONDAY; c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val weekStart = c.timeInMillis
        val prevWeekStart = weekStart - 7L * 86_400_000L
        val m = Calendar.getInstance(); m.set(Calendar.DAY_OF_MONTH, 1); m.set(Calendar.HOUR_OF_DAY, 0); m.set(Calendar.MINUTE, 0); m.set(Calendar.SECOND, 0); m.set(Calendar.MILLISECOND, 0)
        val monthStart = m.timeInMillis
        fun totals(from: Long, to: Long): DoubleArray {
            if (demo) {
                val list = demoTrips().filter { it.startTs >= from && it.startTs < to }
                return doubleArrayOf(list.size.toDouble(), list.sumOf { it.distanceM }, list.sumOf { it.energyKwh ?: 0.0 }, list.sumOf { it.regenKwh ?: 0.0 })
            }
            return Db.get(host.context).tripTotals(from, to)
        }
        val w = totals(weekStart, Long.MAX_VALUE)
        val pw = totals(prevWeekStart, weekStart)
        val mo = totals(monthStart, Long.MAX_VALUE)
        val wKm = w[1] / 1000.0; val pKm = pw[1] / 1000.0; val mKm = mo[1] / 1000.0
        weekKmTween.set(wKm)
        tvWeekSub.text = String.format("%d회", w[0].toInt()) + (if (w[2] > 0.3) String.format(" · 전비 %.1f km/kWh", wKm / w[2]) else "") +
            (if (w[3] > 0.005) String.format(" · 회생 %.1f kWh", w[3]) else "")
        if (pKm > 0.5) {
            val d = (wKm / pKm - 1.0) * 100.0
            tvWeekCmp.text = if (Math.abs(d) < 1) "지난주와 비슷" else String.format("지난주보다 %s%.0f%%", if (d >= 0) "+" else "−", Math.abs(d))
            val col = if (d >= 0) GREEN else GRAY
            tvWeekCmp.setTextColor(col); tvWeekCmp.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(col)
            tvWeekCmp.visibility = View.VISIBLE
        } else tvWeekCmp.visibility = View.INVISIBLE
        monthKmTween.set(mKm)
        tvMonthSub.text = String.format("%d회", mo[0].toInt()) + (if (mo[2] > 0.3) String.format(" · 전비 %.1f km/kWh", mKm / mo[2]) else "")
        if (now < 0) return
    }

    // ---------- 새 기록 (v14) ----------
    private var achievement: String? = null
    private var achievementUntil = 0L

    /** 방금 저장된 주행이 개인 최고 기록이면 상태 줄에 10분 동안 "새 기록"을 띄운다 (주행당 한 번, Prefs에 기억) */
    private fun checkAchievement(trips: List<Trip>) {
        val last = trips.firstOrNull() ?: return
        if (demo) return
        val ctx = host.context
        if (last.id == Prefs.lastAchievedTripId(ctx)) return
        if (System.currentTimeMillis() - last.endTs > 6 * 3_600_000L) { Prefs.setLastAchievedTripId(ctx, last.id); return }
        val db = Db.get(ctx)
        val parts = ArrayList<String>()
        val best = db.bestEffTrip(5000.0)
        if (best != null && best.id == last.id && best.effKmPerKwh != null) parts.add(String.format("최고 전비 %.1f km/kWh", best.effKmPerKwh))
        val longest = db.longestTrip()
        if (longest != null && longest.id == last.id && trips.size > 1) parts.add("최장 주행 " + Fmt.km(last.distanceKm))
        val total = Prefs.totalKm(ctx)
        if (Prefs.hasTotalKm(ctx) && Math.floor(total / 1000.0) > Math.floor((total - last.distanceKm) / 1000.0))
            parts.add(String.format("누적 %,d km 돌파", (Math.floor(total / 1000.0) * 1000).toLong()))
        Prefs.setLastAchievedTripId(ctx, last.id)
        if (parts.isEmpty()) return
        achievement = "새 기록 · " + parts.joinToString(" · ")
        achievementUntil = System.currentTimeMillis() + 10 * 60_000L
        ServiceLog.add(ctx, "새 기록: " + parts.joinToString(" · "))
    }

    /** 최근 주행 표: 전비 숫자 + 표 안 최대 전비 대비 인라인 막대 (주행이 새로 저장될 때만 다시 읽는다) */
    private fun reloadHistory() {
        val trips = if (demo) demoTrips() else Db.get(host.context).recentTrips(6)
        lastTrip = trips.firstOrNull()
        usualEff = if (demo) 5.9 else {
            val u = Db.get(host.context).tripTotals(System.currentTimeMillis() - 30L * 86_400_000L, Long.MAX_VALUE)
            if (u[2] > 0.5 && u[1] > 500.0) u[1] / 1000.0 / u[2] else null
        }
        checkAchievement(trips)
        recentList.removeAllViews()
        tvRecentEmpty.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
        val inflater = android.view.LayoutInflater.from(host.context)
        if (trips.isEmpty()) {
            // 첫 실행 빈 화면: 앞으로 채워질 자리를 흐리게 미리 보여준다 (2026-09-15)
            for (i in 0 until 3) {
                val v = inflater.inflate(R.layout.row_trip_compact, recentList, false)
                v.findViewById<TextView>(R.id.rtc_when).text = "— (—) —:—"
                v.findViewById<TextView>(R.id.rtc_km).text = "— km"
                v.findViewById<TextView>(R.id.rtc_eff).text = "—"
                v.findViewById<android.widget.ProgressBar>(R.id.rtc_bar).progress = 0
                v.alpha = 0.25f
                recentList.addView(v)
            }
        }
        val maxEff = trips.mapNotNull { it.effKmPerKwh }.maxOrNull() ?: 0.0
        for ((i, t) in trips.withIndex()) {
            val v = inflater.inflate(R.layout.row_trip_compact, recentList, false)
            v.findViewById<TextView>(R.id.rtc_when).text = dayFmt.format(java.util.Date(t.startTs))
            v.findViewById<TextView>(R.id.rtc_km).text = Fmt.km(t.distanceKm)
            val eff = t.effKmPerKwh
            val tvEffRow = v.findViewById<TextView>(R.id.rtc_eff)
            val bar = v.findViewById<android.widget.ProgressBar>(R.id.rtc_bar)
            tvEffRow.text = if (eff != null) String.format("%.1f", eff) else "-"
            bar.progress = if (eff != null && maxEff > 0) Math.round(eff / maxEff * 100).toInt() else 0
            // 최신 한 건만 주황으로 강조, 나머지는 회색 막대
            val latest = i == 0
            bar.progressTintList = android.content.res.ColorStateList.valueOf(if (latest) ACCENT else 0xFF5A5A60.toInt())
            tvEffRow.setTextColor(if (latest) ACCENT else 0xFFF2F2F2.toInt())
            v.findViewById<TextView>(R.id.rtc_regen).text =
                if (t.regenKwh != null && t.regenKwh > 0.005) Fmt.kwh(t.regenKwh) else ""
            v.setOnClickListener { host.openTrip(t.id) }
            recentList.addView(v)
        }
        // 오늘의 타임라인: 오늘 시작한 주행들
        val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val dayStart = c.timeInMillis
        val today = if (demo) demoTrips().filter { it.startTs >= dayStart } else Db.get(host.context).tripsBetweenLite(dayStart, Long.MAX_VALUE)
        timeline.set(today.map { longArrayOf(it.startTs, it.endTs) }, dayStart, System.currentTimeMillis())
        if (!drivingMode) reloadWeek()
    }

    override fun onTick() {
        val s = if (demo) demoSnapshot() else LoggerService.lastSnapshot
        if (LoggerService.tripSavedCount != seenTripCount) {
            seenTripCount = LoggerService.tripSavedCount
            reloadHistory()
        }
        // 하단 패널 모드: 주행 중이면 실시간 전력 + 라이브 지도, 아니면 요약 + 기록 표 (v14)
        applyMode(s.tripActive, force = seenTripCount == LoggerService.tripSavedCount && !modeApplied)
        modeApplied = true

        // 시계 (2026-09-15): 분이 바뀔 때만 다시 쓴다
        val nowMin = System.currentTimeMillis() / 60_000L
        if (nowMin != lastClockMin) {
            lastClockMin = nowMin
            val d = java.util.Date()
            tvClock.text = clockFmt.format(d)
            tvDate.text = dateFmt.format(d)
        }

        // 배터리: 큰 숫자 + 얇은 라인 (충전 중 초록)
        val pct = s.socPct
        tvSocBig.text = if (pct != null) String.format("%.0f", pct) else "--"
        socBar.set(pct, s.chargeActive)
        tvSocCaption.text = if (s.chargeActive) "배터리 · 충전 중" else "배터리"
        tvSocCaption.setTextColor(if (s.chargeActive) GREEN else GRAY)
        tvRange.text = if (s.rangeKm != null) Fmt.kmInt(s.rangeKm.toDouble()) else "-"

        // 차량 속도가 0으로 고정 보고될 수 있어 GPS 속도와 비교해 큰 쪽을 표시. 구독 값이 3초 이내면 그것이 최신
        val fresh = System.currentTimeMillis() - liveTs < 3000
        // 스냅샷 speedKmh는 차량·GPS·바퀴 융합값. 구독 속도(폴스타4는 0 고정)는 그보다 클 때만 채택 → 터널에서 0으로 안 떨어진다
        val fused = s.speedKmh
        val carV = if (fresh && liveSpeed != null) maxOf(liveSpeed!!, fused ?: 0f) else fused
        val gpsV = s.gpsSpeedKmh
        if (!heroParkedNow(s)) when {
            carV != null && (gpsV == null || carV >= gpsV) -> { speedUnit = "km/h"; speedTween.set(carV.toDouble(), formatKey = speedUnit, epsilon = 0.5) }
            gpsV != null -> { speedUnit = "km/h · GPS"; speedTween.set(gpsV.toDouble(), formatKey = speedUnit, epsilon = 0.5) }
            else -> speedTween.set(null)
        }

        // 실시간 전력 = 배터리에서 나가는 소비 전력만. 회생은 옆 칸에 따로 (섞지 않는다, 사용자 결정 2026-09-06)
        val kw = if (fresh) liveKw ?: s.chargeRateKw else s.chargeRateKw
        // 충전 중엔 들어오는 전력(+)을 초록으로, 아니면 나가는 소비 전력을 주황으로 (사용자 2026-09-08 "충전 중이 보이게")
        val consumeKw = if (kw == null) null else noNegZero(if (s.chargeActive) kw.coerceAtLeast(0f) else (-kw).coerceAtLeast(0f))
        powerTween.set(consumeKw?.toDouble())
        tvPowerLabel.text = if (s.chargeActive) "실시간 전력 · 충전 중" else "실시간 전력 · 소비"
        tvPower.setTextColor(if (s.chargeActive) GREEN else ACCENT)
        tvPowerLabel.setTextColor(if (s.chargeActive) GREEN else GRAY)
        powerChart.setAccent(if (s.chargeActive) GREEN else null)
        // 계기: 지금 값 + 이번 주행 전체 평균·최고 (사용자: "10분 말고 전체 평균·최고", 2026-09-06)
        powerChart.setValues(consumeKw, s.tripConsumeAvgKw, s.tripConsumePeakKw)

        // 히어로 둘째·셋째 칸: 이번 주행 거리 · 이번 주행 전비 (아래 칸과 겹치는 전력·회생은 뺌, 사용자 2026-09-08)
        // 주차 중이고 최근 주행이 있으면 세 칸을 "마지막 주행 · 전비 · 평소 대비"로 바꿔 빈 칸 대신 요약을 보여준다 (2026-09-13)
        val recent = lastTripForHero(s)
        val parked = recent != null && !s.chargeActive
        if (parked != heroParked) {
            heroParked = parked
            val icons = if (parked) heroIconsParked else heroIcons
            setHeroLabel(tvHero1Label, if (parked) "마지막 주행" else "속도", icons[0])
            setHeroLabel(tvHero2Label, if (parked) "전비" else "이번 주행", icons[1])
            setHeroLabel(tvHero3Label, if (parked) "평소 대비" else "이번 주행 전비", icons[2])
            speedTween.set(null); tripKmTween.set(null); tripEffTween.set(null)
            lastKmTween.set(null); lastEffTween.set(null); lastDeltaTween.set(null)
            tvTripEff.setTextColor(0xFFF2F2F2.toInt())
        }
        var lastDelta: Double? = null
        if (parked && recent != null) {
            setHeroLabel(tvHero1Label, "마지막 주행 · " + Fmt.agoText(recent.endTs), heroIconsParked[0])
            lastKmTween.set(recent.distanceKm)
            val eff = recent.effKmPerKwh
            lastEffTween.set(eff)
            val u = usualEff
            lastDelta = if (eff != null && u != null && u > 0.1) (eff / u - 1.0) * 100.0 else null
            lastDeltaTween.set(lastDelta, epsilon = 0.5)
            tvTripEff.setTextColor(when { lastDelta == null -> 0xFFF2F2F2.toInt(); lastDelta >= 0 -> GREEN; else -> 0xFFFF8A80.toInt() })
        } else {
            tripKmTween.set(if (s.tripActive) s.tripKm else null)
            tripEffTween.set(if (s.tripActive && s.tripEnergyKwh > 0.1 && s.tripKm > 0.3) s.tripKm / s.tripEnergyKwh else null)
        }

        // 충전 사이클 패널 (v14): 충전 이후 km · 배터리 N% 사용 · 소비 kWh / 회생 회수 비율(게이지에 평소 선) / 이번 주행
        val cycleRatio: Float? = if (s.cycleConsumeKwh > 0.3) (s.cycleRegenKwh / s.cycleConsumeKwh * 100.0).toFloat() else null
        tvCycleTitle.text = if (s.cycleSinceTs != null) "충전 이후 · " + sinceFmt.format(java.util.Date(s.cycleSinceTs)) else "충전 이후 · 전체 기간"
        cycleKmTween.set(if (s.cycleSinceTs != null || s.cycleKm > 0.05) s.cycleKm else null, epsilon = 0.5)
        val socUsed = if (s.cycleSocEnd != null && pct != null && s.cycleSocEnd > pct) s.cycleSocEnd - pct else null
        tvCycleSub.text = when {
            s.cycleKm < 0.05 && s.cycleConsumeKwh < 0.05 -> "충전 후 주행 데이터 없음"
            // 한 줄에 들어가게: 배터리 %가 있으면 소비 kWh는 생략 (회생 줄에 kWh가 있음)
            else -> (if (socUsed != null) String.format("배터리 %.0f%% 사용", socUsed) else String.format("소비 %.1f kWh", s.cycleConsumeKwh)) +
                (if (s.cycleKm > 0.5 && s.cycleConsumeKwh > 0.3) String.format(" · 전비 %.1f km/kWh", s.cycleKm / s.cycleConsumeKwh) else "")
        }
        animateRatio(cycleRatio)
        tvCycleKwh.text = if (cycleRatio != null) String.format("%.2f kWh 회수", s.cycleRegenKwh) else ""
        regenChart.setValues(cycleRatio, s.usualRegenRatioPct)
        val tripRatio = if (s.tripActive && s.tripEnergyKwh > 0.1) s.tripRegenKwh / s.tripEnergyKwh * 100.0 else null
        tvRegenTripLabel.text = if (s.tripActive) "이번 주행" else "주행 대기"
        tvRegenTrip.text = if (s.tripActive) String.format("%.2f kWh", s.tripRegenKwh) else "-"
        tvRegenToday.text = if (tripRatio != null) String.format("%.0f%%", tripRatio) else "-"

        tvToday.text = Fmt.km(s.todayKm)
        tvEff.text = Fmt.eff(s.todayEff)
        // 누적: 계기판 값을 안 넣었고 앱이 쌓은 거리도 없으면 "0 km" 대신 칸을 숨긴다 (2026-09-13)
        val hasTotal = demo || Prefs.hasTotalKm(host.context)
        tvTotal.visibility = if (hasTotal) View.VISIBLE else View.GONE
        tvTotalLabel.visibility = tvTotal.visibility
        tvTotal.text = if (demo) "15,545 km" else Fmt.kmInt(Prefs.totalKm(host.context))

        // 상태 한 줄: 점 색으로 상태 표시 (충전=초록, 주행=주황, 대기=회색)
        // 차량이 주는 경우에만 덧붙는 정보 (목표 %, 완료까지 남은 시간). 외기 온도는 의미 없어 표시 안 함 (사용자 결정 2026-09-05)
        val limitTxt = s.chargeLimitPct?.let { String.format(" · 목표 %.0f%%", it) } ?: ""
        val remainTxt = s.chargeRemainMin?.let {
            if (it >= 60) String.format(" · %d시간 %d분 남음", it / 60, it % 60)
            else String.format(" · %d분 남음", it)
        } ?: ""
        val nowMs = System.currentTimeMillis()
        val ach = achievement?.takeIf { nowMs < achievementUntil }
        val baseLine = when {
            s.chargeActive -> "충전 중 · ${Fmt.kwh(s.chargeKwh)} 들어옴 · " +
                (if (kw != null) Fmt.kw(kw.toDouble()) else "") + limitTxt + remainTxt
            s.tripActive -> "주행 중 · ${Fmt.km(s.tripKm)} · ${s.tripMinutes}분"
            recent != null -> "대기 중 · 마지막 주행 " + Fmt.km(recent.distanceKm) + (
                if (lastDelta == null) "" else if (lastDelta >= 3) " · 평소보다 전비 " + String.format("%.0f", lastDelta) + "% 좋았어요"
                else if (lastDelta <= -3) " · 평소보다 전비 " + String.format("%.0f", -lastDelta) + "% 낮았어요" else " · 평소와 비슷한 전비")
            else -> "대기 중" + (if (s.gpsFix) " · GPS 수신" else " · GPS 대기")
        }
        tvStateLine.text = if (ach != null) ach + "  ·  " + baseLine else if (demo && demoParked) "새 기록 · 최고 전비 6.5 km/kWh  ·  " + baseLine else baseLine
        val dot = when {
            s.chargeActive -> GREEN
            s.tripActive -> ACCENT
            else -> 0xFF5A5A60.toInt()
        }
        stateDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dot)
        // 상태 점 숨쉬기: 주행·충전 중엔 1.6초 주기로 커졌다 작아지며 은은하게 깜빡, 대기 중엔 정지 (사용자 2026-09-08)
        val live = s.chargeActive || s.tripActive
        if (live && dotAnim == null) {
            val a = android.animation.ObjectAnimator.ofPropertyValuesHolder(stateDot,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.6f, 1f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.6f, 1f),
                android.animation.PropertyValuesHolder.ofFloat("alpha", 1f, 0.45f, 1f))
            a.duration = 1600; a.repeatCount = android.animation.ValueAnimator.INFINITE
            a.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            a.start(); dotAnim = a
        } else if (!live && dotAnim != null) {
            dotAnim?.cancel(); dotAnim = null
            stateDot.scaleX = 1f; stateDot.scaleY = 1f; stateDot.alpha = 1f
        }
        tvStateLine.setTextColor(if (ach != null || (demo && demoParked)) ACCENT else if (s.chargeActive || s.tripActive) 0xFFF2F2F2.toInt() else 0xFFC9C9CE.toInt())

        // 차량 데이터 경고 (누르면 진단 화면)
        if (!s.carConnected || s.batteryWh == null) {
            tvWarn.visibility = View.VISIBLE
            tvWarn.text = if (!s.carConnected)
                "차량 연결 안 됨 — 눌러서 진단 보기"
            else
                "배터리 데이터 없음 (권한/미지원) — 눌러서 진단 보기"
        } else {
            tvWarn.visibility = View.GONE
        }
    }
    private var modeApplied = false

    companion object {
        private const val ACCENT = 0xFFFF7500.toInt()
        private const val GREEN = 0xFF3ECF6E.toInt()
        private const val GRAY = 0xFF8E8E93.toInt()

        /** 레이아웃 점검용 데모 표시 (MainActivity가 디버그 빌드 + 인텐트 extra일 때만 켠다, 2026-09-06) */
        @Volatile var demo = false
        /** 데모를 충전 중 상태로 (am start ... --ez demo true --ez demo_charge true) */
        @Volatile var demoCharge = false
        /** 데모를 주차 중(주행 직후) 상태로 (--ez demo_parked true) — 히어로 "마지막 주행" 요약 점검용 (2026-09-13) */
        @Volatile var demoParked = false

        private fun demoSnapshot(): StatusSnapshot = StatusSnapshot(
            ts = System.currentTimeMillis(), carConnected = true, batteryWh = 68000f, capacityWh = 94000f,
            socPct = 72f, chargeRateKw = if (demoCharge) 46.0f else if (demoParked) 0f else -18.4f, rangeKm = 312f,
            speedKmh = if (demoCharge || demoParked) 0f else 64f, gpsFix = true,
            tripActive = !demoCharge && !demoParked, chargeActive = demoCharge,
            chargeKwh = if (demoCharge) 12.4 else 0.0, portConnected = demoCharge,
            tripKm = 14.2, tripMinutes = 23, todayKm = 27.6, todayEff = 6.2,
            tripRegenKwh = 0.8, todayRegenKwh = 1.4,
            tripConsumeAvgKw = 16.5f, tripConsumePeakKw = 45.8f, tripRegenAvgKw = 9.2f, tripRegenPeakKw = 21.4f,
            cycleSinceTs = System.currentTimeMillis() - 26 * 3_600_000L, cycleConsumeKwh = 18.4, cycleRegenKwh = 3.3,
            tripEnergyKwh = 2.3, usualRegenRatioPct = 14f,
            cycleKm = 107.0, cycleSocEnd = 91f
        )

        /** 데모 표의 행을 눌렀을 때 상세 화면이 쓸 가짜 주행 (DB에 없으므로) */
        fun demoTrip(id: Long): Trip? = if (demo) demoTrips().firstOrNull { it.id == id } else null
        /** 충전 탭 데모 기록 4건 (데모일 때만, 아니면 null). DB에 넣지 않으므로 서버로 올라갈 일 없음 (2026-09-08 사용자 요청) */
        fun demoChargeList(): List<ChargeSession>? = if (demo) demoCharges() else null
        fun demoCharge(id: Long): ChargeSession? = if (demo) demoCharges().firstOrNull { it.id == id } else null

        private fun demoCharges(): List<ChargeSession> {
            val now = System.currentTimeMillis()
            fun c(id: Long, hoursAgo: Int, minutes: Int, kwh: Double, cost: Double, s0: Float, s1: Float, maxKw: Double, type: String, profile: String, station: String) =
                ChargeSession(id = id, startTs = now - hoursAgo * 3_600_000L, endTs = now - hoursAgo * 3_600_000L + minutes * 60_000L,
                    kwh = kwh, cost = cost, socStart = s0, socEnd = s1, maxKw = maxKw, type = type, profile = profile, station = station,
                    stLat = 37.48, stLon = 126.95)
            val dc = "[[0,12],[1,88],[2,142],[4,151.2],[7,148.5],[10,131],[14,112],[18,96.4],[22,80],[26,64],[30,52],[34,41]]"
            val dc2 = "[[0,9],[1,34],[2,48],[3,51.5],[5,50.2],[8,49.1],[12,44.0],[17,38.2],[22,33.5],[26,30.1],[30,27.8],[35,26.0]]"
            val ac = "[[0,3.1],[5,7.0],[30,7.1],[60,7.0],[120,7.0],[180,6.9],[240,6.8],[300,6.2],[330,4.1]]"
            return listOf(
                c(-901L, 26, 34, 41.3, 14455.0, 28f, 71f, 151.2, "DC", dc, "관악 이마트 초급속"),
                c(-902L, 3 * 24 + 5, 35, 28.7, 10725.0, 36f, 65f, 51.5, "DC", dc2, "사당역 공영주차장"),
                c(-903L, 6 * 24 + 2, 335, 38.9, 6224.0, 22f, 61f, 7.1, "AC", ac, "아파트 완속 3번"),
                c(-904L, 12 * 24 + 9, 290, 33.5, 5360.0, 31f, 65f, 7.0, "AC", ac, "아파트 완속 3번")
            )
        }

        /** 전체 주행 기록 화면의 데모 목록 (데모일 때만, 아니면 null) */
        fun demoTripList(): List<Trip>? = if (demo) demoTrips() else null

        private fun demoTrips(): List<Trip> {
            val now = System.currentTimeMillis()
            fun t(hoursAgo: Int, km: Double, kwh: Double, regen: Double) = Trip(
                id = -hoursAgo.toLong(), startTs = now - hoursAgo * 3_600_000L, endTs = now - hoursAgo * 3_600_000L + 25 * 60_000L,
                distanceM = km * 1000, energyKwh = kwh, socStart = null, socEnd = null, avgKmh = 38.0, maxKmh = 82.0,
                startLat = 37.478, startLon = 126.951, endLat = 37.498, endLon = 126.978,
                polyline = "[[37.478,126.951,120],[37.483,126.958,140],[37.489,126.966,90],[37.494,126.972,-30],[37.498,126.978,110]]",
                regenKwh = regen,
                // 마지막 한 건은 이름 없이 → 에뮬레이터에서 실제 역지오코딩 경로를 시험
                startPlace = if (hoursAgo == 75) null else "관악구 봉천동", endPlace = if (hoursAgo == 75) null else "동작구 사당동"
            )
            // 오늘 타임라인 점검용으로 3건은 오늘 안에 (3·8·13시간 전)
            return listOf(t(3, 14.2, 2.2, 0.6), t(8, 8.1, 1.6, 0.3), t(13, 22.7, 3.3, 1.1), t(51, 41.5, 7.4, 1.9), t(75, 6.3, 1.1, 0.2))
        }
    }
}
