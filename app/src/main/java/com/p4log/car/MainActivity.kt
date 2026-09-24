package com.p4log.car

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_BG_LOC = 101

        /** 절전 예외 안내는 앱 실행(프로세스)당 한 번만 — 허용할 때까지 켤 때마다 다시 묻는다 */
        @Volatile private var batteryPromptShown = false

        /**
         * 주행 오버레이 게이트 (2026-09-08, 실차 확인 후 사용자: "앱을 안 켜뒀는데도 움직이기만 하면 무조건 뜬다").
         * 오버레이는 **앱이 화면에 떠 있다가 주행 차단으로 가려진 경우에만** 띄운다.
         *  - visible: 지금 MainActivity가 화면에 있음
         *  - lastVisibleTs: 마지막으로 화면에서 내려간 시각 (차단 화면이 덮으면 onPause가 먼저 오고 UX 이벤트가 뒤따른다)
         *  - overlayDismissed: 오버레이의 [닫기]를 눌렀음 → 앱을 다시 열 때까지 안 띄움
         */
        @Volatile var visible = false
        @Volatile var lastVisibleTs = 0L
        @Volatile var overlayDismissed = false
    }

    private lateinit var ui: MainUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 레이아웃 점검용 데모 데이터 (에뮬레이터: am start ... --ez demo true). 디버그 빌드에서만 (2026-09-06)
        // MainUi가 첫 탭을 바로 띄우므로(onShow → 실시간 구독) 플래그는 MainUi 생성 전에 정해야 한다 (2026-09-13)
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        DashboardPage.demo = debuggable && intent.getBooleanExtra("demo", false)
        DashboardPage.demoCharge = DashboardPage.demo && intent.getBooleanExtra("demo_charge", false)
        DashboardPage.demoParked = DashboardPage.demo && intent.getBooleanExtra("demo_parked", false)
        // 요금표 자동 조회 시험 (디버그 빌드, 에뮬레이터): am start ... --es fee_test "충전소 이름" → 결과가 svclog/logcat에 남는다 (2026-09-21)
        intent.getStringExtra("fee_test")?.takeIf { debuggable }?.let { name ->
            FeeLookup.lookupAsync(this, name, 2) { r -> ServiceLog.add(this, "요금표 자동 조회 시험: " + (r.rate?.toString() ?: "없음") + " — " + r.note) }
        }
        EvStations.init(this)
        // 탭바+페이지는 MainUi (주행 중 오버레이와 같은 코드, 2026-09-05)
        ui = MainUi(ActivityHost(this), findViewById(android.R.id.content))

        requestNeededPermissions()
        requestBackgroundLocation()
        LoggerService.start(this)
        retryParkingPhotoIfPending()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        overlayDismissed = false
        ui.start()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        lastVisibleTs = System.currentTimeMillis()
        ui.stop()
    }

    // ---------- 권한 ----------

    private fun neededPermissions(): Array<String> {
        val perms = ArrayList<String>()
        perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        perms.add(android.Manifest.permission.CAMERA) // 주차 사진 (실차 전면 카메라 확인됨)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.POST_NOTIFICATIONS")
        }
        // 차량 위험 권한 — AAOS가 요청 다이얼로그를 띄워줌
        perms.add("android.car.permission.CAR_ENERGY")
        perms.add("android.car.permission.CAR_ENERGY_PORTS")
        perms.add("android.car.permission.CAR_SPEED")
        perms.add("android.car.permission.CAR_POWERTRAIN")
        return perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun requestNeededPermissions() {
        val need = neededPermissions()
        if (need.isNotEmpty()) requestPermissions(need, REQ_PERMS)
    }

    /**
     * 주차 사진 재시도 (2026-08-31).
     *
     * 주행 종료 시 촬영은 앱이 백그라운드라 Android의 while-in-use 제한에 막힌다
     * (실차 로그: 앱을 연 날 아침은 성공, 안 연 저녁은 `정책상 카메라 차단`).
     * 앱이 화면에 떠 있는 지금은 확실히 찍힌다 — 주차한 지 얼마 안 됐으면 여기서 만회한다.
     */
    private fun retryParkingPhotoIfPending() {
        if (!Prefs.photoFailed(this)) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return
        val db = Db.get(this)
        val p = db.parking() ?: return
        if (p.photoTs != null) return                       // 이미 사진이 있다
        val minsAgo = (System.currentTimeMillis() - p.ts) / 60_000L
        if (minsAgo > 30) return                            // 오래된 주차면 지금 찍어봐야 딴 장면이다
        ServiceLog.add(this, "주차 사진 재시도 (앱 실행, 주차 " + minsAgo + "분 전)")
        ParkingCamera.captureAsync(this) { file, err ->
            if (file != null) {
                db.setParkingPhotoTs(System.currentTimeMillis())
                Prefs.setPhotoFailed(this, false)
                ServiceLog.add(this, "주차 사진 재시도 성공 (" + (file.length() / 1024) + "KB)")
                SyncManager.uploadAsync(applicationContext) { }
            } else {
                ServiceLog.add(this, "주차 사진 재시도 실패: " + (err ?: "알 수 없음"))
            }
        }
    }

    /**
     * '위치 항상 허용'(ACCESS_BACKGROUND_LOCATION) 요청 (2026-08-30).
     *
     * 이게 없으면 앱이 화면에 떠 있을 때만 GPS가 열린다. 실차 로그에서 **앱을 연 날만
     * 주행이 기록되고**, 나머지 날은 28분을 달려도 `주행 폐기: 0m`로 버려졌다.
     * Android 11+는 前면 위치를 먼저 받은 뒤 **따로** 요청해야 하고, 시스템이 설정 화면을 띄운다.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val bg = checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") ==
            PackageManager.PERMISSION_GRANTED
        if (!fine || bg) return
        try {
            requestPermissions(arrayOf("android.permission.ACCESS_BACKGROUND_LOCATION"), REQ_BG_LOC)
        } catch (e: Throwable) {
            ServiceLog.add(this, "위치 항상 허용 요청 실패: " + e.javaClass.simpleName)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            // 앞 단계(위치 등)를 받은 다음에야 '항상 허용'을 물을 수 있다
            requestBackgroundLocation()
            LoggerService.start(this)
        } else if (requestCode == REQ_BG_LOC) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ServiceLog.add(this, "위치 항상 허용: " + (if (ok) "허용됨" else "거부됨"))
            LoggerService.start(this)
        }
    }
}
