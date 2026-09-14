package com.p4log.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 좌표 → 동네 이름 (역지오코딩). 주행 기록 목록에 "출발 → 도착"으로 쓴다 (2026-09-06, 사용자 요청).
 *
 * 1순위 VWorld 주소 API(지도 타일과 같은 키), 실패하면 Android Geocoder(폴스타4엔 구글 서비스가 있다).
 * 결과는 trip.start_place / end_place(DB v6)에 저장해 두 번 다시 안 묻는다.
 * 차량 부하: 주행 1건당 요청 2번(끝났을 때 한 번), 같은 좌표(약 100m 격자)는 메모리 캐시.
 */
object PlaceNames {
    private const val VWORLD_KEY = ""   // vworld.kr 인증키 (map.html과 같은 키)   // map.html과 같은 키 (APK 공유 금지 사유)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, String>()
    private val inFlight = HashSet<Long>()

    private fun key(lat: Double, lon: Double) = String.format("%.3f,%.3f", lat, lon)

    /** 동네 이름으로 쓸 만한지: 한글이 들어 있는 것만 ("20", "1-3" 같은 번지는 버린다 — 2026-09-13 "관악구 20" 수정) */
    private fun goodName(s: String?): String? {
        val t = s?.trim() ?: return null
        if (t.isEmpty()) return null
        if (!t.any { it in '\uAC00'..'\uD7A3' }) return null
        return t
    }

    /** 동기 조회 (백그라운드 스레드에서만). 실패하면 null */
    fun lookup(context: Context, lat: Double, lon: Double): String? {
        val k = key(lat, lon)
        synchronized(cache) { cache[k]?.let { return it } }
        val name = try { vworld(lat, lon) } catch (e: Throwable) { null }
            ?: try { geocoder(context, lat, lon) } catch (e: Throwable) { null }
        if (name != null) synchronized(cache) { cache[k] = name }
        return name
    }

    /**
     * 주행의 출발·도착 이름을 백그라운드에서 채우고 DB에 저장한 뒤 onDone(main 스레드)을 부른다.
     * 이미 진행 중인 주행 id는 무시. id가 음수(데모)면 DB엔 안 쓴다.
     */
    fun resolveAsync(context: Context, tripId: Long, sLat: Double?, sLon: Double?, eLat: Double?, eLon: Double?,
                     onDone: ((String?, String?) -> Unit)? = null) {
        if (sLat == null && eLat == null) return
        synchronized(inFlight) { if (!inFlight.add(tripId)) return }
        val app = context.applicationContext
        exec.execute {
            val s = if (sLat != null && sLon != null) lookup(app, sLat, sLon) else null
            val e = if (eLat != null && eLon != null) lookup(app, eLat, eLon) else null
            if (tripId > 0 && (s != null || e != null)) {
                try { Db.get(app).setTripPlaces(tripId, s, e) } catch (t: Throwable) {}
            }
            ServiceLog.add(app, "주소 조회 #" + tripId + ": " + (s ?: "?") + " → " + (e ?: "?"))
            synchronized(inFlight) { inFlight.remove(tripId) }
            if (onDone != null) main.post { onDone(s, e) }
        }
    }

    /** VWorld getAddress: 지번(parcel) 기준 "구 동" — 예: "관악구 봉천동". 없으면 도로명 기준 */
    private fun vworld(lat: Double, lon: Double): String? {
        for (type in arrayOf("parcel", "road")) {
            val url = "https://api.vworld.kr/req/address?service=address&request=getAddress&version=2.0" +
                "&crs=epsg:4326&format=json&type=" + type + "&zipcode=false&simple=false" +
                "&point=" + lon + "," + lat + "&key=" + VWORLD_KEY
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.setRequestProperty("Referer", "https://p4log.app/")
            try {
                if (conn.responseCode != 200) continue
                val body = conn.inputStream.bufferedReader().readText()
                val resp = JSONObject(body).optJSONObject("response") ?: continue
                if (resp.optString("status") != "OK") continue
                val r0 = resp.optJSONArray("result")?.optJSONObject(0) ?: continue
                val st = r0.optJSONObject("structure")
                if (st != null) {
                    val gu = goodName(st.optString("level2")) ?: goodName(st.optString("level1")) ?: ""
                    val dong = goodName(st.optString("level4L")) ?: goodName(st.optString("level4A")) ?: goodName(st.optString("level3"))
                    if (dong != null) return if (gu.isNotEmpty()) "$gu $dong" else dong
                    if (gu.isNotEmpty()) return gu
                }
                val text = r0.optString("text").trim()
                if (text.isNotEmpty()) return text
            } finally { conn.disconnect() }
        }
        return null
    }

    /** Android Geocoder 폴백 (구글 서비스 필요). "구 동" 형태로 */
    @Suppress("DEPRECATION")
    private fun geocoder(context: Context, lat: Double, lon: Double): String? {
        if (!android.location.Geocoder.isPresent()) return null
        val list = android.location.Geocoder(context, java.util.Locale.KOREA).getFromLocation(lat, lon, 1)
        val a = list?.firstOrNull() ?: return null
        // subThoroughfare는 건물 번호("20")라 동네 이름이 못 된다 — 한글 이름만 채택, 없으면 구 이름만
        val gu = goodName(a.subLocality) ?: goodName(a.locality) ?: ""
        val dong = goodName(a.thoroughfare) ?: ""
        val s = (gu + " " + dong).trim()
        return s.ifEmpty { goodName(a.getAddressLine(0)) }
    }
}
