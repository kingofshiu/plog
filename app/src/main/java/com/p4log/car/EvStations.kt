package com.p4log.car

import android.content.Context
import android.location.Location
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * 충전소 자동 식별 (2026-08-09).
 * 우선순위: ① 저장된 충전소 프로필(150m) → ② 환경공단 EvCharger API(300m) → ③ null(기본 요금)
 * API 히트 시 프로필로 저장해 다음부터는 API 호출 없이 인식된다.
 * API: data.go.kr "한국환경공단_전기자동차 충전소 정보" (키는 사용자 발급, 2026-08-09)
 */
object EvStations {

    private const val TAG = "P4Log.EvSt"
    // data.go.kr 일반 인증키 — URL 인코딩된 상태 그대로 써야 함 (재인코딩 금지).
    // 2026-09-15: 설정 화면 값(Prefs.dataGoKey). 서비스 시작·설정 저장 때 init()으로 채운다
    @Volatile private var API_KEY_ENC = ""
    fun init(context: Context) { API_KEY_ENC = Prefs.dataGoKey(context) }
    private const val PROFILE_RADIUS_M = 150.0
    private const val API_RADIUS_M = 300.0
    private const val ROWS_PER_PAGE = 9999
    private const val MAX_PAGES = 6

    /** outputs: 그 충전소에 있는 충전기 정격 출력 목록 (kW, 쉼표 구분. 예 "50,100,200") */
    data class Found(val name: String, val operator: String?, val rate: Double?, val outputs: String?)

    private data class ApiHit(val name: String, val operator: String?, val lat: Double,
                              val lon: Double, val outputs: String?)

    /** 사용자가 직접 고를 후보 충전소 (2026-09-08). distM = 충전 위치에서의 거리 */
    data class Candidate(val name: String, val operator: String?, val lat: Double, val lon: Double,
                         val outputs: String?, val distM: Double)

    /**
     * 충전 위치 반경 radiusM 안의 충전소 후보를 거리순으로 (최대 limit개). 사용자가 지도/목록에서 직접 고르는 용도.
     * 콜백은 main 스레드. API 실패면 빈 목록.
     */
    fun nearbyAsync(lat: Double, lon: Double, radiusM: Double, limit: Int, cb: (List<Candidate>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val list = try {
                val zcode = zcodeOf(lat, lon)
                if (zcode == null) emptyList() else queryAround(zcode, lat, lon, radiusM).take(limit).map {
                    Candidate(it.name, it.operator, it.lat, it.lon,
                        if (it.outputs.isEmpty()) null else it.outputs.joinToString(","), it.dist)
                }
            } catch (e: Throwable) { Log.w(TAG, "nearby failed", e); emptyList() }
            main.post { cb(list) }
        }.start()
    }

    /**
     * 환경부 회원카드 로밍 단가 (원/kWh) — 충전기 정격 출력 구간별 5단계.
     * 전국 공표 표준 단가라 운영사와 무관하게 적용한다 (2026-08-18 기준).
     * 단가가 개정되면 이 표의 숫자만 고치면 된다.
     */
    fun roamingRate(outputKw: Double): Double = when {
        outputKw < 30.0 -> 295.0
        outputKw < 50.0 -> 307.2
        outputKw < 100.0 -> 325.6
        outputKw < 200.0 -> 348.4
        else -> 393.1
    }

    /**
     * 실측 최대 전력으로 "실제로 꽂았던 충전기의 정격 출력"을 고른다.
     * 실측은 항상 정격보다 낮게 나오므로(200kW기에서 175kW 등) 정격 후보 중
     * 실측의 93% 이상인 가장 작은 값을 고른다. 후보가 없으면(=출력 정보 없음) 실측값 그대로.
     */
    fun ratedOutputFor(outputsCsv: String?, measuredMaxKw: Double): Double {
        val cands = outputsCsv?.split(',')
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.filter { it > 0.0 }
            ?.sorted()
        if (cands == null || cands.isEmpty()) return measuredMaxKw
        val need = measuredMaxKw * 0.93
        return cands.firstOrNull { it >= need } ?: cands.last()
    }

    /** 백그라운드에서 충전소 식별. 콜백은 워커 스레드에서 호출됨 */
    fun resolveAsync(context: Context, lat: Double, lon: Double, cb: (Found?) -> Unit) {
        Thread {
            val found = try {
                resolve(context, lat, lon)
            } catch (e: Throwable) {
                Log.w(TAG, "resolve failed", e)
                null
            }
            cb(found)
        }.start()
    }

    private fun resolve(context: Context, lat: Double, lon: Double): Found? {
        val db = Db.get(context)
        val prof = db.nearestStationProfile(lat, lon, PROFILE_RADIUS_M)
        // 출력 정보까지 있는 프로필이면 API 없이 끝. (v3 이전에 저장된 프로필은 출력이 없어
        //  한 번 더 API를 불러 채운다 — 그 다음부터는 다시 API 없이 인식된다)
        if (prof != null && prof.outputs != null) {
            Log.i(TAG, "profile hit: ${prof.name} [${prof.outputs}kW]")
            return Found(prof.name, prof.operator, prof.rate, prof.outputs)
        }
        val zcode = zcodeOf(lat, lon)
        val hit = if (zcode != null) queryNearest(zcode, lat, lon) else null
        if (hit == null) {
            // API 실패/범위 밖 — 출력 없는 기존 프로필이라도 있으면 그걸로 인식만 한다
            return prof?.let { Found(it.name, it.operator, it.rate, null) }
        }
        Log.i(TAG, "api hit: ${hit.name} (${hit.operator}) [${hit.outputs}kW]")
        db.upsertStationProfile(hit.lat, hit.lon, hit.name, hit.operator, null, hit.outputs)
        return Found(hit.name, hit.operator, prof?.rate, hit.outputs)
    }

