package com.p4log.car

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 차량 데이터 진단: 어떤 속성이 되고 안 되는지 한눈에 */
class DiagnosticsActivity : Activity() {

    private lateinit var tvBody: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        tvBody = findViewById(R.id.diag_body)
        findViewById<TextView>(R.id.diag_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.diag_refresh).setOnClickListener { refresh() }
        // 절전 예외: 앱 실행 때 자동으로 띄웠더니 폴스타4에 그 설정 화면이 없어서
        // "사용할 수 없는 애플리케이션입니다" 팝업만 매번 떴다 (2026-08-25 제거).
        // 이제 이 버튼을 누를 때만 시도하고, 안 되면 이유를 표시한다
        findViewById<Button>(R.id.diag_battery).setOnClickListener {
            try {
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).setData(android.net.Uri.parse("package:" + packageName))
                startActivity(i)
            } catch (e: Throwable) {
                Toast.makeText(this, "이 차량에는 절전 예외 설정 화면이 없습니다", Toast.LENGTH_LONG).show()
            }
        }
        // 주차 사진이 왜 안 올라오는지 즉시 확인하는 버튼 (2026-08-19).
        // 화면을 보고 있는 상태(=포그라운드)에서의 결과와, 주행 종료 시(=백그라운드) 결과를
        // 비교하면 원인이 "백그라운드 카메라 제한"인지 아닌지가 갈린다
        findViewById<Button>(R.id.diag_test_photo).setOnClickListener {
            Toast.makeText(this, "촬영 시도 중…", Toast.LENGTH_SHORT).show()
            ParkingCamera.captureAsync(this) { file, err ->
                // 찍기만 하고 끝내면 안 된다 — photo_ts를 기록해야 동기화가 사진을 올린다.
                // (2026-08-19 버그: 이 등록을 빠뜨려서 "촬영 성공"인데 폰 앱엔 안 떴다)
                val msg: String
                if (file != null) {
                    val ts = System.currentTimeMillis()
                    val rows = Db.get(this).setParkingPhotoTs(ts)
                    Prefs.setPhotoFailed(this, false)
                    msg = if (rows > 0) {
                        SyncManager.uploadAsync(applicationContext) { }
                        "촬영 성공 " + (file.length() / 1024) + "KB — 주차 사진으로 등록하고 업로드합니다"
                    } else {
                        "촬영 성공 " + (file.length() / 1024) + "KB — 다만 주차 기록이 없어 등록 못 함"
                    }
                } else {
                    msg = "촬영 실패: " + (err ?: "알 수 없음")
                }
                ServiceLog.add(this, "시험 촬영 — " + msg)
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
        // 동반 기기 연결 (2026-09-03) — 백그라운드에서 사진 액티비티를 띄울 수 있게 하는 열쇠.
        // 한 번 연결되면 재부팅 후에도 유지된다
        findViewById<Button>(R.id.diag_cdm).setOnClickListener {
            Toast.makeText(this, "연결 요청 중…", Toast.LENGTH_SHORT).show()
            CompanionLink.request(this,
                onDone = {
                    ServiceLog.add(this, "동반 기기 연결 완료: " + CompanionLink.status(this))
                    runOnUiThread {
                        Toast.makeText(this, "연결됐습니다 — 주차 시 앱을 안 열어도 촬영을 시도합니다",
                            Toast.LENGTH_LONG).show()
                        refresh()
                    }
                },
                onError = { err ->
                    ServiceLog.add(this, "동반 기기 연결 실패: " + err)
                    runOnUiThread {
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                })
        }
        // 실제 주차 때와 같은 흐름(백그라운드 → 액티비티 → 촬영)을 주행 없이 돌려 본다
        findViewById<Button>(R.id.diag_test_flow).setOnClickListener {
            val ok = LoggerService.requestTestPhotoFlow()
            Toast.makeText(this,
                if (ok) "10초 안에 홈으로 나가세요. 결과는 서비스 이력(새로고침)에 남습니다"
                else "로거 서비스가 실행 중이 아닙니다 — 앱을 다시 열어 주세요",
                Toast.LENGTH_LONG).show()
        }
        // 주행 오버레이 켜기/끄기 (2026-09-05). 켜면 즉시 띄워 보여 준다
        findViewById<Button>(R.id.diag_overlay).setOnClickListener {
            val on = !Prefs.driveOverlay(this)
            Prefs.setDriveOverlay(this, on)
            if (on) {
                val r = DriveOverlay.show(this)
                ServiceLog.add(this, "주행 오버레이 켬: " + r + " (권한 " + DriveOverlay.canDraw(this) + ")")
                Toast.makeText(this, "주행 오버레이 켬 — " + r, Toast.LENGTH_LONG).show()
            } else {
                DriveOverlay.hide(this)
                ServiceLog.add(this, "주행 오버레이 끔")
                Toast.makeText(this, "주행 오버레이 끔", Toast.LENGTH_SHORT).show()
            }
            refresh()
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CompanionLink.REQ_ASSOCIATE) {
            val ok = resultCode == RESULT_OK
            ServiceLog.add(this, "동반 기기 대화상자: " + (if (ok) "확인" else "취소") +
                " → " + CompanionLink.status(this))
            Toast.makeText(this, if (ok) "연결됐습니다" else "연결이 취소됐습니다", Toast.LENGTH_LONG).show()
            refresh()
        }
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
        sb.append(permLine("android.permission.ACCESS_BACKGROUND_LOCATION", "위치 항상 허용 (백그라운드)")).append("\n")
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
                "PERF_ODOMETER" to "적산거리 (시스템 전용 — 실패가 정상)",
                "PERF_VEHICLE_SPEED_DISPLAY" to "계기판 표시 속도",
                "WHEEL_TICK" to "바퀴 회전 누적 (되면 거리수측을 바퀴 기반으로 교체 가능)",
                "WHEEL_TICK_CONFIG" to "바퀴 1틱당 이동거리(μm)",
                "ENV_OUTSIDE_TEMPERATURE" to "외기 온도(°C)",
                "EV_CHARGE_TIME_REMAINING" to "충전 완료까지(초)",
                "EV_CHARGE_PERCENT_LIMIT" to "충전 목표 %",
                "TIRE_PRESSURE" to "타이어 공기압"
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

        sb.append("\n── 주차 사진 자동 촬영 ──\n")
        sb.append("동반 기기 연결: ").append(CompanionLink.status(this)).append("\n")
        sb.append("· 연결돼 있으면 주차 시 앱을 안 열어도 사진 화면을 잠깐 띄워 촬영합니다\n")
        sb.append("· [백그라운드 촬영 시험] 후 홈으로 나가면 10초 뒤 실제 흐름을 돌려 봅니다\n")

        sb.append("\n── 주행 중 표시 (오버레이) ──\n")
        sb.append("주행 오버레이: ").append(if (Prefs.driveOverlay(this)) "켜짐 ✅" else "꺼짐").append("\n")
        sb.append("오버레이 권한: ").append(if (DriveOverlay.canDraw(this)) "있음 ✅" else "없음 ❌").append("\n")
        sb.append("· 차량이 주행 중 제한을 거는 동안 우리 화면(오버레이)을 띄우고, 정차하면 내립니다\n")
        sb.append("· 닫기 버튼은 이번 주행에서만 내립니다. 완전히 끄려면 [주행 오버레이] 버튼\n")

        sb.append("\n── 서비스 ──\n")
        sb.append("로거 서비스: ").append(if (LoggerService.running) "실행 중 ✅" else "중지됨 ❌").append("\n")
        val s = LoggerService.lastSnapshot
        sb.append("GPS: ").append(if (s.gpsFix) "수신 중 ✅" else "신호 없음").append("\n")

        val alive = ServiceLog.lastAlive(this)
        sb.append("마지막 생존 확인: ")
        if (alive == 0L) {
            sb.append("기록 없음\n")
        } else {
            val mins = (System.currentTimeMillis() - alive) / 60_000L
            sb.append(SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(alive)))
                .append(" (").append(mins).append("분 전)\n")
        }

        // 저녁 주행 누락과 직결된 두 항목
        val pm = getSystemService(android.os.PowerManager::class.java)
        val exempt = try {
            pm != null && pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Throwable) { false }
        sb.append(if (exempt) "✅" else "❌")
            .append("  절전 예외 (서비스 부활에 필요)\n")
        val am = getSystemService(android.app.AlarmManager::class.java)
        val exact = try {
            android.os.Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms())
        } catch (e: Throwable) { false }
        sb.append(if (exact) "✅" else "⚠")
            .append("  정확 알람 (없어도 부정확 알람으로 동작)\n")

        sb.append("\n── 서비스 이력 (최근) ──\n")
        val svcLog = ServiceLog.lines(this)
        if (svcLog.isEmpty()) {
            sb.append("아직 기록 없음\n")
        } else {
            for (l in svcLog.takeLast(30)) sb.append(l).append("\n")
        }

        sb.append("\n※ '권한 거부'가 뜨면 아래 [권한 다시 요청]을 누르세요.\n")
        sb.append("※ 요청 후에도 거부되면 폴스타4가 해당 권한을 서드파티 앱에 안 열어준 것입니다.\n")
        sb.append("   그 경우에도 GPS 기반 주행 기록·주차 위치는 정상 동작합니다.")

        tvBody.text = sb.toString()
    }
}
