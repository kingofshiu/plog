package com.p4log.car

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 동반 기기(CompanionDeviceManager) 연결 — 주차 사진의 열쇠 (2026-09-03).
 *
 * 왜 필요한가 (Android 13 소스로 확인):
 * - ActiveServices.shouldAllowFgsWhileInUsePermissionLocked: 백그라운드 서비스가 카메라를 쓰려면
 *   앱 UID가 화면에 보이는 상태(TOP)여야 한다. 동반 기기·오버레이 예외는 **없다**.
 *   단, 한 번 허용되면 그 서비스 인스턴스가 죽을 때까지 유지된다.
 * - ActivityStarter(백그라운드 액티비티 실행, BAL): "동반 기기로 연결된 앱"은 예외로 허용된다.
 * → 주차 감지 시 투명 액티비티(PhotoActivity)를 띄워 순간적으로 TOP을 만들고, 그 상태에서
 *   서비스에 startService를 한 번 보내 카메라 허용을 붙인 뒤 촬영한다.
 * 연결은 사용자가 한 번만 확인하면 재부팅 후에도 유지된다.
 */
object CompanionLink {
    const val REQ_ASSOCIATE = 301
    private const val DISPLAY_NAME = "P.Log 주차 사진"

    private fun cdm(c: Context): CompanionDeviceManager? = try {
        c.getSystemService(CompanionDeviceManager::class.java)
    } catch (e: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun legacyCount(m: CompanionDeviceManager): Int = m.associations.size

    /** 연결 개수 (-1 = 이 기기에 동반 기기 기능 없음 / -2 = 조회 예외, lastError 참고) */
    @Volatile private var lastError = ""
    fun count(c: Context): Int {
        val m = cdm(c) ?: return -1
        return try {
            if (Build.VERSION.SDK_INT >= 33) m.myAssociations.size else legacyCount(m)
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + (e.message?.let { ":" + it.take(60) } ?: "")
            -2
        }
    }

    fun isLinked(c: Context): Boolean = count(c) > 0

    fun status(c: Context): String = when (val n = count(c)) {
        -1 -> "미지원"
        -2 -> "조회 오류(" + lastError + ")"
        0 -> "없음"
        else -> "연결됨($n)"
    }

    /**
     * 연결을 요청한다 — 블루투스 기기 목록에서 하나(예: 내 휴대폰)를 고르는 시스템 대화상자.
     * 결과는 activity.onActivityResult(REQ_ASSOCIATE) 로 온다.
     *
     * API 33의 '자체 관리(self-managed)' 연결은 쓰지 않는다: 필요한 권한
     * REQUEST_COMPANION_SELF_MANAGED 가 signature|privileged 라 사이드로드 앱은 받을 수 없다
     * (에뮬레이터 Android 13에서 SecurityException 으로 확인, 2026-09-03).
     * 블루투스 방식은 API 26부터 권한 없이 가능하고, 차에 이미 페어링된 휴대폰이 목록에 뜬다.
     */
    fun request(activity: Activity, onDone: () -> Unit, onError: (String) -> Unit) {
        val m = cdm(activity) ?: run { onError("이 차량에는 동반 기기 기능이 없습니다"); return }
        val cb = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) { launch(intentSender) }
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(intentSender: IntentSender) { launch(intentSender) }
            override fun onAssociationCreated(associationInfo: AssociationInfo) { onDone() }
            override fun onFailure(error: CharSequence?) {
                onError("연결 실패: " + (error ?: "알 수 없음"))
            }
            private fun launch(sender: IntentSender) {
                try {
                    activity.startIntentSenderForResult(sender, REQ_ASSOCIATE, null, 0, 0, 0)
                } catch (e: Throwable) {
                    onError("연결 대화상자 실행 실패: " + e.javaClass.simpleName)
                }
            }
        }
        try {
            m.associate(btRequest(), cb, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {
            onError("연결 요청 실패: " + e.javaClass.simpleName + " " + (e.message ?: ""))
        }
    }

    private fun btRequest(): AssociationRequest =
        AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
}
