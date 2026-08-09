package com.p4log.car

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Supabase REST(PostgREST) 업로드.
 * upsert 방식: on_conflict=(device_id, client_id) + Prefer: resolution=merge-duplicates
 */
object SyncManager {

    private const val TAG = "P4Log.Sync"

    /** 백그라운드 스레드에서 전체 업로드. 완료 시 결과 문자열 콜백 (메인스레드 아님 주의) */
    fun uploadAsync(context: Context, onDone: (String) -> Unit) {
        Thread {
            val result = uploadAll(context)
            onDone(result)
        }.start()
    }

    fun uploadAll(context: Context): String {
        val url = Prefs.supabaseUrl(context)
        val key = Prefs.supabaseKey(context)
        val deviceId = Prefs.deviceId(context)
        if (url.isEmpty() || key.isEmpty()) return "Supabase 설정이 비어 있음"

        val db = Db.get(context)
        var okCount = 0
        var failMsg: String? = null

        try {
            // 주행
            val trips = db.unsyncedTrips()
            for (t in trips) {
                val row = JSONObject()
                row.put("device_id", deviceId)
                row.put("client_id", t.id)
                row.put("start_ts", t.startTs)
                row.put("end_ts", t.endTs)
                row.put("distance_m", t.distanceM)
                row.put("energy_kwh", t.energyKwh ?: JSONObject.NULL)
                row.put("soc_start", t.socStart ?: JSONObject.NULL)
                row.put("soc_end", t.socEnd ?: JSONObject.NULL)
                row.put("avg_kmh", t.avgKmh)
                row.put("max_kmh", t.maxKmh)
                row.put("start_lat", t.startLat ?: JSONObject.NULL)
                row.put("start_lon", t.startLon ?: JSONObject.NULL)
                row.put("end_lat", t.endLat ?: JSONObject.NULL)
                row.put("end_lon", t.endLon ?: JSONObject.NULL)
                row.put("polyline", t.polyline)
                if (upsert(url, key, "trip", "device_id,client_id", row)) {
                    db.markTripSynced(t.id); okCount++
                } else { failMsg = "trip 업로드 실패"; break }
            }

            // 충전
            if (failMsg == null) {
                for (s in db.unsyncedCharges()) {
                    val row = JSONObject()
                    row.put("device_id", deviceId)
                    row.put("client_id", s.id)
                    row.put("start_ts", s.startTs)
                    row.put("end_ts", s.endTs)
                    row.put("kwh", s.kwh)
                    row.put("cost", s.cost)
                    row.put("soc_start", s.socStart ?: JSONObject.NULL)
                    row.put("soc_end", s.socEnd ?: JSONObject.NULL)
                    row.put("max_kw", s.maxKw)
                    row.put("type", s.type)
                    row.put("profile", s.profile)
                    row.put("station", s.station ?: JSONObject.NULL)
                    if (upsert(url, key, "charge", "device_id,client_id", row)) {
                        db.markChargeSynced(s.id); okCount++
                    } else { failMsg = "charge 업로드 실패"; break }
                }
            }

            // 주차 위치 (항상 최신 1건 upsert)
            if (failMsg == null) {
                val p = db.parking()
                if (p != null) {
                    val row = JSONObject()
                    row.put("device_id", deviceId)
                    row.put("ts", p.ts)
                    row.put("lat", p.lat)
                    row.put("lon", p.lon)
                    row.put("soc", p.socPct ?: JSONObject.NULL)
                    if (upsert(url, key, "parking", "device_id", row)) okCount++
                    else failMsg = "parking 업로드 실패"
                }
            }

            // 소모품 현황 스냅샷
            if (failMsg == null) {
                val totalKm = Prefs.totalKm(context)
                val now = System.currentTimeMillis()
                for (cons in db.consumables()) {
                    val usedKm = totalKm - cons.baseKm
                    val row = JSONObject()
                    row.put("device_id", deviceId)
                    row.put("name", cons.name)
                    row.put("cycle_km", cons.cycleKm)
                    row.put("cycle_months", cons.cycleMonths)
                    row.put("used_km", Math.round(usedKm))
                    row.put(
                        "remain_km",
                        if (cons.cycleKm > 0) Math.round(cons.cycleKm - usedKm) else JSONObject.NULL
                    )
                    row.put(
                        "remain_months",
                        if (cons.cycleMonths > 0)
                            cons.cycleMonths - ((now - cons.baseTs) / (30.44 * 24 * 3_600_000.0))
                        else JSONObject.NULL
                    )
                    row.put("updated_ts", now)
                    if (upsert(url, key, "consumable", "device_id,name", row)) okCount++
                    else { failMsg = "consumable 업로드 실패"; break }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sync error", e)
            failMsg = "오류: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }

        val time = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(Date())
        val result = if (failMsg == null) "$time 성공 ($okCount 건)" else "$time $failMsg"
        Prefs.setLastSyncResult(context, result)
        Prefs.setLastSyncTs(context, System.currentTimeMillis())
        return result
    }

    /** 연결 테스트: trip 테이블에 SELECT 1건 */
    fun testConnection(url: String, key: String): String {
        return try {
            val conn = URL("$url/rest/v1/trip?select=client_id&limit=1")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val code = conn.responseCode
            conn.disconnect()
            when (code) {
                200 -> "연결 성공 (200)"
                401, 403 -> "키가 잘못됨 ($code)"
                404 -> "테이블 없음 — SQL 실행했는지 확인 (404)"
                else -> "응답 코드: $code"
            }
        } catch (e: Throwable) {
            "연결 실패: ${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    private fun upsert(
        baseUrl: String, key: String, table: String, conflictCols: String, row: JSONObject
    ): Boolean {
        return try {
            val conn = URL("$baseUrl/rest/v1/$table?on_conflict=$conflictCols")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val body = JSONArray().put(row).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(200)
                } catch (e: Throwable) { null }
                Log.w(TAG, "upsert $table failed: $code $err")
            }
            conn.disconnect()
            code in 200..299
        } catch (e: Throwable) {
            Log.w(TAG, "upsert $table exception", e)
            false
        }
    }
}
