package com.p4log.car

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 로거 서비스 부활 경로 (2026-08-19 전면 수정 — 저녁 주행 누락의 실제 원인 2가지를 고침):
 *
 * 원인 ① 하트비트 알람이 서비스를 못 살렸다.
 *   targetSdk 34라 Android 12+ 규칙이 적용된다. 백그라운드에서 startForegroundService()는
 *   ForegroundServiceStartNotAllowedException으로 차단되고, 예외 목록에 "일반 알람 브로드캐스트"는 없다.
 *   → setExactAndAllowWhileIdle / setAndAllowWhileIdle 로 바꾼다. 이 알람들은 발화 시
 *     앱을 임시 허용목록에 올려주므로 그 안에서는 포그라운드 서비스 시작이 허용된다.
 *
 * 원인 ② 알람이 애초에 안 울렸다.
 *   ELAPSED_REALTIME(=non-wakeup) + setInexactRepeating 이라 차가 자고 있으면 발화하지 않고,
 *   절전(Doze) 중에는 유지보수 창까지 밀린다.
 *   → ELAPSED_REALTIME_WAKEUP + AllowWhileIdle 원샷으로 바꾸고 발화할 때마다 다시 예약한다.
 *     주차 중 밀린 알람은 차가 깨어나는 즉시 발화하므로 시동 직후 서비스가 되살아난다.
 *
 * 경로: 1) 부팅 완료  2) 앱 업데이트(MY_PACKAGE_REPLACED)  3) 하트비트 알람(죽었으면 5분/살았으면 15분)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_HEARTBEAT = "com.p4log.car.HEARTBEAT"
        private const val TAG = "P4Log.Boot"

        /**
         * 하트비트 간격.
         * - fast(5분): 서비스가 죽어 있는 상태 — 빨리 되살려야 하므로 촘촘히
         * - normal(15분): 서비스가 살아 있는 상태 — 주차 중 차를 자주 깨우지 않게 성기게.
         *   주차가 길면 어차피 알람이 밀려 있다가 시동을 켜는 순간 즉시 발화한다
         */
        private const val INTERVAL_FAST_MS = 5 * 60_000L
        private const val INTERVAL_NORMAL_MS = 15 * 60_000L

        /** 알람 방식(정확/부정확)을 한 번만 기록하기 위한 플래그 */
        @Volatile private var modeLogged = false

        /**
         * 다음 하트비트 1회를 예약한다. 반복 알람이 아니므로 **발화할 때마다 반드시 다시 불러야 한다.**
         * AllowWhileIdle 계열이라 절전 중에도 발화하고, 발화 시 임시 허용목록을 받아
         * 포그라운드 서비스 시작이 가능해진다.
         */
        fun scheduleHeartbeat(context: Context, fast: Boolean = false) {
            try {
                val am = context.getSystemService(AlarmManager::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, BootReceiver::class.java).setAction(ACTION_HEARTBEAT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val at = SystemClock.elapsedRealtime() +
                    (if (fast) INTERVAL_FAST_MS else INTERVAL_NORMAL_MS)
                val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    // 정확 알람 권한이 없어도 AllowWhileIdle 자체는 임시 허용목록을 준다 (조금 늦게 발화)
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
                if (!modeLogged) {
                    modeLogged = true
                    ServiceLog.add(context, "하트비트 알람 예약 (" + (if (exact) "정확" else "부정확") + ")")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "heartbeat schedule failed", e)
                ServiceLog.add(context, "알람 예약 실패: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val known = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_HEARTBEAT
        if (!known) return

        // 어떤 경로로 깨어났든 다음 하트비트를 먼저 다시 건다 (체인이 끊기면 부활 경로가 사라진다)
        val alive = LoggerService.running
        scheduleHeartbeat(context, fast = !alive)
        if (alive) return // 이미 살아 있으면 조용히

        val why = when (action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.LOCKED_BOOT_COMPLETED" -> "부팅 완료"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "앱 업데이트"
            else -> "하트비트"
        }
        Log.i(TAG, "$action → starting service")
        ServiceLog.add(context, "$why → 서비스 재시작 시도")
        LoggerService.start(context)
    }
}
