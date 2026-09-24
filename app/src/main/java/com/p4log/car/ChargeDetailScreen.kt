package com.p4log.car

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 충전 상세: kWh·요금 히어로 + 통계 + 충전 속도 막대 + 충전소 위치 지도 + 단가 수정. Activity/오버레이 공용.
 * v2 (2026-09-08, 사용자): 라벨 아이콘, 숫자 카운트업(열릴 때 0→값), ChargeStripView(전력 곡선 + 열 띠 타임라인, v3), 지도(map.html showPoint).
 */
object ChargeDetailScreen {
    /** UI 확인용 데모 (외부에서 도달 불가, charge_id로 -999를 넘겼을 때만) */
    const val DEMO_ID = -999L
    private val dayFmt = SimpleDateFormat("M.d (E) HH:mm", Locale.KOREA)

    @SuppressLint("SetJavaScriptEnabled")
    fun bind(host: AppHost, root: View, chargeId: Long): Boolean {
        root.findViewById<View>(R.id.cd_back).setOnClickListener { host.back() }
        val s = (if (chargeId == DEMO_ID) demoSession() else (Db.get(host.context).chargeById(chargeId) ?: DashboardPage.demoCharge(chargeId)))
            ?: return false
        // 충전소 위치 지도: 좌표가 있을 때만. map.html을 한 번 로드하고 showPoint
        val web = root.findViewById<WebView>(R.id.cd_map)
        val empty = root.findViewById<View>(R.id.cd_map_empty)
        if (s.stLat != null && s.stLon != null) {
            empty.visibility = View.GONE
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            // 충전소 선택용 JS 브리지는 반드시 loadUrl 전에 (나중에 등록하면 다음 로드까지 무시됨 — 에뮬레이터에서 확인)
            web.addJavascriptInterface(PickBridge { i -> pickCallback?.invoke(i) }, "P4")
            web.setWebViewClient(object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    web.evaluateJavascript("showPoint(" + s.stLat + "," + s.stLon + ");", null)
                }
            })
            web.loadUrl(Prefs.mapUrl(web.context))
        } else {
            web.visibility = View.GONE
            empty.visibility = View.VISIBLE
        }
        // 열릴 때 왼쪽·오른쪽 칸이 떠오르는 전환
        val dp = host.context.resources.displayMetrics.density
        for ((v, dx) in listOf(root.findViewById<View>(R.id.cd_left) to -24f, root.findViewById<View>(R.id.cd_right) to 24f)) {
            v.alpha = 0f; v.translationX = dx * dp
            v.animate().alpha(1f).translationX(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        fill(root, s, animate = true)
        root.findViewById<Button>(R.id.cd_edit_rate).setOnClickListener { showRateDialog(host, root, s) }
        root.findViewById<Button>(R.id.cd_pick_station).setOnClickListener { pickStation(host, root, s, web) }
        root.findViewById<Button>(R.id.cd_set_home).setOnClickListener { showKindDialog(host, root, s, "home") }
        root.findViewById<Button>(R.id.cd_set_work).setOnClickListener { showKindDialog(host, root, s, "work") }
        return true
    }

    /**
     * 내 충전기 등록 (2026-09-24, 사용자 승인): 이 충전의 자리(150 m)를 집/회사로 기억한다.
     * 요금은 정액(원/kWh) 또는 한전 전기차 충전 시간대 요금(HomeTariff). 같은 자리의 기존 충전도 전부 다시 계산해 적용.
     * 다음부터는 위치만으로 인식 — API·요금표 조회 없음.
     */
    private fun showKindDialog(host: AppHost, root: View, s: ChargeSession, kind: String) {
        val ctx = host.context
        val label = HomeTariff.kindLabel(kind) ?: kind
        if (s.stLat == null || s.stLon == null) {
            Toast.makeText(ctx, "이 충전 기록엔 위치 정보가 없어 " + HomeTariff.kindLabelRo(kind) + " 지정할 수 없습니다", Toast.LENGTH_LONG).show(); return
        }
        val dp = ctx.resources.displayMetrics.density
        val db = Db.get(ctx)
        val prof = db.nearestStationProfile(s.stLat, s.stLon, 150.0)
        val box = android.widget.LinearLayout(ctx)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        val group = android.widget.RadioGroup(ctx)
        val flat = android.widget.RadioButton(ctx); flat.text = "정액 단가 (원/kWh) — 아파트 운영사 단가, 회사 정산 단가"; flat.textSize = 21f; flat.id = 1
        val tou = android.widget.RadioButton(ctx); tou.text = "한전 전기차 충전 시간대 요금 — 경부하·중간·최대 자동 (별도 계량기)"; tou.textSize = 21f; tou.id = 2
        group.addView(flat); group.addView(tou)
        group.check(if (prof?.tariff == "tou") 2 else 1)
        val rateLbl = TextView(ctx); rateLbl.text = "정액 단가 (원/kWh)"; rateLbl.textSize = 19f; rateLbl.setTextColor(Color.parseColor("#C9C9CE"))
        rateLbl.setPadding(0, (14 * dp).toInt(), 0, 0)
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(prof?.rate?.let { String.format("%.1f", it) } ?: ""); input.textSize = 24f; input.setTextColor(Color.parseColor("#F2F2F2"))
        input.hint = "예: 120"
        group.setOnCheckedChangeListener { _, id -> input.isEnabled = id == 1; rateLbl.alpha = if (id == 1) 1f else 0.4f }
        box.addView(group); box.addView(rateLbl); box.addView(input)
        host.showDialog(
            AlertDialog.Builder(ctx)
                .setTitle("이 자리를 " + HomeTariff.kindLabelRo(kind))
                .setMessage("이 위치(150 m 안)에서 한 충전은 앞으로 \"" + label + "\"으로 자동 인식됩니다. 완속은 계량기 기준(배터리 kWh ÷ 0.88)으로 요금을 계산합니다. 같은 자리의 기존 충전도 다시 계산합니다.")
                .setView(box)
                .setPositiveButton("저장") { _, _ ->
                    val tariff = if (group.checkedRadioButtonId == 2) "tou" else "flat"
                    val rate = input.text.toString().toDoubleOrNull()
                    if (tariff == "flat" && (rate == null || rate < 0)) { Toast.makeText(ctx, "정액 단가를 입력하세요", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                    fun costFor(c: ChargeSession): Double {
                        val mk = HomeTariff.meterKwh(c.kwh, kind, c.type)
                        return if (tariff == "tou") HomeTariff.touCost(c.startTs, c.endTs, c.profile, mk).cost else mk * (rate ?: 0.0)
                    }
                    if (s.id <= 0) {   // 데모: 화면만
                        fill(root, s.copy(kind = kind, station = label, cost = Math.round(costFor(s)).toDouble()))
                        Toast.makeText(ctx, HomeTariff.kindLabelRo(kind) + " 표시 (데모)", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                    }
                    db.upsertStationProfile(s.stLat, s.stLon, label, null, rate, null, kind, tariff)
                    val targets = db.chargesNear(s.stLat, s.stLon, 150.0)
                    for (c in targets) db.setChargeKind(c.id, kind, label, costFor(c))
                    db.chargeById(s.id)?.let { fill(root, it) }
                    Toast.makeText(ctx, HomeTariff.kindLabelRo(kind) + " 저장 · 기존 " + targets.size + "건 다시 계산 · 다음부터 자동 인식", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("취소", null)
        )
    }

    // ---------- 충전소 직접 선택 (2026-09-08, 사용자 요청) ----------
    // GPS 기반 자동 인식은 오차가 있어서, 근처 후보를 목록·지도 번호 마커로 보여주고 사용자가 고르면
    // ① 이 충전 기록의 충전소 이름·좌표를 바꾸고 ② 충전소 프로필에 저장 → 다음에 그 근처(150m)에서 충전하면 자동으로 그 충전소로 인식.
    // ③ 그 충전소 충전기 출력에 맞는 환경부 로밍 단가를 제안하고 바로 수정할 수 있게 한다.
    private const val PICK_RADIUS_M = 800.0

    /** 현재 선택 모드의 콜백 (선택 모드가 아니면 null) */
    @Volatile private var pickCallback: ((Int) -> Unit)? = null

    private class PickBridge(private val onPick: (Int) -> Unit) {
        @android.webkit.JavascriptInterface
        fun pick(index: Int) { android.os.Handler(android.os.Looper.getMainLooper()).post { onPick(index) } }
    }

    private fun pickStation(host: AppHost, root: View, s: ChargeSession, web: WebView) {
        val ctx = host.context
        val lat = s.stLat; val lon = s.stLon
        if (lat == null || lon == null) {
            Toast.makeText(ctx, "이 충전 기록엔 위치 정보가 없어 충전소를 고를 수 없습니다", Toast.LENGTH_LONG).show(); return
        }
        val btn = root.findViewById<Button>(R.id.cd_pick_station)
        val chart = root.findViewById<View>(R.id.cd_chart)
        val chartLabel = root.findViewById<View>(R.id.cd_chart_label)
        val mapLabel = root.findViewById<TextView>(R.id.cd_map_label)
        // 이미 선택 모드면 취소
        if (btn.tag == "picking") {
            pickCallback = null
            btn.tag = null; btn.text = "충전소 직접 선택 (지도에서 누르기)"
            chart.visibility = View.VISIBLE; chartLabel.visibility = View.VISIBLE
            mapLabel.text = "충전소 위치"
            web.evaluateJavascript("showPoint($lat,$lon);", null)
            return
        }
        btn.isEnabled = false; btn.text = "근처 충전소를 찾는 중…"
        EvStations.nearbyAsync(lat, lon, PICK_RADIUS_M, 12) { list ->
            btn.isEnabled = true
            if (list.isEmpty()) {
                btn.text = "충전소 직접 선택 (지도에서 누르기)"
                Toast.makeText(ctx, "반경 800m 안에서 충전소를 찾지 못했습니다 (인터넷·API 확인)", Toast.LENGTH_LONG).show()
                return@nearbyAsync
            }
            // 선택 모드 (사용자 2026-09-08: "목록 말고 지도에서 바로"): 타임라인을 숨겨 지도를 오른쪽 칸 전체로 키우고,
            // 이름 툴팁이 붙은 번호 마커를 띄운다. 마커를 누르면 바로 네이버 요금표 창으로.
            btn.tag = "picking"; btn.text = "선택 취소"
            chart.visibility = View.GONE; chartLabel.visibility = View.GONE
            mapLabel.text = "지도에서 충전소를 누르세요 (" + list.size + "곳)"
            pickCallback = { i ->
                if (i in list.indices) {
                    pickCallback = null
                    btn.tag = null; btn.text = "충전소 직접 선택 (지도에서 누르기)"
                    chart.visibility = View.VISIBLE; chartLabel.visibility = View.VISIBLE
                    mapLabel.text = "충전소 위치"
                    applyStation(host, root, s, list[i], web)
                }
            }
            val js = StringBuilder("showCandidates([" + lat + "," + lon + "],[")
            list.forEachIndexed { i, c -> if (i > 0) js.append(","); js.append("[" + c.lat + "," + c.lon + ",\"" + c.name.replace("\"", "") + "\"]") }
            js.append("]);")
            // 타임라인을 숨긴 뒤 레이아웃이 끝나고 나서 그려야 지도 범위가 맞는다
            web.postDelayed({ web.evaluateJavascript(js.toString(), null) }, 150)
        }
    }

    private fun applyStation(host: AppHost, root: View, s: ChargeSession, c: EvStations.Candidate, web: WebView) {
        val ctx = host.context
        val db = Db.get(ctx)
        val ratedKw = EvStations.ratedOutputFor(c.outputs, s.maxKw)
        val suggested = EvStations.roamingRate(ratedKw)
        if (s.id > 0) {
            db.setChargeStation(s.id, c.name, c.lat, c.lon)
            // 프로필: 충전소 좌표에 저장 → 다음 충전 때 150m 안이면 이 충전소로 자동 인식 (단가는 아래에서 정하면 같이 저장)
            db.upsertStationProfile(c.lat, c.lon, c.name, c.operator, null, c.outputs)
        }
        web.evaluateJavascript("showPoint(" + c.lat + "," + c.lon + ");", null)
        root.findViewById<TextView>(R.id.cd_station).text = c.name
        // 앱 문법 패널에서 공식 요금표를 화면 없이 조회해 큰 숫자로 보여준다 (2026-09-22). 웹 화면은 보조 버튼으로만
        showPickPanel(host, root, s, c, suggested, ratedKw)
    }

    /** 선택 패널의 세대 번호 — 닫은 뒤 늦게 도착한 요금표 결과를 무시하기 위해 */
    @Volatile private var pickGen = 0

    /**
     * 선택한 충전소 패널 (2026-09-22, 사용자: "충전소 선택 UI가 앱과 너무 달라 괴리감이 심하다").
     * 이전엔 마커를 누르면 안드로이드 기본 대화상자에 흰 정부 사이트가 통째로 떴다. 이제 왼쪽 칸에 이름·사업자·정격·거리를 앱 타이포로 보여주고,
     * FeeLookup(화면 없는 WebView)이 공식 요금표를 읽어 이번 출력 구간 단가를 큰 숫자(바로 고칠 수 있는 입력)로 채운다. 5구간은 작은 한 줄.
     * 못 찾으면 환경부 로밍 단가를 제안하고 문구로 알린다. [요금표 웹]·[네이버 지도]는 보조(기존 창).
     */
    private fun showPickPanel(host: AppHost, root: View, s: ChargeSession, c: EvStations.Candidate, suggested: Double, ratedKw: Double) {
        val ctx = host.context
        val panel = root.findViewById<View>(R.id.cd_pick_panel)
        val btnPick = root.findViewById<View>(R.id.cd_pick_station)
        val btnRate = root.findViewById<View>(R.id.cd_edit_rate)
        val name = root.findViewById<TextView>(R.id.cd_pk_name)
        val sub = root.findViewById<TextView>(R.id.cd_pk_sub)
        val feeLabel = root.findViewById<TextView>(R.id.cd_pk_fee_label)
        val input = root.findViewById<EditText>(R.id.cd_pk_rate)
        val bands = root.findViewById<TextView>(R.id.cd_pk_bands)
        val note = root.findViewById<TextView>(R.id.cd_pk_note)
        val bandIdx = FeeLookup.bandIndex(ratedKw)
        val gray = Color.parseColor("#8E8E93"); val subColor = Color.parseColor("#C9C9CE")
        val green = Color.parseColor("#3ECF6E"); val warn = Color.parseColor("#FFB020")

        name.text = c.name
        sub.text = listOfNotNull(
            c.operator?.takeIf { it.isNotBlank() },
            if (ratedKw > 0) String.format("정격 %.0f kW", ratedKw) else null,
            String.format("충전 위치에서 %.0f m", c.distM)
        ).joinToString("  ·  ")
        feeLabel.text = "공식 요금표  ·  " + FeeLookup.BAND_NAMES[bandIdx]
        input.setText(String.format("%.1f", suggested)); input.setTextColor(gray)
        bands.text = ""
        note.text = "무공해차 통합누리집 요금표에서 \"" + c.name + "\" 검색 중…  (로밍 단가 " + String.format("%.0f", suggested) + "원 제안 상태)"
        note.setTextColor(subColor)
        panel.visibility = View.VISIBLE; btnPick.visibility = View.GONE; btnRate.visibility = View.GONE
        panel.alpha = 0f; panel.animate().alpha(1f).setDuration(220).start()

        val gen = ++pickGen
        FeeLookup.lookupAsync(ctx, c.name, bandIdx) { r ->
            if (gen != pickGen || panel.visibility != View.VISIBLE) return@lookupAsync
            r.prices?.let { p ->
                bands.text = p.mapIndexedNotNull { i, v -> if (v != null && v > 0) FeeLookup.BAND_SHORT[i] + " " + String.format("%.0f", v) else null }
                    .joinToString("  ·  ")
            }
            if (r.rate != null) {
                input.setText(String.format("%.1f", r.rate)); input.setTextColor(Color.parseColor("#FF7500"))
                note.text = "공식 요금표에서 찾음  ·  " + (r.cpo?.takeIf { it.isNotBlank() } ?: "") + "  ·  회원가 기준, 확인 후 저장"
                note.setTextColor(green)
            } else {
                input.setTextColor(Color.parseColor("#FF7500"))
                note.text = "공식 요금표에 없음 — 로밍 단가 " + String.format("%.0f", suggested) + "원을 제안합니다. 숫자를 직접 고치거나 [요금표 웹]·[네이버 지도]로 확인하세요"
                note.setTextColor(warn)
            }
        }
        fun close() {
            pickGen++
            panel.visibility = View.GONE; btnPick.visibility = View.VISIBLE; btnRate.visibility = View.VISIBLE
        }
        fun typed(): Double = input.text.toString().toDoubleOrNull() ?: suggested
        root.findViewById<Button>(R.id.cd_pk_save).setOnClickListener {
            val rate = input.text.toString().toDoubleOrNull()
            if (rate == null || rate <= 0) { Toast.makeText(ctx, "단가를 올바르게 입력하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            saveRate(host, root, s, c, rate)
            close()
        }
        root.findViewById<Button>(R.id.cd_pk_web).setOnClickListener { showOfficialRate(host, root, s, c, typed(), ratedKw) }
        root.findViewById<Button>(R.id.cd_pk_naver).setOnClickListener { showNaverRate(host, root, s, c, typed()) }
        root.findViewById<Button>(R.id.cd_pk_cancel).setOnClickListener { close() }
    }

    /**
     * 무공해차 통합누리집 "충전소 요금 검색" (2026-09-08, 사용자: "네이버 말고 더 맞는 곳 찾아봐" → 공식 등록 요금).
     * https://ev.or.kr/nportal/evcarInfo/initEvcarChargePriceV2.do 를 앱 안 WebView(데스크톱 UA)로 열고, 페이지가 뜨면
     * 충전소명(#snm)에 우리 충전소 이름을 넣어 검색(#btnStationSearch)한다. 이름이 환경공단 API statNm과 같은 출처라 대개 1건이 맞는다.
     * 결과 표는 사업자(CPO)·출력구성·완속/중속/급속/급속/초급속 회원가(라디오로 비회원가 전환). AJAX 응답은 암호화돼 있어
     * 직접 부르지 않고, 렌더된 DOM 텍스트를 1초마다 읽어(최대 15초) 이번 충전 출력 구간의 단가를 아래 입력칸에 자동으로 채운다.
     * 사용자는 표를 보고 확인/수정 후 [이 단가로 저장]. [네이버 지도]는 보조.
     */
    private fun showOfficialRate(host: AppHost, root: View, s: ChargeSession, c: EvStations.Candidate, suggested: Double, ratedKw: Double) {
        val ctx = host.context
        val dp = ctx.resources.displayMetrics.density
        val web = WebView(ctx)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        val nameJs = c.name.replace("\\", "").replace("'", "")
        val bandIdx = when { ratedKw < 30 -> 0; ratedKw < 50 -> 1; ratedKw < 100 -> 2; ratedKw < 200 -> 3; else -> 4 }
        val bandName = arrayOf("완속(30kW 미만)", "중속(30~49kW)", "급속(50~99kW)", "급속(100~199kW)", "초급속(200kW 이상)")[bandIdx]

        val box = android.widget.LinearLayout(ctx)
        box.orientation = android.widget.LinearLayout.VERTICAL
        val status = TextView(ctx)
        status.textSize = 19f; status.setTextColor(Color.parseColor("#C9C9CE"))
        status.setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        status.text = "공식 요금표에서 \"" + c.name + "\" 검색 중… (이번 충전: " + bandName + ")"
        box.addView(status, android.widget.LinearLayout.LayoutParams(-1, -2))
        box.addView(web, android.widget.LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * 0.56).toInt()))
        val row = android.widget.LinearLayout(ctx)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        val lbl = TextView(ctx); lbl.text = "단가 (원/kWh)"; lbl.textSize = 21f; lbl.setTextColor(Color.parseColor("#C9C9CE"))
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(String.format("%.1f", suggested)); input.textSize = 24f
        input.setTextColor(Color.parseColor("#F2F2F2"))
        val save = Button(ctx); save.text = "이 단가로 저장"; save.textSize = 21f
        save.setBackgroundColor(Color.parseColor("#FF7500")); save.setTextColor(Color.BLACK)
        val naver = Button(ctx); naver.text = "네이버 지도"; naver.textSize = 19f
        naver.setBackgroundColor(Color.parseColor("#2A2A2E")); naver.setTextColor(Color.parseColor("#F2F2F2"))
        row.addView(lbl, android.widget.LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = (14 * dp).toInt() })
        row.addView(input, android.widget.LinearLayout.LayoutParams((170 * dp).toInt(), -2).also { it.rightMargin = (14 * dp).toInt() })
        row.addView(save, android.widget.LinearLayout.LayoutParams(-2, (52 * dp).toInt()).also { it.rightMargin = (14 * dp).toInt() })
        row.addView(naver, android.widget.LinearLayout.LayoutParams(-2, (52 * dp).toInt()))
        box.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))

        val d = AlertDialog.Builder(ctx).setTitle("공식 충전 요금표 — " + c.name).setView(box)
            .setNegativeButton("닫기", null).create()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        var emptyStreak = 0
        var found = false
        // 결과 표 읽기: "번호 / 충전소명 / 주소 / 사업자 / 출력 / 5구간" 텍스트를 줄로 쪼개 첫 행의 5구간 값을 꺼낸다
        val readJs = FeeLookup.READ_JS   // 자동 조회(FeeLookup)와 같은 파서 (2026-09-21)
        lateinit var poll: Runnable
        poll = Runnable {
            if (found || !d.isShowing) return@Runnable
            if (tries++ > 20) {
                status.text = "공식 요금표에서 이 충전소를 찾지 못했습니다 — 표를 보고 직접 입력하거나 [네이버 지도]로 확인하세요"
                status.setTextColor(Color.parseColor("#FFB020")); return@Runnable
            }
            web.evaluateJavascript(readJs) { res ->
                val txt = res?.trim('"')?.replace("\\\"", "\"") ?: ""
                if (txt != "WAIT") ServiceLog.add(ctx, "공식 요금표 읽기: " + txt.take(400))   // 실차 svclog로 파싱 판정용
                if (txt.startsWith("{")) {
                    try {
                        val o = org.json.JSONObject(txt)
                        val p = o.getJSONArray("p")
                        val v = p.optString(bandIdx).replace(",", "").toDoubleOrNull()
                        found = true
                        if (!FeeLookup.matches(c.name, o.optString("row"))) {
                            // 검색 결과가 없을 때 페이지가 보여주는 환경부 기본 행을 이 충전소 요금으로 오인하지 않게 (2026-09-21)
                            status.text = "요금표에 \"" + c.name + "\"이(가) 없습니다 (표의 행은 다른 충전소: " + o.optString("name") + ") — 직접 입력하거나 [네이버 지도]로 확인하세요"
                            status.setTextColor(Color.parseColor("#FFB020"))
                        } else if (v != null && v > 0) {
                            input.setText(String.format("%.1f", v))
                            status.text = "자동으로 찾음: " + o.optString("cpo") + " · " + o.optString("kw") + " kW · " + bandName + " 회원가 " + String.format("%.0f", v) + "원/kWh — 확인 후 저장"
                            status.setTextColor(Color.parseColor("#3ECF6E"))
                        } else {
                            status.text = "표는 찾았지만 " + bandName + " 단가가 없습니다 (사업자 미등재) — 표를 보고 직접 입력하세요"
                            status.setTextColor(Color.parseColor("#FFB020"))
                        }
                    } catch (e: Throwable) { handler.postDelayed(poll, 1000) }
                } else if (txt == "EMPTY" && ++emptyStreak >= 4) {
                    // "총 0 건"은 검색 직후 표가 비는 순간에도 잠깐 나온다 → 4초 연속일 때만 진짜 없음으로 본다
                    found = true
                    status.text = "공식 요금표에 이 이름의 충전소가 없습니다 — 아래에서 검색어를 바꿔 보거나 [네이버 지도]로 확인하세요"
                    status.setTextColor(Color.parseColor("#FFB020"))
                } else handler.postDelayed(poll, 1000)
            }
        }
        web.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                web.evaluateJavascript(FeeLookup.searchJs(nameJs), null)
                handler.postDelayed(poll, 1500)
            }
        })
        web.loadUrl("https://ev.or.kr/nportal/evcarInfo/initEvcarChargePriceV2.do")

        save.setOnClickListener {
            val rate = input.text.toString().toDoubleOrNull()
            if (rate == null || rate <= 0) { Toast.makeText(ctx, "단가를 올바르게 입력하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            saveRate(host, root, s, c, rate)
            d.dismiss()
        }
        naver.setOnClickListener { d.dismiss(); showNaverRate(host, root, s, c, input.text.toString().toDoubleOrNull() ?: suggested) }
        host.showDialogDirect(d)
        d.window?.setLayout((ctx.resources.displayMetrics.widthPixels * 0.94).toInt(), (ctx.resources.displayMetrics.heightPixels * 0.92).toInt())
    }

    /**
     * 네이버 지도 충전소 페이지 (2026-09-08, 사용자: "네이버 지도에서 고르면 요금이 정확히 보인다").
     * 앱 안 WebView(데스크톱 UA → PC 지도 레이아웃, 차 화면이 넓어 요금표가 그대로 보임)에 충전소 이름으로 검색을 띄우고,
     * 같은 창 아래 줄에서 읽은 단가를 바로 입력해 저장한다(요금 재계산 + 충전소 프로필에 기억).
     */
    private fun showNaverRate(host: AppHost, root: View, s: ChargeSession, c: EvStations.Candidate, suggested: Double) {
        val ctx = host.context
        val dp = ctx.resources.displayMetrics.density
        val web = WebView(ctx)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        web.setWebViewClient(WebViewClient())
        val q = java.net.URLEncoder.encode(c.name + " 전기차충전소", "UTF-8")
        web.loadUrl("https://map.naver.com/p/search/" + q)

        val box = android.widget.LinearLayout(ctx)
        box.orientation = android.widget.LinearLayout.VERTICAL
        // 다이얼로그 안에서는 weight가 0이 되므로 화면 높이 기준 고정 높이
        box.addView(web, android.widget.LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * 0.62).toInt()))
        val row = android.widget.LinearLayout(ctx)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        val lbl = TextView(ctx); lbl.text = "읽은 단가 (원/kWh)"; lbl.textSize = 21f; lbl.setTextColor(Color.parseColor("#C9C9CE"))
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(String.format("%.1f", suggested)); input.textSize = 24f
        input.setTextColor(Color.parseColor("#F2F2F2"))
        val save = Button(ctx); save.text = "이 단가로 저장"; save.textSize = 21f
        save.setBackgroundColor(Color.parseColor("#FF7500")); save.setTextColor(Color.BLACK)
        row.addView(lbl, android.widget.LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = (14 * dp).toInt() })
        row.addView(input, android.widget.LinearLayout.LayoutParams((180 * dp).toInt(), -2).also { it.rightMargin = (14 * dp).toInt() })
        row.addView(save, android.widget.LinearLayout.LayoutParams(-2, (52 * dp).toInt()))
        box.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))

        val d = AlertDialog.Builder(ctx).setTitle("네이버 지도 요금표 — " + c.name).setView(box)
            .setNegativeButton("닫기", null).create()
        save.setOnClickListener {
            val rate = input.text.toString().toDoubleOrNull()
            if (rate == null || rate <= 0) { Toast.makeText(ctx, "단가를 올바르게 입력하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            saveRate(host, root, s, c, rate)
            d.dismiss()
        }
        host.showDialogDirect(d)
        d.window?.setLayout((ctx.resources.displayMetrics.widthPixels * 0.92).toInt(), (ctx.resources.displayMetrics.heightPixels * 0.9).toInt())
    }

    /** 고른 충전소 + 단가를 저장: 요금 재계산, 프로필에 단가 기억(다음부터 자동 적용). 데모(id<0)는 화면만 */
    private fun saveRate(host: AppHost, root: View, s: ChargeSession, c: EvStations.Candidate, rate: Double) {
        val ctx = host.context
        val db = Db.get(ctx)
        if (s.id > 0) {
            db.updateChargeRate(s.id, s.kwh, rate)
            db.upsertStationProfile(c.lat, c.lon, c.name, c.operator, rate, c.outputs)
            db.chargeById(s.id)?.let { fill(root, it) }
        } else {
            root.findViewById<TextView>(R.id.cd_rate).text = String.format("%.1f원/kWh", rate)
            root.findViewById<TextView>(R.id.cd_cost).text = Fmt.won(s.kwh * rate)
        }
        Toast.makeText(ctx, "저장했습니다 — 이 충전소는 다음부터 자동 인식·이 단가 적용", Toast.LENGTH_SHORT).show()
    }

    private fun fill(root: View, s: ChargeSession, animate: Boolean = false) {
        root.findViewById<TextView>(R.id.cd_title).text = dayFmt.format(Date(s.startTs)) + " 충전"

        val typeView = root.findViewById<TextView>(R.id.cd_type)
        // 칩 대신 색 점 + 글자 (2026-09-13, 충전 탭 목록과 같은 표시)
        typeView.text = "●  " + (if (s.type == "DC") "급속" else "완속")
        typeView.setTextColor(if (s.type == "DC") Color.parseColor("#FF7500") else Color.parseColor("#4DA3FF"))

        // 숫자 카운트업: 처음 열릴 때 0에서 값까지, 단가 수정 후엔 이전 값에서 새 값으로
        val twKwh = NumberTween(root.findViewById(R.id.cd_kwh)) { Fmt.kwh(it) }
        val twCost = NumberTween(root.findViewById(R.id.cd_cost)) { Fmt.won(it) }
        val twTime = NumberTween(root.findViewById(R.id.cd_time)) { Fmt.duration(0L, (it * 60_000.0).toLong()) }
        val twRate = NumberTween(root.findViewById(R.id.cd_rate)) { String.format("%.1f원/kWh", it) }
        if (animate) { twKwh.set(0.0); twCost.set(0.0); twTime.set(0.0); twRate.set(0.0) }
        twKwh.set(s.kwh)
        twCost.set(s.cost, epsilon = 0.5)
        twTime.set(((s.endTs - s.startTs) / 60_000L).toDouble(), epsilon = 0.5)
        root.findViewById<TextView>(R.id.cd_soc).text =
            if (s.socStart != null && s.socEnd != null)
                String.format("%.0f → %.0f%%", s.socStart, s.socEnd)
            else "-"
        // 내 충전기(집·회사)는 계량 kWh 기준 단가·구성 표시 (2026-09-24)
        val meterKwh = HomeTariff.meterKwh(s.kwh, s.kind, s.type)
        twRate.set(if (meterKwh > 0.01) s.cost / meterKwh else null)
        val kindLabel = HomeTariff.kindLabel(s.kind)
        root.findViewById<TextView>(R.id.cd_station).text = kindLabel ?: s.station ?: "미확인"
        val stLabel = root.findViewById<TextView>(R.id.cd_station_label)
        stLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(
            when (s.kind) { "home" -> R.drawable.ic_st_home; "work" -> R.drawable.ic_st_work; else -> R.drawable.ic_st_station }, 0, 0, 0)
        stLabel.text = if (kindLabel != null) "내 충전기" else "충전소"
        val note = root.findViewById<TextView>(R.id.cd_kind_note)
        if (kindLabel != null) {
            val prof = if (s.stLat != null && s.stLon != null && s.id > 0) Db.get(root.context).nearestStationProfile(s.stLat, s.stLon, 150.0) else null
            val tou = if (prof?.tariff == "tou") HomeTariff.touCost(s.startTs, s.endTs, s.profile, meterKwh) else null
            note.text = (if (s.type == "AC") String.format("계량 약 %.1f kWh (배터리 %.1f kWh · 완속 충전기 효율 88%% 기준)", meterKwh, s.kwh) else String.format("급속 · 배터리 %.1f kWh 그대로", s.kwh)) +
                (if (tou != null) "  ·  한전 시간대 " + tou.summary() else if (prof?.rate != null) String.format("  ·  정액 %.1f원/kWh", prof.rate) else "")
            note.visibility = View.VISIBLE
        } else note.visibility = View.GONE

        // 충전 속도 프로파일: profile JSON [[분,kW],...]
        val pts = ArrayList<Pair<Double, Double>>()
        try {
            val arr = JSONArray(s.profile)
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                pts.add(p.optDouble(0, 0.0) to p.optDouble(1, 0.0))
            }
        } catch (e: Throwable) { /* 손상된 프로파일은 무시 */ }
        root.findViewById<ChargeStripView>(R.id.cd_chart).setData(pts, s.type == "DC", s.socStart, s.socEnd)
    }

    /**
     * 단가 수정: 요금 재계산 + 충전소 프로필에 기억 (다음부터 자동 적용).
     * 2026-09-21: 이름 칸 추가 — 회사 사내 충전기처럼 공공 API에 없는 곳은 "회사 완속"같이 이름을 붙여 기억시킨다
     * (그 위치 150m 안에서 충전하면 다음부터 이 이름·단가로 자동 인식).
     */
    private fun showRateDialog(host: AppHost, root: View, s: ChargeSession) {
        if (s.kwh <= 0.01) return
        val ctx = host.context
        val dp = ctx.resources.displayMetrics.density
        val box = android.widget.LinearLayout(ctx)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        val nameLbl = TextView(ctx); nameLbl.text = "충전소 이름 (예: 집 완속, 회사 완속)"; nameLbl.textSize = 19f; nameLbl.setTextColor(Color.parseColor("#C9C9CE"))
        val name = EditText(ctx)
        name.inputType = InputType.TYPE_CLASS_TEXT
        name.setText(s.station ?: ""); name.textSize = 24f; name.setTextColor(Color.parseColor("#F2F2F2"))
        val rateLbl = TextView(ctx); rateLbl.text = "단가 (원/kWh)"; rateLbl.textSize = 19f; rateLbl.setTextColor(Color.parseColor("#C9C9CE"))
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(String.format("%.1f", s.cost / s.kwh)); input.textSize = 24f; input.setTextColor(Color.parseColor("#F2F2F2"))
        box.addView(nameLbl); box.addView(name)
        rateLbl.setPadding(0, (14 * dp).toInt(), 0, 0)
        box.addView(rateLbl); box.addView(input)
        host.showDialog(
            AlertDialog.Builder(ctx)
                .setTitle("단가 수정")
                .setMessage(
                    "실제 낸 단가(원/kWh)를 입력하면 요금이 다시 계산됩니다." +
                        if (s.stLat != null) "\n이 위치(150m 안)에서는 다음부터 이 이름·단가가 자동 적용됩니다." else ""
                )
                .setView(box)
                .setPositiveButton("저장") { _, _ ->
                    val rate = input.text.toString().toDoubleOrNull()
                    if (rate == null || rate <= 0) {
                        Toast.makeText(ctx, "단가를 올바르게 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (s.id == DEMO_ID) return@setPositiveButton
                    val db = Db.get(ctx)
                    val stName = name.text.toString().trim().ifEmpty { s.station ?: "직접 지정 충전소" }
                    db.updateChargeRate(s.id, s.kwh, rate)
                    if (s.stLat != null && s.stLon != null) {
                        if (stName != s.station) db.setChargeStation(s.id, stName, s.stLat, s.stLon)
                        db.upsertStationProfile(s.stLat, s.stLon, stName, null, rate)
                    }
                    db.chargeById(s.id)?.let { fill(root, it) }
                    Toast.makeText(ctx, "저장했습니다 — 이 위치는 다음부터 \"" + stName + "\"로 자동 인식", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
        )
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
