package com.p4log.car

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 서비스 생존 기록 (2026-08-19 추가).
 *
 * 실차에는 adb가 없어 logcat을 볼 수 없다. 저녁 주행이 왜 누락되는지 판정하려면
 * "서비스가 언제 죽었고, 되살리기를 시도했는지, 실패했다면 무슨 예외였는지"가 앱 안에 남아야 한다.
 * 진단 화면(설정 → 차량 데이터 진단)에서 확인한다.
 */
object ServiceLog {

    private const val FILE = "p4log_svclog"
    private const val KEY_LINES = "lines"
    private const val KEY_ALIVE = "last_alive"
    // 120줄이면 주행 한 번의 UX 변경 로그만으로 꽉 차서 전날 기록이 밀려났다 (2026-09-17 실차) → 400줄 (~35KB)
    private const val MAX_LINES = 400

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    /** 한 줄 기록 (오래된 줄은 밀려서 버려짐) */
    @Synchronized
    fun add(context: Context, event: String) {
        try {
            val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val prev = sp.getString(KEY_LINES, "") ?: ""
            val line = fmt.format(Date()) + "  " + event
            android.util.Log.i("P4Log.Svc", event)   // 에뮬레이터에선 logcat으로도 바로 본다
            val all = if (prev.isEmpty()) line else "$prev\n$line"
            val kept = all.split("\n").let { if (it.size > MAX_LINES) it.takeLast(MAX_LINES) else it }
            sp.edit().putString(KEY_LINES, kept.joinToString("\n")).apply()
        } catch (e: Throwable) {
            // 기록 실패가 본 기능을 막아선 안 된다
        }
    }

    fun lines(context: Context): List<String> {
        val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = sp.getString(KEY_LINES, "") ?: ""
        return if (s.isEmpty()) emptyList() else s.split("\n")
    }

    /** 서비스가 살아서 tick을 돌고 있다는 표시 (1분에 한 번만 기록) */
    fun markAlive(context: Context) {
        try {
            val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - sp.getLong(KEY_ALIVE, 0L) < 60_000L) return
            sp.edit().putLong(KEY_ALIVE, now).apply()
        } catch (e: Throwable) {
        }
    }

    /** 마지막 생존 확인 시각 (0이면 기록 없음) */
    fun lastAlive(context: Context): Long =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_ALIVE, 0L)

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_LINES).apply()
    }
}
