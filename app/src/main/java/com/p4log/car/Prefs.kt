package com.p4log.car

import android.content.Context
import android.content.SharedPreferences

/** 설정 저장 (SharedPreferences 래퍼) */
object Prefs {
    private const val FILE = "p4log_prefs"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // 기본 요금 (원/kWh) — 충전소 인식 실패 시 최후 폴백 (설정 UI에서는 2026-08-09 제거)
    // Float→Double 직접 변환은 347.2f가 347.20001…로 보이는 오차를 만들므로 문자열 경유로 변환
    fun rateDc(c: Context): Double = sp(c).getFloat("rate_dc", 347.2f).toString().toDouble()
    fun rateAc(c: Context): Double = sp(c).getFloat("rate_ac", 292.9f).toString().toDouble()

    // 배터리 총용량 (kWh) — 차량이 용량을 안 줄 때 % 계산 및 에너지 추정용
    fun capacityKwh(c: Context): Double = sp(c).getFloat("cap_kwh", 100.0f).toString().toDouble()
    fun setCapacityKwh(c: Context, v: Double) =
        sp(c).edit().putFloat("cap_kwh", v.toFloat()).apply()

    // 누적 주행거리: 앱이 GPS로 쌓은 km + 시작 보정값
    fun lifetimeKm(c: Context): Double = sp(c).getFloat("lifetime_km", 0f).toDouble()
    fun addLifetimeKm(c: Context, delta: Double) =
        sp(c).edit().putFloat("lifetime_km", (lifetimeKm(c) + delta).toFloat()).apply()
    fun odoOffsetKm(c: Context): Double = sp(c).getFloat("odo_offset_km", 0f).toDouble()
    fun setOdoOffsetKm(c: Context, v: Double) =
        sp(c).edit().putFloat("odo_offset_km", v.toFloat()).apply()
    /** 표시용 총 누적주행 = 보정값 + 앱 누적(바퀴 틱 기반) */
    fun totalKm(c: Context): Double = odoOffsetKm(c) + lifetimeKm(c)
    /** 누적주행이 설정됐는지 (계기판 값을 넣었거나 앱이 쌓은 거리가 있음). 없으면 주행 탭에서 "누적" 칸을 숨긴다 (2026-09-13) */
    fun hasTotalKm(c: Context): Boolean = odoOffsetKm(c) > 0.0 || lifetimeKm(c) > 0.5

    // 마지막 주차 사진 촬영이 실패했는지.
    // 실패했을 때만 앱 실행 시 서비스를 포그라운드로 다시 띄운다 (평소엔 서비스를 건드리지 않는다)
    fun photoFailed(c: Context): Boolean = sp(c).getBoolean("photo_failed", false)
    fun setPhotoFailed(c: Context, v: Boolean) =
        sp(c).edit().putBoolean("photo_failed", v).apply()

    // 마지막으로 기록한 차량 속성 조사 결과 (같으면 로그에 다시 안 남긴다)
    fun lastProbe(c: Context): String = sp(c).getString("last_probe", "") ?: ""
    fun setLastProbe(c: Context, v: String) = sp(c).edit().putString("last_probe", v).apply()

    // Supabase 동기화 설정
    // 기본값 내장: 개인용 APK 전제 (이 APK를 남에게 줄 때는 두 값을 비우고 다시 빌드할 것)
    private const val DEFAULT_SB_URL = ""
    private const val DEFAULT_SB_KEY = ""
    fun supabaseUrl(c: Context): String =
        (sp(c).getString("sb_url", "") ?: "").ifBlank { DEFAULT_SB_URL }
    fun supabaseKey(c: Context): String =
        (sp(c).getString("sb_key", "") ?: "").ifBlank { DEFAULT_SB_KEY }
    fun deviceId(c: Context): String {
        val cur = sp(c).getString("device_id", "") ?: ""
        if (cur.isNotEmpty()) return cur
        val gen = "p4-" + (100000 + (Math.random() * 900000).toInt())
        sp(c).edit().putString("device_id", gen).apply()
        return gen
    }
    fun setSupabase(c: Context, url: String, key: String, deviceId: String) =
        sp(c).edit().putString("sb_url", url.trim().trimEnd('/'))
            .putString("sb_key", key.trim())
            .putString("device_id", deviceId.trim()).apply()

