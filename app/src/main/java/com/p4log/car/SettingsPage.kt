package com.p4log.car

import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** 설정 탭 */
class SettingsPage(private val host: AppHost, root: View) : PageController {
    private val activity get() = host.context

    private val etCapacity: EditText = root.findViewById(R.id.set_capacity)
    private val etOdo: EditText = root.findViewById(R.id.set_odo)
    private val etVworld: EditText = root.findViewById(R.id.set_vworld)
    private val etDataGo: EditText = root.findViewById(R.id.set_datago)
    private val etSbUrl: EditText = root.findViewById(R.id.set_sb_url)
    private val etSbKey: EditText = root.findViewById(R.id.set_sb_key)
    private val tvKeyToggle: TextView = root.findViewById(R.id.set_sb_key_toggle)
    private val etDeviceId: EditText = root.findViewById(R.id.set_device_id)
    private val tvSyncState: TextView = root.findViewById(R.id.set_sync_state)
    private var keyShown = false

    init {
        root.findViewById<Button>(R.id.set_save).setOnClickListener { save() }
        root.findViewById<Button>(R.id.set_test).setOnClickListener { testConnection() }
        root.findViewById<Button>(R.id.set_sync_now).setOnClickListener { syncNow() }
        root.findViewById<Button>(R.id.set_diag).setOnClickListener { host.openDiagnostics() }
        // 키는 평소에 가려둔다 (2026-09-13: 화면 캡처·동승자에게 그대로 보이던 문제). [보기]로 잠깐 펼침
        tvKeyToggle.setOnClickListener {
            keyShown = !keyShown
            etSbKey.transformationMethod =
                if (keyShown) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
            etSbKey.setSelection(etSbKey.text.length)
            tvKeyToggle.text = if (keyShown) "가리기" else "보기"
        }
    }

    override fun onShow() {
        etCapacity.setText(Prefs.capacityKwh(activity).toString())
        etOdo.setText(Math.round(Prefs.totalKm(activity)).toString())
        etVworld.setText(Prefs.vworldKey(activity))
        etDataGo.setText(Prefs.dataGoKey(activity))
        etSbUrl.setText(Prefs.supabaseUrl(activity))
        etSbKey.setText(Prefs.supabaseKey(activity))
        etDeviceId.setText(Prefs.deviceId(activity))
        tvSyncState.text = "마지막 동기화: " + Prefs.lastSyncResult(activity)
    }

    private fun save() {
        val cap = etCapacity.text.toString().toDoubleOrNull()
        if (cap != null && cap > 10) Prefs.setCapacityKwh(activity, cap)

        // 누적주행 보정: 입력한 총 주행거리 = 보정값 + GPS누적 이 되도록 보정값 조정
        val odo = etOdo.text.toString().toDoubleOrNull()
        if (odo != null && odo >= 0) {
            Prefs.setOdoOffsetKm(activity, odo - Prefs.lifetimeKm(activity))
        }

        Prefs.setVworldKey(activity, etVworld.text.toString())
        Prefs.setDataGoKey(activity, etDataGo.text.toString())
        EvStations.init(activity)

        Prefs.setSupabase(
            activity,
            etSbUrl.text.toString(),
            etSbKey.text.toString(),
            etDeviceId.text.toString().ifEmpty { Prefs.deviceId(activity) }
        )
        Toast.makeText(activity, "저장했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        save()
        val url = Prefs.supabaseUrl(activity)
        val key = Prefs.supabaseKey(activity)
        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(activity, "URL과 키를 먼저 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        tvSyncState.text = "연결 확인 중..."
        Thread {
            val msg = SyncManager.testConnection(url, key)
            host.runOnUi { tvSyncState.text = "연결 테스트: $msg" }
        }.start()
    }

    private fun syncNow() {
        save()
        tvSyncState.text = "업로드 중..."
        SyncManager.uploadAsync(activity.applicationContext) { result ->
            host.runOnUi { tvSyncState.text = "마지막 동기화: $result" }
        }
    }
}
