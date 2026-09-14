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
        return true
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
        // 바로 공식 요금표(무공해차 통합누리집) 창 — 표에서 이번 출력 구간 단가를 자동으로 읽어 채운다. 못 읽으면 로밍 제안값
        showOfficialRate(host, root, s, c, suggested, ratedKw)
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
        val readJs = """
            (function(){
              var el=document.getElementById('stationFeeM'); if(!el) return 'NOROOT';
              for(var i=0;i<8&&el;i++){ el=el.parentElement; if(el&&el.innerText.indexOf('로밍요금 보기')>=0) break; }
              if(!el) return 'WAIT';
              var t=el.innerText;
              var tot=(t.match(/총 ([0-9,]+) 건/)||[])[1];
              if(tot==='0') return 'EMPTY';
              var k=t.indexOf('로밍\n'); if(k<0) return 'WAIT';
              var rows=t.slice(k+3).split('로밍요금 보기'); var r=rows[0].trim().split('\n').map(function(x){return x.trim()}).filter(Boolean);
              /* 결과 행 = "번호, 이름, 주소, 사업자, 출력, 5구간". 화면 폭에 따라 이름·주소 줄이 빠지기도 하므로(에뮬레이터 확인)
                 위치가 아니라 "숫자(또는 -)가 6개 이상 연속" 패턴으로 [출력, 5구간]을 찾는다. 검색 직후엔 표가 비어 있으니 그땐 더 기다린다 */
              if(!/^[0-9]+$/.test(r[0])) return 'WAIT';
              var isNum=function(x){return /^([0-9]+(\.[0-9]+)?|-)$/.test(x)};
              var run=[]; for(var i=1;i<r.length;i++){ if(isNum(r[i])) run.push(i); else { if(run.length>=6) break; run=[]; } }
              if(run.length<6) return 'WAIT';
              var last=run.slice(run.length-5).map(function(i){return r[i]}); var kwI=run[run.length-6];
              el.scrollIntoView({block:'start'});
              return JSON.stringify({name:r[1],cpo:r[kwI-1]||'',kw:r[kwI],p:last});
            })()
        """.trimIndent()
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
                        if (v != null && v > 0) {
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
                web.evaluateJavascript(
                    "(function(){var i=document.getElementById('snm');var b=document.getElementById('btnStationSearch');" +
                        "if(!i||!b)return 'NOFORM';i.value='" + nameJs + "';b.click();return 'OK';})()", null)
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
        twRate.set(if (s.kwh > 0.01) s.cost / s.kwh else null)
        root.findViewById<TextView>(R.id.cd_station).text = s.station ?: "미확인"

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

    /** 단가 수정: 요금 재계산 + 충전소 프로필에 기억 (다음부터 자동 적용) */
    private fun showRateDialog(host: AppHost, root: View, s: ChargeSession) {
        if (s.kwh <= 0.01) return
        val ctx = host.context
        val input = EditText(ctx)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.setText(String.format("%.1f", s.cost / s.kwh))
        host.showDialog(
            AlertDialog.Builder(ctx)
                .setTitle(s.station ?: "충전 단가 수정")
                .setMessage(
                    "실제 낸 단가(원/kWh)를 입력하면 요금이 다시 계산됩니다." +
                        if (s.stLat != null) "\n이 충전소에서는 다음부터 이 단가가 자동 적용됩니다." else ""
                )
                .setView(input)
                .setPositiveButton("저장") { _, _ ->
                    val rate = input.text.toString().toDoubleOrNull()
                    if (rate == null || rate <= 0) {
                        Toast.makeText(ctx, "단가를 올바르게 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (s.id == DEMO_ID) return@setPositiveButton
                    val db = Db.get(ctx)
                    db.updateChargeRate(s.id, s.kwh, rate)
                    if (s.stLat != null && s.stLon != null) {
                        db.upsertStationProfile(s.stLat, s.stLon, s.station ?: "직접 지정 충전소", null, rate)
                    }
                    db.chargeById(s.id)?.let { fill(root, it) }
                    Toast.makeText(ctx, "저장했습니다", Toast.LENGTH_SHORT).show()
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
