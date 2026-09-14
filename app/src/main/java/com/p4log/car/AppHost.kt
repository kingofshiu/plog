package com.p4log.car

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent

/**
 * 화면 코드가 Activity에 묶이지 않도록 하는 호스트 (2026-09-05).
 *
 * 같은 페이지·상세 코드를 두 곳에서 쓴다:
 *  - MainActivity (평소)           → ActivityHost: 상세는 Activity로 연다
 *  - DriveOverlay (주행 중 오버레이) → OverlayHost: 상세를 오버레이 창 안의 스택으로 연다
 * 주행 중엔 우리 액티비티가 전부 가려지므로, 오버레이 안에서 "똑같은 화면"을 보이려면 이 분리가 필요하다.
 */
interface AppHost {
    /** 테마가 적용된 컨텍스트 — inflate, Db, Prefs, Toast, 다이얼로그에 쓴다 */
    val context: Context
    fun runOnUi(r: Runnable)
    fun openTrip(tripId: Long)
    /** 전체 주행 기록 (월별 목록, 2026-09-06) */
    fun openTrips()
    fun openCharge(chargeId: Long)
    fun openMap(mode: String, title: String, lat: Double = 0.0, lon: Double = 0.0, polyline: String? = null)
    fun openDiagnostics()
    /** 다이얼로그 표시 — 오버레이에선 창 타입을 바꿔야 해서 호스트가 띄운다 */
    fun showDialog(b: AlertDialog.Builder)
    /** 이미 create()된 다이얼로그 표시 (목록형 등). 오버레이는 창 타입을 바꾼다 (2026-09-08) */
    fun showDialogDirect(d: AlertDialog)
    /** 상세 화면에서 뒤로 */
    fun back()
}

class ActivityHost(private val activity: Activity) : AppHost {
    override val context: Context get() = activity
    override fun runOnUi(r: Runnable) = activity.runOnUiThread(r)
    override fun openTrip(tripId: Long) {
        activity.startActivity(Intent(activity, TripDetailActivity::class.java).putExtra("trip_id", tripId))
    }
    override fun openTrips() {
        activity.startActivity(Intent(activity, TripsActivity::class.java))
    }
    override fun openCharge(chargeId: Long) {
        activity.startActivity(Intent(activity, ChargeDetailActivity::class.java).putExtra("charge_id", chargeId))
    }
    override fun openMap(mode: String, title: String, lat: Double, lon: Double, polyline: String?) {
        val i = Intent(activity, MapWebActivity::class.java)
            .putExtra("mode", mode).putExtra("title", title)
            .putExtra("lat", lat).putExtra("lon", lon)
        if (polyline != null) i.putExtra("polyline", polyline)
        activity.startActivity(i)
    }
    override fun openDiagnostics() {
        activity.startActivity(Intent(activity, DiagnosticsActivity::class.java))
    }
    override fun showDialog(b: AlertDialog.Builder) { b.show() }
    override fun showDialogDirect(d: AlertDialog) { d.show() }
    override fun back() = activity.finish()
}
