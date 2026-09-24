package com.p4log.car

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 공식 충전 요금표 자동 조회 (2026-09-21, 사용자: "요금도 최대한 많은 충전기에서 자동으로").
 *
 * 환경공단 충전소 API는 요금을 주지 않는다. 요금은 무공해차 통합누리집(ev.or.kr) "충전소 요금 검색" 페이지에 있는데
 * AJAX 응답이 암호화돼 있어 직접 부를 수 없고, 화면을 그린 뒤 표 글자를 읽어야 한다 (2026-09-08 충전 상세 [충전소 직접 선택]에서 확인).
 * 여기서는 그 과정을 **화면 없는 WebView**로 자동화한다:
 *  1) 충전소 이름으로 검색 → 결과 행의 5구간(완속/중속/급속/급속/초급속) 회원가에서 이번 충전 구간을 꺼낸다
 *  2) 0건이면 이름을 줄여 다시 검색 (괄호 제거 → 앞 두 단어). 등록 이름이 API와 조금 다른 충전소를 최대한 잡기 위해
 *  3) 읽은 행의 충전소 이름이 검색어를 담고 있을 때만 인정한다 — 결과가 없을 때 페이지가 보여주는 "기후에너지환경부 200kW" 기본 행이나
 *     남의 충전소를 요금으로 오인하지 않게 (에뮬레이터 2026-09-21: 송지트리뷰 → 환경부 348원, "광주" → IPLEX광주 로 잘못 잡혔던 것)
 *  4) 그래도 없으면 실패 사유를 돌려준다 (아파트·사내 충전기처럼 공공 로밍에 없는 곳)
 * 주행 중엔 절대 부르지 않는다 — 충전이 끝난 뒤(주차 중) 한 번, 최대 약 2분. WebView는 끝나면 destroy.
 * 충전 상세 화면의 수동 조회(ChargeDetailScreen.showOfficialRate)와 같은 JS·검증을 쓴다.
 */
// 2026-09-22: shared/ 로 이동 — 폰 앱(mobile/StationBridge)도 같은 코드로 요금표를 읽는다. 차량 전용 클래스(Prefs·Db·ServiceLog)를 여기서 쓰지 말 것
object FeeLookup {
    const val URL = "https://ev.or.kr/nportal/evcarInfo/initEvcarChargePriceV2.do"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    val BAND_NAMES = arrayOf("완속(30kW 미만)", "중속(30~49kW)", "급속(50~99kW)", "급속(100~199kW)", "초급속(200kW 이상)")

    /** 이번 충전이 요금표의 어느 구간인지: 정격(급속) 또는 실측(완속) kW */
    fun bandIndex(kw: Double): Int = when { kw < 30 -> 0; kw < 50 -> 1; kw < 100 -> 2; kw < 200 -> 3; else -> 4 }

    /** 검색창(#snm)에 이름을 넣고 검색 버튼(#btnStationSearch)을 누르는 JS. name은 따옴표·역슬래시를 뺀 값 */
    fun searchJs(name: String): String =
        "(function(){var i=document.getElementById('snm');var b=document.getElementById('btnStationSearch');" +
            "if(!i||!b)return 'NOFORM';i.value='" + name.replace("\\", "").replace("'", "") + "';b.click();return 'OK';})()"

    /**
     * 결과 표 읽기: 'WAIT'(아직) / 'EMPTY'(총 0 건) / 'NOROOT'(페이지 구조 다름) / JSON {name,cpo,kw,p:[5구간]}.
     * 결과 행 = "번호, 이름, 주소, 사업자, 출력, 5구간"인데 화면 폭에 따라 줄이 빠지기도 하므로 위치가 아니라
     * "숫자(또는 -)가 6개 이상 연속" 패턴으로 [출력, 5구간]을 찾는다 (에뮬레이터 실증 2026-09-08)
     */
    val READ_JS = """
        (function(){
          var el=document.getElementById('stationFeeM'); if(!el) return 'NOROOT';
          for(var i=0;i<8&&el;i++){ el=el.parentElement; if(el&&el.innerText.indexOf('로밍요금 보기')>=0) break; }
          if(!el) return 'WAIT';
          var t=el.innerText;
          var tot=(t.match(/총 ([0-9,]+) 건/)||[])[1];
          if(tot==='0') return 'EMPTY';
          var k=t.indexOf('로밍\n'); if(k<0) return 'WAIT';
          var rows=t.slice(k+3).split('로밍요금 보기'); var r=rows[0].trim().split('\n').map(function(x){return x.trim()}).filter(Boolean);
          if(!/^[0-9]+$/.test(r[0])) return 'WAIT';
          var isNum=function(x){return /^([0-9]+(\.[0-9]+)?|-)$/.test(x)};
          var run=[]; for(var i=1;i<r.length;i++){ if(isNum(r[i])) run.push(i); else { if(run.length>=6) break; run=[]; } }
          if(run.length<6) return 'WAIT';
          var last=run.slice(run.length-5).map(function(i){return r[i]}); var kwI=run[run.length-6];
          el.scrollIntoView({block:'start'});
          return JSON.stringify({name:r[1],cpo:r[kwI-1]||'',kw:r[kwI],p:last,row:r.slice(0,kwI).join(' ').slice(0,300)});
        })()
    """.trimIndent()

    /** prices = 5구간(완속/중속/급속/급속/초급속) 회원가 전부 (표를 찾았을 때만). 충전 상세 선택 패널에서 한 줄로 보여준다 (2026-09-22) */
    class Result(val rate: Double?, val cpo: String?, val kw: String?, val note: String, val prices: List<Double?>? = null)
    val BAND_SHORT = arrayOf("완속", "중속", "급속 50", "급속 100", "초급속")

