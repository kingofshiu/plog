package com.p4log.car

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * 주차 사진용 투명 액티비티 (2026-09-03).
 *
 * 서비스가 백그라운드에서 이 화면을 띄운다(동반 기기 연결 시 허용). 화면에 뜨는 순간 앱 UID가
 * TOP이 되고, 그 상태에서 서비스에 startService를 보내면 서비스의 while-in-use 카메라 허용이
 * 켜진다(서비스가 살아 있는 동안 유지). 촬영이 끝나면 서비스가 dismiss()로 닫는다.
 * 사용자는 아무것도 누르지 않는다 — 화면 구석에 "촬영 중" 카드가 몇 초 보였다 사라질 뿐이다.
 */
class PhotoActivity : Activity() {

    companion object {
        const val EXTRA_HAS_LOC = "has_loc"
        /** 마지막으로 화면에 뜬 시각(elapsedRealtime). 0이면 아직 안 떴다 — 서비스가 BAL 차단을 판정하는 근거 */
        @Volatile var visibleSince = 0L
        @Volatile private var current: WeakReference<PhotoActivity>? = null

        fun dismiss() {
            val a = current?.get() ?: return
            a.runOnUiThread { if (!a.isFinishing) a.finish() }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        current = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        visibleSince = SystemClock.elapsedRealtime()
        if (requested) return
        requested = true
        val i = Intent(this, LoggerService::class.java)
            .setAction(LoggerService.ACTION_PHOTO_FOREGROUND)
            .putExtra(EXTRA_HAS_LOC, intent?.getBooleanExtra(EXTRA_HAS_LOC, true) ?: true)
        try {
            startForegroundService(i)
        } catch (e: Throwable) {
            ServiceLog.add(this, "사진 액티비티: 서비스 호출 실패 " + e.javaClass.simpleName)
            finish()
            return
        }
        // 안전망: 서비스가 닫아주지 않아도 25초 뒤엔 스스로 사라진다
        handler.postDelayed({ if (!isFinishing) finish() }, 25_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
