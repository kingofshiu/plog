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
    /** 표시용 총 누적주행 = 보정값 + GPS 누적 */
    fun totalKm(c: Context): Double = odoOffsetKm(c) + lifetimeKm(c)

    // Supabase 동기화 설정
    // 기본값 내장: 개인용 APK 전제 (이 APK를 남에게 줄 때는 두 값을 비우고 다시 빌드할 것)
    private const val DEFAULT_SB_URL = "" // 본인 Supabase Project URL 입력 (선택 — 설정 화면에서도 입력 가능)
    private const val DEFAULT_SB_KEY = "" // 본인 anon/publishable 키 입력 (선택)
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

    fun lastSyncResult(c: Context): String = sp(c).getString("last_sync", "아직 동기화 안 함") ?: ""
    fun setLastSyncResult(c: Context, msg: String) =
        sp(c).edit().putString("last_sync", msg).apply()

    fun lastSyncTs(c: Context): Long = sp(c).getLong("last_sync_ts", 0L)
    fun setLastSyncTs(c: Context, ts: Long) = sp(c).edit().putLong("last_sync_ts", ts).apply()
}