    /**
     * 이름 변형 순서: 원본 → 괄호 제거 → 앞 두 단어. 3글자 이상만.
     * 한 단어 검색은 뺐다 — "광주"로 검색하면 IPLEX광주 같은 남의 충전소가 잡힌다 (에뮬레이터 2026-09-21)
     */
    fun nameVariants(name: String): List<String> {
        val base = name.trim()
        val noParen = base.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\s+"), " ").trim()
        val words = noParen.split(" ").filter { it.isNotBlank() }
        val two = if (words.size >= 2) words.take(2).joinToString(" ") else ""
        return listOf(base, noParen, two).map { it.trim() }.filter { it.length >= 3 }.distinct()
    }

    /**
     * 읽은 행이 정말 이 충전소인지: 검색어의 단어(2글자 이상)가 전부 결과 행 글자(이름·주소·사업자 칸 전체)에 들어 있어야 한다.
     * 요금표 페이지는 검색 결과가 없어도 기본 행("기후에너지환경부 200kW")을 보여줘 파서가 그걸 읽는다.
     * 이름 칸(r[1])만 보면 화면 폭에 따라 줄이 밀려 사업자명이 읽히기도 하므로(에뮬레이터: 송지트리뷰 → "에버온") 행 전체와 비교한다.
     */
    fun matches(query: String, rowText: String): Boolean {
        val f = rowText.replace(" ", "")
        val toks = query.split(" ").map { it.trim() }.filter { it.length >= 2 }
        return toks.isNotEmpty() && toks.all { f.contains(it) }
    }

    /**
     * 백그라운드(화면 없는 WebView) 조회. cb는 메인 스레드. bandIdx = bandIndex(kW).
     * 순서: 페이지 로드 → 변형 이름마다 검색 → 1초 간격 폴링(최대 20회, "총 0 건"이 4번 연속이거나 다른 충전소 행이면 다음 변형).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun lookupAsync(context: Context, stationName: String, bandIdx: Int, cb: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val app = context.applicationContext
            val web: WebView = try { WebView(app) } catch (e: Throwable) {
                cb(Result(null, null, null, "WebView 생성 실패: " + e.javaClass.simpleName)); return@post
            }
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.userAgentString = UA
            // 화면에 안 붙는 WebView도 크기가 있어야 레이아웃이 돌아 표가 그려진다
            web.layout(0, 0, 1600, 1200)
            val variants = nameVariants(stationName)
            var vi = 0
            var tries = 0
            var emptyStreak = 0
            var done = false
            var mismatch: String? = null   // 검색어와 다른 행이 읽힌 경우 그 이름 (실패 사유용)
            fun finish(r: Result) {
                if (done) return
                done = true
                try { web.stopLoading(); web.destroy() } catch (e: Throwable) { }
                cb(r)
            }
            lateinit var poll: Runnable
            fun startVariant() {
                if (vi >= variants.size) {
                    finish(Result(null, null, null, "요금표에 없음 (검색: " + variants.joinToString(" / ") + ")" +
                        (if (mismatch != null) " — 표에 나온 건 다른 충전소 \"" + mismatch + "\"" else "")))
                    return
                }
                tries = 0; emptyStreak = 0
                web.evaluateJavascript(searchJs(variants[vi])) { r ->
                    if (r?.contains("NOFORM") == true) finish(Result(null, null, null, "요금표 페이지 구조가 바뀜 (검색창 없음)"))
                    else main.postDelayed(poll, 1500)
                }
            }
            poll = Runnable {
                if (done) return@Runnable
                if (tries++ > 20) { vi++; startVariant(); return@Runnable }
                web.evaluateJavascript(READ_JS) { res ->
                    if (done) return@evaluateJavascript
                    val txt = res?.trim('"')?.replace("\\\"", "\"") ?: ""
                    when {
                        txt.startsWith("{") -> {
                            try {
                                val o = org.json.JSONObject(txt)
                                val p = o.getJSONArray("p")
                                val v = p.optString(bandIdx).replace(",", "").toDoubleOrNull()
                                val prices = (0 until 5).map { p.optString(it).replace(",", "").toDoubleOrNull() }
                                val cpo = o.optString("cpo"); val kw = o.optString("kw"); val nm = o.optString("name")
                                if (!matches(variants[vi], o.optString("row"))) {
                                    mismatch = nm
                                    vi++; startVariant()
                                } else if (v != null && v > 0) {
                                    finish(Result(v, cpo, kw, "\"" + variants[vi] + "\" → " + nm + " · " + cpo + " · " + BAND_NAMES[bandIdx] + " " + String.format("%.0f", v) + "원", prices))
                                } else {
                                    finish(Result(null, cpo, kw, "\"" + variants[vi] + "\" → " + nm + " · " + cpo + " 은 " + BAND_NAMES[bandIdx] + " 단가 미등재", prices))
                                }
                            } catch (e: Throwable) { main.postDelayed(poll, 1000) }
                        }
                        txt == "EMPTY" && ++emptyStreak >= 4 -> { vi++; startVariant() }
                        txt == "NOROOT" && tries > 8 -> finish(Result(null, null, null, "요금표 페이지를 못 읽음 (구조 변경 또는 인터넷)"))
                        else -> main.postDelayed(poll, 1000)
                    }
                }
            }
            web.setWebViewClient(object : WebViewClient() {
                private var started = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (started || done) return
                    started = true
                    startVariant()
                }
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (failingUrl != null && failingUrl.startsWith(URL)) finish(Result(null, null, null, "요금표 페이지 로드 실패: " + (description ?: errorCode.toString())))
                }
            })
            // 전체 상한 (페이지가 영영 안 뜨는 경우)
            main.postDelayed({ finish(Result(null, null, null, "시간 초과 (120초)")) }, 120_000L)
            web.loadUrl(URL)
        }
    }
}
