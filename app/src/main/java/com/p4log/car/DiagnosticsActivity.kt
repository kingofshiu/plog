package com.p4log.car

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/** 차량 데이터 진단: 어떤 속성이 되고 안 되는지 한눈에 */
class DiagnosticsActivity : Activity() {

    private lateinit var tvBody: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        tvBody = findViewById(R.id.diag_body)
        findViewById<TextView>(R.id.diag_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.diag_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.diag_req_perm).setOnClickListener {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.CAMERA,
                    "android.car.permission.CAR_ENERGY",
                    "android.car.permission.CAR_ENERGY_PORTS",
                    "android.car.permission.CAR_SPEED",
                    "android.car.permission.CAR_POWERTRAIN"
                ), 200
            )
        }
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun permLine(perm: String, label: String): String {
        val ok = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        return (if (ok) "✅" else "❌") + "  $label"
    }

    private fun refresh() {
        val sb = StringBuilder()

        sb.append("── 권한 상태 ──\n")
        sb.append(permLine(android.Manifest.permission.ACCESS_FINE_LOCATION, "위치 (GPS)")).append("\n")
        sb.append(permLine("android.car.permission.CAR_ENERGY", "차량 에너지 (배터리/충전)")).append("\n")
        sb.append(permLine("android.car.permission.CAR_ENERGY_PORTS", "충전 포트")).append("\n")
        sb.append(permLine("android.car.permission.CAR_SPEED", "차량 속도")).append("\n")
        sb.append(permLine("android.car.permission.CAR_POWERTRAIN", "시동 상태")).append("\n")
        sb.append(permLine("android.car.permission.CAR_MILEAGE", "적산거리 (시스템 전용 — ❌가 정상)")).append("\n")

        sb.append("\n── 차량 데이터 ──\n")
        val reader = CarDataReader(this)
        if (!reader.connect()) {
            sb.append("차량 연결 실패: ").append(reader.connectionError ?: "알 수 없음").append("\n")
        } else {
            reader.probeAll()
            val labels = mapOf(
                "EV_BATTERY_LEVEL" to "배터리 잔량(Wh)",
                "INFO_EV_BATTERY_CAPACITY" to "배터리 용량(Wh)",
                "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to "순간 전력(mW)",
                "RANGE_REMAINING" to "주행가능거리(m)",
                "PERF_VEHICLE_SPEED" to "속도(m/s)",
                "IGNITION_STATE" to "시동 상태",
                "EV_CHARGE_PORT_CONNECTED" to "충전 포트 연결",
                "PERF_ODOMETER" to "적산거리 (실패가 정상)"
            )
            for ((key, label) in labels) {
                sb.append("· ").append(label).append(": ")
                    .append(reader.diag[key] ?: "안 읽음").append("\n")
            }
            reader.disconnect()
        }

        sb.append("\n── 카메라 (주차 사진용 조사) ──\n")
        sb.append(permLine(android.Manifest.permission.CAMERA, "카메라 권한")).append("\n")
        try {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val ids = cm.cameraIdList
            if (ids.isEmpty()) {
                sb.append("· 감지된 카메라 없음 — 차량이 서드파티 앱에 카메라를 노출하지 않음\n")
            } else {
                for (id in ids) {
                    val facing = try {
                        when (cm.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                            android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "전방(실내측)"
                            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "후방"
                            android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL -> "외장"
                            else -> "방향 불명"
                        }
                    } catch (e: Throwable) { "정보 조회 실패" }
                    sb.append("· 카메라 [").append(id).append("]: ").append(facing).append(" ✅\n")
                }
                sb.append("→ 카메라가 잡혔습니다! 이 화면을 캡처해서 Claude에게 보여주면\n")
                sb.append("   주차 사진 촬영·업로드 기능을 만들 수 있습니다.\n")
            }
        } catch (e: Throwable) {
            sb.append("· 카메라 조회 실패: ").append(e.javaClass.simpleName)
                .append(" ").append(e.message ?: "").append("\n")
        }

        sb.append("\n── 서비스 ──\n")
        sb.append("로거 서비스: ").append(if (LoggerService.running) "실행 중 ✅" else "중지됨 ❌").append("\n")
        val s = LoggerService.lastSnapshot
        sb.append("GPS: ").append(if (s.gpsFix) "수신 중 ✅" else "신호 없음").append("\n")

        sb.append("\n※ '권한 거부'가 뜨면 아래 [권한 다시 요청]을 누르세요.\n")
        sb.append("※ 요청 후에도 거부되면 폴스타4가 해당 권한을 서드파티 앱에 안 열어준 것입니다.\n")
        sb.append("   그 경우에도 GPS 기반 주행 기록·주차 위치는 정상 동작합니다.")

        tvBody.text = sb.toString()
    }
}
