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

            // 충전 위치 소급 (2026-09-22): 서버에 st_lat 컬럼이 생긴 게 확인되면 한 번만, 위치 있는 옛 충전을 다시 올린다
            if (failMsg == null && !Prefs.chargeLocBackfillDone(context) && probeColumn(url, key, "charge", "st_lat")) {
                val n = db.unsyncChargesWithLocation()
                Prefs.setChargeLocBackfillDone(context)
                ServiceLog.add(context, "충전 위치 소급 업로드: " + n + "건 다시 올림 (서버 v7 확인)")
            }

            // 충전
            var chargeLocCols = true
            var chargeKindCol = true   // 서버 v8 charge.kind (2026-09-24)
            var chargePlaceCol = true  // 서버 v9 charge.place (2026-09-25)
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
                    if (chargeLocCols) {   // 충전 위치 (서버 v7, 2026-09-22 — 폰 지도 선택용)
                        row.put("st_lat", s.stLat ?: JSONObject.NULL)
                        row.put("st_lon", s.stLon ?: JSONObject.NULL)
                    }
                    if (chargeKindCol) row.put("kind", s.kind ?: JSONObject.NULL)   // 내 충전기 집/회사 (서버 v8)
                    if (chargePlaceCol) row.put("place", s.place ?: JSONObject.NULL) // 충전 위치 동네 이름 (서버 v9)
                    var ok = upsert(url, key, "charge", "device_id,client_id", row)
                    if (!ok && chargePlaceCol && (lastUpsertError ?: "").contains("place")) {
                        chargePlaceCol = false
                        row.remove("place")
                        ServiceLog.add(context, "동기화: 서버 charge 테이블에 place 컬럼 없음 — 빼고 올림 (supabase_setup.sql v9 실행 필요)")
                        ok = upsert(url, key, "charge", "device_id,client_id", row)
                    }
                    if (!ok && chargeKindCol && (lastUpsertError ?: "").contains("kind")) {
                        chargeKindCol = false
                        row.remove("kind")
                        ServiceLog.add(context, "동기화: 서버 charge 테이블에 kind 컬럼 없음 — 빼고 올림 (supabase_setup.sql v8 실행 필요)")
                        ok = upsert(url, key, "charge", "device_id,client_id", row)
                    }
                    if (!ok && chargeLocCols && (lastUpsertError ?: "").contains("column")) {
                        chargeLocCols = false
                        row.remove("st_lat"); row.remove("st_lon")
                        ServiceLog.add(context, "동기화: 서버 charge 테이블에 st_lat/st_lon 컬럼 없음 — 빼고 올림 (supabase_setup.sql v7 실행 필요)")
                        ok = upsert(url, key, "charge", "device_id,client_id", row)
                    }
                    if (ok) {
                        db.markChargeSynced(s.id); okCount++
                    } else { failMsg = "charge 업로드 실패"; break }
                }
            }

            // 폰(웹)에서 고친 충전 단가·충전소 받아오기 (2026-09-21 양방향 동기화, 사용자 요청)
            if (failMsg == null) pullChargeEdits(context, url, key, deviceId, db)

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

    @Volatile private var pullColumnMissingLogged = false

    /**
     * 폰/웹에서 수정한 충전 기록 받기 (2026-09-21): 서버 charge 행에 `edited_ts`(ms)가 마지막으로 받아온 시각보다 크면
     * 그 요금·충전소를 차량 로컬 기록에 반영하고, 충전소 프로필(150m)에도 단가를 기억시켜 다음 충전부터 자동 적용된다.
     * 차량 쪽 수정은 기존대로 재업로드(synced=0)로 서버에 가므로 양방향이 된다. 서버에 컬럼이 없으면(SQL v5 전) 한 번만 로그.
     */
    private fun pullChargeEdits(context: Context, baseUrl: String, key: String, deviceId: String, db: Db) {
        val since = Prefs.lastChargePullTs(context)
        try {
            // kind(서버 v8)가 아직 없으면 400 → kind 없이 한 번 더 (2026-09-24)
            var body: String? = null
            for (withKind in listOf(true, false)) {
                val sel = "client_id,cost,kwh,station,edited_ts" + (if (withKind) ",kind" else "")
                val conn = URL("$baseUrl/rest/v1/charge?device_id=eq.$deviceId&edited_ts=gt.$since" +
                    "&select=$sel&order=edited_ts.asc&limit=100").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("apikey", key)
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.connectTimeout = 8000; conn.readTimeout = 15_000
                val code = conn.responseCode
                if (code == 200) { body = conn.inputStream.bufferedReader().readText(); conn.disconnect(); break }
                conn.disconnect()
                if (code == 400 && withKind) continue
                if (code == 400 && !pullColumnMissingLogged) {
                    pullColumnMissingLogged = true
                    ServiceLog.add(context, "양방향 동기화: 서버 charge 테이블에 edited_ts 컬럼 없음 — supabase_setup.sql v5 실행 필요")
                }
                return
            }
            if (body == null) return
            val arr = JSONArray(body)
            var maxTs = since
            var applied = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optLong("client_id", -1L)
                val ts = o.optLong("edited_ts", 0L)
                if (ts > maxTs) maxTs = ts
                val local = db.chargeById(id) ?: continue     // 재설치 전 기록(client_id+100000 등)은 차량에 없다
                val cost = o.optDouble("cost", Double.NaN)
                if (cost.isNaN()) continue
                val station = if (o.isNull("station")) null else o.optString("station").ifBlank { null }
                val kind = if (!o.has("kind") || o.isNull("kind")) null else o.optString("kind").ifBlank { null }
                db.applyRemoteChargeEdit(id, cost, station, kind)
                if (local.stLat != null && local.stLon != null && local.kwh > 0.01) {
                    // 폰에서 집/회사로 지정했으면 프로필도 내 충전기(정액)로 — 단가는 계량 kWh 기준 (2026-09-24)
                    val meter = HomeTariff.meterKwh(local.kwh, kind, local.type)
                    db.upsertStationProfile(local.stLat, local.stLon, station ?: local.station ?: "직접 지정 충전소", null, cost / meter,
                        null, kind, if (kind != null) "flat" else null)
                }
                applied++
            }
            if (maxTs > since) Prefs.setLastChargePullTs(context, maxTs)
            if (applied > 0) ServiceLog.add(context, "폰에서 수정한 충전 " + applied + "건 반영 (요금·충전소 기억값 갱신)")
        } catch (e: Throwable) {
            Log.w(TAG, "pullChargeEdits failed", e)
        }
    }

    /**
     * 서버 사용량 (2026-09-21, 사용자: "무료 한도 안에서 얼마나 쓰고 얼마나 남았는지"):
     * DB 크기·행 수는 서버 함수 plog_usage()(SQL v6), 저장소는 parking 버킷 파일 크기 합. 무료 한도 DB 500MB / Storage 1GB와 비교한 한 줄.
     * 전송량(월 5GB)은 관리 API(개인 토큰)가 있어야 해서 여기선 못 잰다. 워커 스레드에서 부를 것
     */
    class Usage(val dbBytes: Long, val storeBytes: Long, val trips: Long, val charges: Long, val dbErr: String?) {
        companion object { const val DB_LIMIT = 500L * 1_048_576L; const val STORE_LIMIT = 1024L * 1_048_576L }
        fun dbPct(): Float = if (dbBytes >= 0) dbBytes * 100f / DB_LIMIT else 0f
        fun storePct(): Float = if (storeBytes >= 0) storeBytes * 100f / STORE_LIMIT else 0f
        fun line(): String {
            val mb = { b: Long -> String.format("%.1f MB", b / 1_048_576.0) }
            val sb = StringBuilder("서버 사용량 · ")
            if (dbBytes >= 0) sb.append("DB ").append(mb(dbBytes)).append(" / 500 MB (").append(Math.round(dbPct())).append("%)") else sb.append(dbErr ?: "DB ?")
            sb.append(" · 저장소 ").append(if (storeBytes >= 0) mb(storeBytes) + " / 1 GB" else "?")
            if (trips >= 0) sb.append(" · 주행 ").append(trips).append("건 · 충전 ").append(charges).append("건")
            return sb.toString()
        }
    }

    fun fetchUsage(url: String, key: String): Usage {
        var dbBytes = -1L; var trips = -1L; var charges = -1L
        var dbErr: String? = null
        try {
            val conn = URL("$url/rest/v1/rpc/plog_usage").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("apikey", key); conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000; conn.readTimeout = 15_000
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write("{}") }
            val code = conn.responseCode
            if (code == 200) {
                val o = JSONObject(conn.inputStream.bufferedReader().readText())
                dbBytes = o.optLong("db_bytes", -1L); trips = o.optLong("trips", -1L); charges = o.optLong("charges", -1L)
            } else dbErr = if (code == 404) "서버 함수 없음 (supabase_setup.sql v6 실행 필요)" else "DB 크기 조회 실패 ($code)"
            conn.disconnect()
        } catch (e: Throwable) { dbErr = "DB 크기 조회 실패: " + e.javaClass.simpleName }
        var storeBytes = -1L
        try {
            val conn = URL("$url/storage/v1/object/list/parking").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("apikey", key); conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 8000; conn.readTimeout = 15_000
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write("{\"prefix\":\"\",\"limit\":1000}") }
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                var sum = 0L
                for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optJSONObject("metadata")?.optLong("size", 0L) ?: 0L
                storeBytes = sum
            }
            conn.disconnect()
        } catch (e: Throwable) { /* 저장소는 못 재도 DB 줄은 보여준다 */ }
        return Usage(dbBytes, storeBytes, trips, charges, dbErr)
    }

    /** 서버 테이블에 컬럼이 있는지 (SELECT 컬럼 LIMIT 1 → 200이면 있음). 소급 업로드 판정용 (2026-09-22) */
    private fun probeColumn(baseUrl: String, key: String, table: String, column: String): Boolean {
        return try {
            val conn = URL("$baseUrl/rest/v1/$table?select=$column&limit=1").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 8000; conn.readTimeout = 10_000
            val ok = conn.responseCode == 200
            conn.disconnect(); ok
        } catch (e: Throwable) { false }
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