    // 지도·충전소 API 키도 설정 화면에서 입력할 수 있게 (2026-09-15, 공개 릴리즈 APK용 — 소스에 키를 안 넣고 빌드해도 쓸 수 있도록).
    // 기본값은 개인 빌드에만 들어 있다. 공개 저장소·릴리즈 APK에서는 빈 문자열
    private const val DEFAULT_VWORLD_KEY = ""
    private const val DEFAULT_DATA_GO_KEY = ""
    fun vworldKey(c: Context): String = (sp(c).getString("vworld_key", "") ?: "").ifBlank { DEFAULT_VWORLD_KEY }
    fun setVworldKey(c: Context, v: String) = sp(c).edit().putString("vworld_key", v.trim()).apply()
    /** data.go.kr 일반 인증키 — URL 인코딩된(Encoding) 값 그대로. 재인코딩 금지 */
    fun dataGoKey(c: Context): String = (sp(c).getString("datago_key", "") ?: "").ifBlank { DEFAULT_DATA_GO_KEY }
    fun setDataGoKey(c: Context, v: String) = sp(c).edit().putString("datago_key", v.trim()).apply()
    /** 지도 WebView가 열 주소: map.html이 ?k= 로 VWorld 키를 받는다 */
    fun mapUrl(c: Context): String = "file:///android_asset/map.html?k=" + java.net.URLEncoder.encode(vworldKey(c), "UTF-8")

    fun lastSyncResult(c: Context): String = sp(c).getString("last_sync", "아직 동기화 안 함") ?: ""
    fun setLastSyncResult(c: Context, msg: String) =
        sp(c).edit().putString("last_sync", msg).apply()

    fun lastSyncTs(c: Context): Long = sp(c).getLong("last_sync_ts", 0L)
    fun setLastSyncTs(c: Context, ts: Long) = sp(c).edit().putLong("last_sync_ts", ts).apply()

    // 주행 중 표시 실험 (2026-09-05): 헤드업 알림 + 미디어 '지금 재생' 카드. 기본 꺼짐
    fun driveDisplay(c: Context): Boolean = sp(c).getBoolean("drive_display", false)
    // 주행 중 전체 화면 오버레이 (2026-09-05): 제품 기능이라 기본 켜짐. 진단 화면에서 끌 수 있다
    fun driveOverlay(c: Context): Boolean = sp(c).getBoolean("drive_overlay", true)
    fun setDriveOverlay(c: Context, v: Boolean) = sp(c).edit().putBoolean("drive_overlay", v).apply()
    fun setDriveDisplay(c: Context, v: Boolean) = sp(c).edit().putBoolean("drive_display", v).apply()

    // 주차 사진: 마지막으로 서버에 올린 촬영 시각 (같은 사진 중복 업로드 방지)
    // 새 기록 알림을 이미 띄운 주행 id (2026-09-15) — 같은 주행으로 두 번 띄우지 않는다
    fun lastAchievedTripId(c: Context): Long = sp(c).getLong("achieved_trip", 0L)
    fun setLastAchievedTripId(c: Context, id: Long) = sp(c).edit().putLong("achieved_trip", id).apply()

    // 폰에서 고친 충전 기록을 어디까지 받아왔는지 (서버 charge.edited_ts, 2026-09-21 양방향 동기화)
    // 충전 위치(st_lat/st_lon) 소급 업로드를 했는지 (2026-09-22, SQL v7 뒤 한 번)
    fun chargeLocBackfillDone(c: Context): Boolean = sp(c).getBoolean("charge_loc_backfill", false)
    fun setChargeLocBackfillDone(c: Context) = sp(c).edit().putBoolean("charge_loc_backfill", true).apply()

    // 기간 탭 마지막 선택 (2026-09-25, 사용자: "항상 월간으로 떠서 불편") — 충전 탭·주행 기록 화면
    fun chargesPeriod(c: Context, dflt: Int): Int = sp(c).getInt("charges_period", dflt)
    fun setChargesPeriod(c: Context, p: Int) = sp(c).edit().putInt("charges_period", p).apply()
    fun tripsPeriod(c: Context, dflt: Int): Int = sp(c).getInt("trips_period", dflt)
    fun setTripsPeriod(c: Context, p: Int) = sp(c).edit().putInt("trips_period", p).apply()

    fun lastChargePullTs(c: Context): Long = sp(c).getLong("charge_pull_ts", 0L)
    fun setLastChargePullTs(c: Context, ts: Long) = sp(c).edit().putLong("charge_pull_ts", ts).apply()

    fun lastPhotoUploadTs(c: Context): Long = sp(c).getLong("last_photo_up_ts", 0L)
    fun setLastPhotoUploadTs(c: Context, ts: Long) =
        sp(c).edit().putLong("last_photo_up_ts", ts).apply()
}
