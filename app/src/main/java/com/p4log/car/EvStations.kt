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
    // data.go.kr 일반 인증키 — URL 인코딩된 상태 그대로 써야 함 (재인코딩 금지)
    private const val API_KEY_ENC = "" // data.go.kr "한국환경공단_전기자동차 충전소 정보" 인증키(URL 인코딩 형태)를 입력
    private const val PROFILE_RADIUS_M = 150.0
    private const val API_RADIUS_M = 300.0
    private const val ROWS_PER_PAGE = 9999
    private const val MAX_PAGES = 6

    data class Found(val name: String, val operator: String?, val rate: Double?)

    private data class ApiHit(val name: String, val operator: String?, val lat: Double, val lon: Double)

    /**
     * 운영사별 공표 단가 (비회원, 원/kWh). 확실한 것만 등록 — 없으면 null(설정의 기본 요금 사용).
     * 단가는 수시로 바뀌므로 갱신은 수동 (Claude에게 "운영사 요금표 갱신" 요청)
     */
    fun operatorRate(operator: String?, type: String): Double? {
        if (operator == null) return null
        val dc = type == "DC"
        return when {
            operator.contains("환경부") -> if (dc) 347.2 else 292.9
            else -> null
        }
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
        if (prof != null) {
            Log.i(TAG, "profile hit: ${prof.name}")
            return Found(prof.name, prof.operator, prof.rate)
        }
        if (API_KEY_ENC.isEmpty()) return null // 키 미설정 시 충전소 식별 생략
        val zcode = zcodeOf(lat, lon) ?: return null
        val hit = queryNearest(zcode, lat, lon) ?: return null
        Log.i(TAG, "api hit: ${hit.name} (${hit.operator})")
        db.upsertStationProfile(hit.lat, hit.lon, hit.name, hit.operator, null)
        return Found(hit.name, hit.operator, null)
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

    /** 해당 시도의 충전기 목록을 페이지 단위로 훑어 반경 내 최근접 충전소를 찾는다 */
    private fun queryNearest(zcode: String, lat: Double, lon: Double): ApiHit? {
        var best: ApiHit? = null
        var bestD = API_RADIUS_M
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
                if (conn.responseCode != 200) return best
                val parser = Xml.newPullParser()
                parser.setInput(conn.inputStream, "UTF-8")
                var tag = ""
                var name: String? = null; var op: String? = null
                var iLat: Double? = null; var iLon: Double? = null
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
                        }
                        XmlPullParser.END_TAG -> {
                            tag = ""
                            if (parser.name == "item") {
                                if (name != null && iLat != null && iLon != null) {
                                    Location.distanceBetween(lat, lon, iLat, iLon, dist)
                                    if (dist[0] <= bestD) {
                                        bestD = dist[0].toDouble()
                                        best = ApiHit(name, op, iLat, iLon)
                                    }
                                }
                                name = null; op = null; iLat = null; iLon = null
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
        return best
    }
}
