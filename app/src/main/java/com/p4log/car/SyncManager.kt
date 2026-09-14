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
        var softFail: String? = null   // 전체를 실패로 만들지 않는 부분 실패

        try {
            // 주행. 회생·출발·도착 동네는 서버 trip 테이블에 컬럼이 있어야 한다 (supabase_setup.sql v4, 2026-09-13).
            // 아직 안 만든 서버면 PostgREST가 "column ... does not exist"(400)를 주므로 그 3개를 빼고 한 번 더 올린다
            var extraCols = true
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
                if (extraCols) {
                    row.put("regen_kwh", t.regenKwh ?: JSONObject.NULL)
                    row.put("start_place", t.startPlace ?: JSONObject.NULL)
                    row.put("end_place", t.endPlace ?: JSONObject.NULL)
                }
                var ok = upsert(url, key, "trip", "device_id,client_id", row)
                if (!ok && extraCols && (lastUpsertError ?: "").contains("column")) {
                    extraCols = false
                    row.remove("regen_kwh"); row.remove("start_place"); row.remove("end_place")
                    ServiceLog.add(context, "동기화: 서버 trip 테이블에 regen_kwh/start_place/end_place 컬럼 없음 — 빼고 올림 (supabase_setup.sql v4 실행 필요)")
                    ok = upsert(url, key, "trip", "device_id,client_id", row)
                }
                if (ok) {
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

            // 주차 위치 (항상 최신 1건 upsert) + 주차 사진
            if (failMsg == null) {
                val p = db.parking()
                if (p != null) {
                    val row = JSONObject()
                    row.put("device_id", deviceId)
                    row.put("ts", p.ts)
                    row.put("lat", p.lat)
                    row.put("lon", p.lon)
                    row.put("soc", p.socPct ?: JSONObject.NULL)
                    row.put("photo_ts", p.photoTs ?: JSONObject.NULL)
                    // 주차 행 업로드가 실패해도 **뒤 단계를 막지 않는다.**
                    // 예전엔 여기서 failMsg가 설정돼 진단 로그 업로드까지 통째로 건너뛰었고,
                    // 그래서 svclog가 하루씩 밀려 올라왔다 (2026-09-02 발견).
                    // 주차 행은 매번 최신값으로 덮어쓰므로 이번에 실패해도 다음 동기화에서 복구된다
                    if (upsert(url, key, "parking", "device_id", row)) okCount++
                    else softFail = "parking 업로드 실패"

                    // 새 사진이 있으면 Storage에 업로드 (한 번만)
                    val photoTs = p.photoTs
                    if (photoTs != null &&
                        photoTs > Prefs.lastPhotoUploadTs(context)) {
                        val file = java.io.File(context.filesDir, "parking.jpg")
                        if (file.exists() && uploadPhoto(url, key, deviceId, file)) {
                            Prefs.setLastPhotoUploadTs(context, photoTs)
                            okCount++
                        }
                    }
                }
            }

            // 서비스 이력 (진단용, 몇 KB). 이건 **항상** 올린다 —
            // 다른 게 실패했을 때야말로 로그가 필요하기 때문이다
            uploadServiceLog(context, url, key, deviceId)

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
        val result = when {
            failMsg != null -> "$time $failMsg"
            softFail != null -> "$time 성공 ($okCount 건, $softFail)"
            else -> "$time 성공 ($okCount 건)"
        }
        Prefs.setLastSyncResult(context, result)
        Prefs.setLastSyncTs(context, System.currentTimeMillis())
        // 실패만 이력에 남긴다 — "차엔 기록이 있는데 서버엔 없다"를 원격에서 판정하기 위함
        if (failMsg != null) ServiceLog.add(context, "동기화 실패: " + failMsg)
        else if (softFail != null) ServiceLog.add(context, "동기화 일부 실패: " + softFail)
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

    /** Storage(parking 버킷)에 바이트를 올린다. 같은 경로면 덮어씀 */
    private fun uploadBytes(
        baseUrl: String, key: String, path: String, contentType: String, bytes: ByteArray
    ): Boolean {
        return try {
            val conn = URL("$baseUrl/storage/v1/object/parking/$path")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("x-upsert", "true")
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) Log.w(TAG, "upload $path failed: $code")
            conn.disconnect()
            code in 200..299
        } catch (e: Throwable) {
            Log.w(TAG, "upload $path exception", e)
            false
        }
    }

    /** 주차 사진을 Supabase Storage(parking 버킷)에 업로드 */
    private fun uploadPhoto(baseUrl: String, key: String, deviceId: String, file: java.io.File): Boolean =
        uploadBytes(baseUrl, key, "$deviceId.jpg", "image/jpeg", file.readBytes())

    /**
     * 서비스 이력을 텍스트로 Storage에 올린다 (2026-08-19).
     *
     * 실차에는 logcat이 없고, 문제가 생길 때마다 진단 화면을 캡처해 달라고 하는 것도 현실적이지 않다.
     * 이 파일만 있으면 서비스가 언제 죽고 왜 못 살아났는지, 사진이 왜 실패했는지 원격에서 바로 보인다.
     * 담기는 것은 시각·이벤트명·권한 상태뿐이다 (위치나 개인 정보는 넣지 않는다).
     */
    private fun uploadServiceLog(
        context: Context, baseUrl: String, key: String, deviceId: String
    ): Boolean {
        val sb = StringBuilder()
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA)
            .format(java.util.Date())
        sb.append("device=").append(deviceId).append('\n')
        sb.append("uploaded=").append(now).append('\n')
        sb.append("serviceRunning=").append(LoggerService.running).append('\n')
        sb.append("batteryExempt=").append(
            try {
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Throwable) { "?" }
        ).append('\n')
        sb.append("exactAlarm=").append(
            try {
                val am = context.getSystemService(android.app.AlarmManager::class.java)
                android.os.Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms())
            } catch (e: Throwable) { "?" }
        ).append('\n')
        sb.append("bgLocation=").append(
            context.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ).append('\n')
        sb.append("sdk=").append(android.os.Build.VERSION.SDK_INT)
            .append(" android=").append(android.os.Build.VERSION.RELEASE ?: "?").append('\n')
        sb.append("cdm=").append(CompanionLink.status(context)).append('\n')
        sb.append("cameraPerm=").append(
            context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ).append('\n')
        sb.append("lastAlive=").append(ServiceLog.lastAlive(context)).append('\n')
        sb.append("lastSync=").append(Prefs.lastSyncResult(context)).append('\n')
        val localDb = Db.get(context)
        sb.append("unsynced=trip ").append(localDb.unsyncedTrips().size)
            .append(" / charge ").append(localDb.unsyncedCharges().size).append('\n')
        val snap = LoggerService.lastSnapshot
        sb.append("gpsFix=").append(snap.gpsFix)
            .append(" tripActive=").append(snap.tripActive)
            .append(" carConnected=").append(snap.carConnected).append('\n')
        sb.append("---- 서비스 이력 ----\n")
        for (l in ServiceLog.lines(context)) sb.append(l).append('\n')
        return uploadBytes(
            baseUrl, key, "$deviceId-svclog.txt", "text/plain; charset=utf-8",
            sb.toString().toByteArray(Charsets.UTF_8)
        )
    }

    /** 마지막 upsert 실패의 서버 응답 본문 (컬럼 없음 판정용) */
    @Volatile private var lastUpsertError: String? = null

    private fun upsert(
        baseUrl: String, key: String, table: String, conflictCols: String, row: JSONObject
    ): Boolean {
        lastUpsertError = null
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
                lastUpsertError = err
            }
            conn.disconnect()
            code in 200..299
        } catch (e: Throwable) {
            Log.w(TAG, "upsert $table exception", e)
            false
        }
    }
}
