package com.p4log.car

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 주행 중 전체 화면 오버레이 (2026-09-05).
 *
 * 차단 서비스(CarPackageManagerService)는 화면 최상단 **액티비티**만 검사한다. 이 창은
 * TYPE_APPLICATION_OVERLAY 윈도우라 검사 대상이 아니다(에뮬레이터: 차단 화면 위에 그대로 남음).
 * 권한(SYSTEM_ALERT_WINDOW)은 targetSdk 22 앱에 설치 시 자동 부여된다(pre23).
 *
 * 내용은 **앱 전체와 동일**: activity_main(탭바 + 5개 페이지)을 그대로 띄우고, 주행/충전 상세·지도는
 * 창 안의 스택(FrameLayout)으로 연다. 탭바 끝에 "닫기" 셀만 추가된다.
 */
object DriveOverlay {
    private var root: FrameLayout? = null
    private var stack: FrameLayout? = null
    private var ui: MainUi? = null
    private var themed: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    fun canDraw(c: Context): Boolean = try { Settings.canDrawOverlays(c) } catch (e: Throwable) { false }
    fun isShown(): Boolean = root != null

    /** 오버레이용 호스트: 상세 화면을 창 안의 스택에 쌓는다 */
    private class OverlayHost(override val context: Context) : AppHost {
        override fun runOnUi(r: Runnable) { handler.post(r) }
        override fun openTrip(tripId: Long) = push(R.layout.activity_trip_detail) { v ->
            TripDetailScreen.bind(this, v, tripId)
        }
        override fun openTrips() = push(R.layout.activity_trips) { v -> TripsScreen.bind(this, v) }
        override fun openCharge(chargeId: Long) = push(R.layout.activity_charge_detail) { v ->
            ChargeDetailScreen.bind(this, v, chargeId)
        }
        override fun openMap(mode: String, title: String, lat: Double, lon: Double, polyline: String?) =
            push(R.layout.activity_map_web) { v -> MapScreen.bind(this, v, mode, title, lat, lon, polyline); true }
        override fun openDiagnostics() {
            // 진단은 액티비티뿐이라 주행 중엔 차량이 가릴 수 있다 — 정차 후 다시 열면 된다
            try {
                context.startActivity(Intent(context, DiagnosticsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Throwable) {}
        }
        override fun showDialog(b: AlertDialog.Builder) {
            try {
                val d = b.create()
                d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                d.show()
            } catch (e: Throwable) {
                ServiceLog.add(context, "오버레이 다이얼로그 실패: " + e.javaClass.simpleName)
            }
        }
        override fun showDialogDirect(d: AlertDialog) {
            try {
                d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                d.show()
            } catch (e: Throwable) {
                ServiceLog.add(context, "오버레이 다이얼로그 실패: " + e.javaClass.simpleName)
            }
        }
        override fun back() = pop()
    }

    private fun push(layout: Int, bind: (View) -> Boolean) {
        val st = stack ?: return
        val ctx = themed ?: return
        val v = LayoutInflater.from(ctx).inflate(layout, st, false)
        v.setBackgroundColor(Color.BLACK)
        v.isClickable = true          // 아래 페이지로 터치가 새지 않게
        if (!bind(v)) return
        st.addView(v)
        st.visibility = View.VISIBLE
    }

    private fun pop() {
        val st = stack ?: return
        if (st.childCount > 0) st.removeViewAt(st.childCount - 1)
        if (st.childCount == 0) st.visibility = View.GONE
    }

    fun show(c: Context): String {
        if (root != null) return "이미 표시 중"
        if (!canDraw(c)) return "오버레이 권한 없음"
        val app = c.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return "WindowManager 없음"
        val ctx = ContextThemeWrapper(app, R.style.Theme_P4Log)

        val fl = FrameLayout(ctx)
        fl.setBackgroundColor(Color.BLACK)
        val main = LayoutInflater.from(ctx).inflate(R.layout.activity_main, fl, false)
        fl.addView(main, FrameLayout.LayoutParams(-1, -1))
        val st = FrameLayout(ctx)
        st.visibility = View.GONE
        fl.addView(st, FrameLayout.LayoutParams(-1, -1))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 플래그 없음 = 포커스 가능(설정 입력칸에 키보드), 상태바·내비바를 피해 배치
            // (FLAG_LAYOUT_IN_SCREEN을 주면 페이지 상단 헤더가 상태바 밑에 잘린다 — 에뮬레이터 확인)
            0,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        return try {
            wm.addView(fl, lp)
            root = fl; stack = st; themed = ctx
            val host = OverlayHost(ctx)
            ui = MainUi(host, main, "닫기") { MainActivity.overlayDismissed = true; hide(app) }
            ui?.start()
            "표시됨"
        } catch (e: Throwable) {
            root = null; stack = null; themed = null
            "addView 실패: " + e.javaClass.simpleName + " " + (e.message ?: "")
        }
    }

    fun hide(c: Context) {
        val v = root ?: return
        ui?.stop()
        try { c.applicationContext.getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Throwable) {}
        root = null; stack = null; ui = null; themed = null
    }

    /** 서비스 tick(2초)마다 호출 — 페이지 갱신은 MainUi의 1초 티커가 하므로 여기선 할 일이 없다 */
    fun update() {}
}
