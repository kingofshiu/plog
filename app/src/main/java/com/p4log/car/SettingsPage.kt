package com.p4log.car

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** 설정 탭 */
class SettingsPage(private val activity: Activity, root: View) : PageController {

    private val etCapacity: EditText = root.findViewById(R.id.set_capacity)
    private val etOdo: EditText = root.findViewById(R.id.set_odo)
    private val etSbUrl: EditText = root.findViewById(R.id.set_sb_url)
    private val etSbKey: EditText = root.findViewById(R.id.set_sb_key)
    private val etDeviceId: EditText = root.findViewById(R.id.set_device_id)
    private val tvSyncState: TextView = root.findViewById(R.id.set_sync_state)

    init {
        root.findViewById<Button>(R.id.set_save).setOnClickListener { save() }
        root.findViewById<Button>(R.id.set_test).setOnClickListener { testConnection() }
        root.findViewById<Button>(R.id.set_sync_now).setOnClickListener { syncNow() }
        root.findViewById<Button>(R.id.set_diag).setOnClickListener {
            activity.startActivity(Intent(activity, DiagnosticsActivity::class.java))
        }
    }

    override fun onShow() {
        etCapacity.setText(Prefs.capacityKwh(activity).toString())
        etOdo.setText(Math.round(Prefs.totalKm(activity)).toString())
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
            activity.runOnUiThread { tvSyncState.text = "연결 테스트: $msg" }
        }.start()
    }

    private fun syncNow() {
        save()
        tvSyncState.text = "업로드 중..."
        SyncManager.uploadAsync(activity.applicationContext) { result ->
            activity.runOnUiThread { tvSyncState.text = "마지막 동기화: $result" }
        }
    }
}