    private class Box(val zcode: String, val latMin: Double, val latMax: Double,
                      val lonMin: Double, val lonMax: Double)

    // 시도 대략 경계 상자 — 광역시가 도 상자에 포함되므로 광역시를 앞에 배치 (첫 매칭 우선)
    private val BOXES = listOf(
        Box("26", 34.95, 35.40, 128.75, 129.35), // 부산
        Box("31", 35.35, 35.75, 129.00, 129.50), // 울산
        Box("27", 35.60, 36.05, 128.35, 128.80), // 대구
        Box("29", 35.05, 35.25, 126.70, 127.05), // 광주
        Box("30", 36.15, 36.50, 127.25, 127.60), // 대전
        Box("36", 36.40, 36.75, 127.15, 127.40), // 세종
        Box("11", 37.42, 37.72, 126.75, 127.20), // 서울
        Box("28", 37.20, 37.80, 126.20, 126.80), // 인천
        Box("41", 36.85, 38.30, 126.35, 127.90), // 경기
        Box("51", 37.00, 38.65, 127.05, 129.40), // 강원
        Box("43", 36.00, 37.25, 127.25, 128.65), // 충북
        Box("44", 35.95, 37.10, 125.95, 127.40), // 충남
        Box("52", 35.30, 36.20, 126.35, 127.90), // 전북
        Box("46", 33.90, 35.50, 125.05, 127.55), // 전남
        Box("47", 35.55, 37.15, 127.80, 129.60), // 경북
        Box("48", 34.55, 35.95, 127.55, 129.30), // 경남
        Box("50", 33.10, 33.60, 126.10, 127.05)  // 제주
    )

    /** 좌표가 속한 시도의 zcode. 어느 상자에도 없으면 null(식별 포기) */
    private fun zcodeOf(lat: Double, lon: Double): String? =
        BOXES.firstOrNull { lat in it.latMin..it.latMax && lon in it.lonMin..it.lonMax }?.zcode

    /** 충전소 하나(= statNm 하나)에 속한 충전기들을 모은 집계 */
    private class Agg(val name: String, val lat: Double, val lon: Double) {
        var operator: String? = null
        var dist = Double.MAX_VALUE
        val outputs = java.util.TreeSet<Int>()   // 정격 출력(kW) 오름차순, 중복 제거
    }

    /**
     * 해당 시도의 충전기 목록을 페이지 단위로 훑어 반경 내 최근접 충전소를 찾는다.
     * 충전소는 충전기 여러 대를 갖고 있으므로 statNm 단위로 묶어 정격 출력 목록까지 모은다.
     */
    private fun queryNearest(zcode: String, lat: Double, lon: Double): ApiHit? {
        val best = queryAround(zcode, lat, lon, API_RADIUS_M).firstOrNull() ?: return null
        return ApiHit(
            best.name, best.operator, best.lat, best.lon,
            if (best.outputs.isEmpty()) null else best.outputs.joinToString(",")
        )
    }

    /** 반경 radiusM 안의 충전소들을 statNm 단위로 묶어 거리순으로 */
    private fun queryAround(zcode: String, lat: Double, lon: Double, radiusM: Double): List<Agg> {
        if (API_KEY_ENC.isBlank()) { Log.w(TAG, "data.go.kr key missing"); return emptyList() }
        val near = HashMap<String, Agg>()
        var totalCount = Int.MAX_VALUE
        var page = 1
        val dist = FloatArray(1)

        while (page <= MAX_PAGES && (page - 1) * ROWS_PER_PAGE < totalCount) {
            val conn = URL(
                "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo" +
                    "?serviceKey=$API_KEY_ENC&pageNo=$page&numOfRows=$ROWS_PER_PAGE&zcode=$zcode"
            ).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 40_000
            try {
                if (conn.responseCode != 200) break
                val parser = Xml.newPullParser()
                parser.setInput(conn.inputStream, "UTF-8")
                var tag = ""
                var name: String? = null; var op: String? = null
                var iLat: Double? = null; var iLon: Double? = null
                var outKw: Int? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> tag = parser.name
                        XmlPullParser.TEXT -> when (tag) {
                            "totalCount" -> parser.text.trim().toIntOrNull()?.let { totalCount = it }
                            "statNm" -> name = parser.text
                            "busiNm" -> op = parser.text
                            "lat" -> iLat = parser.text.trim().toDoubleOrNull()
                            "lng" -> iLon = parser.text.trim().toDoubleOrNull()
                            // output = 충전기 정격 출력(kW). 요금 구간 판정의 핵심 값
                            "output" -> outKw = parser.text.trim().toDoubleOrNull()?.let {
                                Math.round(it).toInt()
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            tag = ""
                            if (parser.name == "item") {
                                // 클로저에 담기므로 불변 지역변수로 옮겨 받는다
                                val n = name; val la = iLat; val lo = iLon; val kw = outKw
                                if (n != null && la != null && lo != null) {
                                    Location.distanceBetween(lat, lon, la, lo, dist)
                                    if (dist[0] <= radiusM) {
                                        var agg = near[n]
                                        if (agg == null) {
                                            agg = Agg(n, la, lo)
                                            near[n] = agg
                                        }
                                        if (dist[0] < agg.dist) agg.dist = dist[0].toDouble()
                                        if (agg.operator == null) agg.operator = op
                                        if (kw != null && kw > 0) agg.outputs.add(kw)
                                    }
                                }
                                name = null; op = null; iLat = null; iLon = null; outKw = null
                            }
                        }
                    }
                    event = parser.next()
                }
            } finally {
                conn.disconnect()
            }
            page++
        }

        return near.values.sortedBy { it.dist }
    }
}
