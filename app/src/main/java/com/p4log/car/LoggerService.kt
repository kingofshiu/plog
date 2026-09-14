package com.p4log.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.util.Calendar

/**
 * 핵심 로거: 2초마다 차량 데이터 + GPS를 읽어
 * 주행/충전을 자동 감지하고 DB에 기록하는 포그라운드 서비스.
 */
class LoggerService : Service(), LocationListener {

    companion object {
        private const val TAG = "P4Log.Svc"
        private const val CHANNEL_ID = "p4log_logger"
        private const val NOTI_ID = 1001
        private const val ALERT_CHANNEL_ID = "p4log_alert"
        private const val ALERT_NOTI_ID = 1002
        /** PhotoActivity가 화면에 뜬 상태에서 보내는 액션 — 이 startService가 카메라 허용을 켠다 */
        const val ACTION_PHOTO_FOREGROUND = "com.p4log.car.PHOTO_FOREGROUND"

        /**
         * 주차 사진 재시도는 **하지 않는다** (2026-09-01 실차 로그로 판정).
         * 화면이 켜져 있는 상태(`화면 on`)에서도 30초·3분 뒤 재시도가 전부 실패했다:
         *   07:22 시간 초과(워밍업 0프레임) / 18:18 정책상 카메라 차단 — 둘 다 3차까지 실패.
         * 즉 차단 요인은 디스플레이가 아니라 **앱이 포그라운드가 아닌 것**이고, 시간이 지난다고
         * 열리지 않는다. 무의미한 재시도는 로그만 더럽히므로 1회 시도로 끝낸다.
         */
        private val PHOTO_RETRY_DELAYS = LongArray(0)
        private const val TICK_MS = 2000L

        // 주행 감지 파라미터
        private const val MOVE_START_KMH = 4.0      // 이 속도 이상이면 출발
        private const val MOVE_STOP_KMH = 2.0       // 이 속도 미만이면 정지 후보
        private const val STOP_END_MS = 180_000L    // 3분 정지 시 주행 종료
        private const val MIN_TRIP_M = 300.0        // 300m 미만 주행은 버림
        private const val MAX_JUMP_MS_SPEED = 60.0  // GPS 점프 필터 (m/s)

        // 충전 감지 파라미터
        private const val CHARGE_START_KW = 0.3
        private const val CHARGE_STOP_KW = 0.15
        private const val CHARGE_END_QUIET_MS = 120_000L
        private const val MIN_CHARGE_KWH = 0.2
        private const val DC_THRESHOLD_KW = 11.5

        /** UI가 읽는 최신 스냅샷 */
        @Volatile var lastSnapshot: StatusSnapshot = StatusSnapshot()
            private set

        /** 주행 탭이 보일 때 차량 변경 알림을 구독 (2026-09-13). 서비스가 없으면 실패 사유 반환 */
        fun liveSubscribe(cb: (String, Float) -> Unit): String = instance?.carReader?.subscribeLive(cb) ?: "서비스 없음"
        fun liveUnsubscribe() { instance?.carReader?.unsubscribeLive() }

        /** 진행 중인 주행의 경로 JSON [[lat,lon,wh],…] — 주행 탭 라이브 지도용 (2026-09-15). 주행 중이 아니면 null. 메인 스레드 전용 */
        fun currentTripPolyline(): String? = instance?.let { if (it.tripActive) it.tripPoints.toString() else null }

        /** 최근 10분 순간 전력 (ts, kW; +충전/회생, −소비). 주행 탭 실시간 그래프용 (2026-09-05) */
        private val powerHistory = ArrayList<Pair<Long, Float>>()
        private const val POWER_HISTORY_MAX = 300      // 2초 × 300 = 10분
        fun powerHistorySnapshot(): List<Pair<Long, Float>> = synchronized(powerHistory) { ArrayList(powerHistory) }
        private fun pushPower(ts: Long, kw: Float) = synchronized(powerHistory) {
            powerHistory.add(ts to kw)
            while (powerHistory.size > POWER_HISTORY_MAX) powerHistory.removeAt(0)
        }
        /** 주행이 저장될 때마다 +1 — 주행 탭이 목록을 다시 읽는 신호 */
        @Volatile var tripSavedCount = 0
            private set
        /** 앱이 화면에서 내려간 뒤 이 시간 안에 주행 차단이 오면 "앱이 떠 있었다"로 본다 (오버레이 게이트) */
        private const val OVERLAY_GATE_MS = 6_000L
        @Volatile var running: Boolean = false
            private set
        @Volatile private var instance: LoggerService? = null

        /**
         * [백그라운드 촬영 시험]: 10초 뒤 실제 주차 사진 흐름을 돌린다.
         * 일부러 startService를 쓰지 않는다 — 포그라운드 화면에서 보낸 startService는 그 자체로
         * 서비스의 카메라 허용을 켜 버려, "앱을 안 연 상태"의 시험이 되지 않는다.
         * @return 서비스가 안 돌고 있으면 false
         */
        fun requestTestPhotoFlow(): Boolean {
            val svc = instance ?: return false
            svc.handler.post {
                ServiceLog.add(svc, "시험: 10초 뒤 백그라운드 사진 흐름 시작 (홈으로 나가 두세요)")
                svc.handler.postDelayed({
                    ServiceLog.add(svc, "시험: 사진 흐름 시작 [앱 " + svc.appInForeground() + "]")
                    svc.requestParkPhoto(true)
                }, 10_000L)
            }
            return true
        }

        fun start(context: Context) {
            val i = Intent(context, LoggerService::class.java)
            try {
                context.startForegroundService(i)
            } catch (e: Throwable) {
                // Android 12+ 백그라운드 포그라운드서비스 시작 제한에 걸리면 여기로 온다.
                // 실차에는 logcat이 없으므로 진단 화면에서 볼 수 있게 남긴다
                Log.w(TAG, "startForegroundService failed", e)
                ServiceLog.add(context, "서비스 시작 실패: " + e.javaClass.simpleName)
                notifyRestartNeeded(context)
            }
        }

        /**
         * 자동 부활 경로가 전부 막혔을 때의 마지막 안전망.
         * 조용히 기록을 놓치는 대신, 눌러서 앱을 열면 바로 되살아나는 알림을 띄운다.
         */
        private fun notifyRestartNeeded(context: Context) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL_ID, "기록 중단 알림", NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
                val pi = PendingIntent.getActivity(
                    context, 2, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                nm.notify(
                    ALERT_NOTI_ID,
                    Notification.Builder(context, ALERT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_bolt)
                        .setContentTitle("P.Log 기록이 멈췄습니다")
                        .setContentText("눌러서 앱을 열면 다시 기록을 시작합니다")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                )
            } catch (e: Throwable) {
                Log.w(TAG, "restart notice failed", e)
            }
        }
    }

    lateinit var carReader: CarDataReader   // 주행 탭 실시간 구독(liveSubscribe)이 쓴다
    private lateinit var db: Db
    private val handler = Handler(Looper.getMainLooper())

    // GPS 상태
    private var lastLoc: Location? = null
    private var lastLocElapsed: Long = 0L
    private var gpsFixLogged = false   // 서비스 시작 후 첫 수신을 한 번만 기록

    // 주행 상태
    private var lastBatteryWhForTrip: Float? = null // 폴리라인 지점별 전비 계산용 (tick마다 갱신)
    private var tripActive = false
    private var tripStartTs = 0L
    private var tripStartWh: Float? = null
    private var tripStartSoc: Float? = null
    private var tripDistanceM = 0.0
    private var tripMaxKmh = 0.0
    private var tripMovingMs = 0L   // 실제로 움직인 시간만 누적 (신호대기·정차 제외)
    private var tripRegenKwh = 0.0  // 회생제동 회수 에너지: 주행 중 +전력(충전 방향) 적분 (2026-09-05)
    // 회생 계산 자체 검증 (2026-09-15, 사용자: "회생 계산 확실한 거 맞아?"): 순간 전력을 부호 그대로 적분한 순 kWh.
    // 주행 끝에 배터리 Wh 델타와 나란히 로그로 남긴다 — 둘이 비슷하면 2초 샘플링 적분(회생도 같은 방식)이 믿을 만하다는 뜻
    private var tripNetIntegralKwh = 0.0
    // P단 주차 사진 (2026-09-15, 사용자: "P단에 두면 사진을 찍게"): 실차 로그상 주행 종료 시점엔 시동이 ACC(3)라
    // 카메라가 프레임을 안 준다(워밍업 0프레임). 시동 ON(4)에서 기어가 P로 바뀌는 순간 찍는다
    private var lastGear: Int? = null
    private var pPhotoTs = 0L               // 마지막 P단 촬영 시도 시각
    private var photoDuringTrip = false     // P단 촬영은 주행 상태에서 찍으므로 tripActive 가드를 이 플래그로 연다
    // 이번 주행 전체 소비/회생 전력 통계 (2026-09-06, 주행 탭 계기): 주행 시작 때만 리셋 → 끝나도 마지막 주행 값이 남는다
    private var tripConsumeSum = 0.0; private var tripConsumeCnt = 0; private var tripConsumePeak = 0f
    private var tripRegenSum = 0.0; private var tripRegenCnt = 0; private var tripRegenPeak = 0f
    // 바퀴 틱 기반 거리 (2026-08-30). GPS가 없어도 거리가 나오는 주 소스
    private var tripWheelM = 0.0
    private var lastWheelTicks: LongArray? = null
    private var lastWheelMono = 0L
    // 속도 적분 거리 — 바퀴도 GPS도 못 쓸 때의 마지막 안전망
    private var tripSpeedM = 0.0
    private var lastTickMono = 0L
    private var wheelUm: List<Int>? = null      // configArray [지원비트, FL, FR, RL, RR] μm/틱
    private var tripPoints = JSONArray()
    private var tripLastPoint: Location? = null
    private var movingSinceMs = 0L
    private var stoppedSinceMs = 0L

    // 충전 상태
    private var chargeActive = false
    private var chargeStartTs = 0L
    private var chargeStartWh: Float? = null
    private var chargeStartSoc: Float? = null
    private var chargeMaxKw = 0.0
    private var chargeProfile = JSONArray()
    private var chargeLastSampleTs = 0L
    private var chargeQuietSinceMs = 0L
    private var chargeIntegralKwh = 0.0
    private var chargeLastTickMs = 0L
    // 충전소 자동 식별 결과 (EvStations, 비동기로 채워짐)
    private var chargeStation: String? = null
    private var chargeOperator: String? = null
    private var chargeRateOverride: Double? = null
    private var chargeOutputs: String? = null   // 충전소의 충전기 정격 출력 목록 (kW, 쉼표 구분)
    private var chargeStLat: Double? = null
    private var chargeStLon: Double? = null

    // 하트비트 알람을 마지막으로 건 시각 (elapsedRealtime)
    private var lastHeartbeatArm = 0L
    private var lastChargeNotiTs = 0L   // 충전 알림 갱신 주기 제한용

    // 오늘 주행 캐시
    private var todayKmCache = 0.0
    private var todayEnergyCache = 0.0
    private var todayRegenCache = 0.0
    // 회생 사이클 캐시 (2026-09-08): 마지막 충전 이후 저장된 주행의 소비/회생 합 + 평소 비율
    private var cycleSinceTs: Long? = null
    private var cycleConsumeCache = 0.0
    private var cycleRegenCache = 0.0
    private var cycleKmCache = 0.0            // 충전 이후 달린 km (2026-09-15)
    private var cycleSocEnd: Float? = null    // 마지막 충전 종료 시 배터리 %
    private var usualRegenRatioPct = 0f
    private var todayCacheDay = -1

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Throwable) {
                Log.e(TAG, "tick error", e)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        carReader = CarDataReader(this)
        db = Db.get(this)
        createChannel()
        startAsForeground()
        carReader.connect()
        startGps()
        refreshTodayCache()
        handler.post(ticker)
        running = true
        instance = this
        ServiceLog.add(this, "서비스 시작")
        // 거리 측정을 GPS 대신 바퀴 기반으로 바꿀 수 있는지 조사 (2026-08-26).
        // 서비스 시작마다 1회만 — 결과는 동기화 때 svclog로 올라간다
        wheelUm = carReader.wheelTickMicrometers()
        val cams = ParkingCamera.listCameras(this)
            .joinToString(", ") { it.first + "=" + it.second }
        // 설치자 이름: 차량의 DO 허용 설치자 목록(allowedAppInstallSources)과 맞는지 보는 근거 (2026-09-03)
        val installer = try {
            val si = packageManager.getInstallSourceInfo(packageName)
            (si.installingPackageName ?: "null") + "/" + (si.initiatingPackageName ?: "null")
        } catch (e: Throwable) { "?" + e.javaClass.simpleName }
        val probe = carReader.probeSummary() + " | cams=[" + cams + "]" +
            " | cdm=" + CompanionLink.status(this) + " | installer=" + installer +
            " | build=" + android.os.Build.TYPE + "/sdk" + android.os.Build.VERSION.SDK_INT +
            " | overlay=" + overlayState() +
            " | display=" + resources.displayMetrics.widthPixels + "x" + resources.displayMetrics.heightPixels +
            "@" + resources.displayMetrics.densityDpi + "dpi"
        if (probe != Prefs.lastProbe(this)) {
            Prefs.setLastProbe(this, probe)
            ServiceLog.add(this, "속성조사 " + probe)
        }
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTI_ID)
        // UX 제한이 바뀔 때마다 기록 (2026-09-05): 폴스타가 D를 넣는 순간 DO를 요구하는지, 실제로 움직여야
        // 요구하는지를 실차에서 가른다. 볼트메터 영상은 기어 D 상태에서 화면이 떠 있었다
        val uxOk = carReader.registerUxListener { st ->
            handler.post {
                val snap = lastSnapshot
                ServiceLog.add(this, "UX 변경 [" + st + "] 기어 " + gearName(carReader.gear()) +
                    " / 속도 " + Math.round(snap.speedKmh?.toDouble() ?: 0.0) +
                    " / 시동 " + (snap.ignition?.toString() ?: "?"))
                applyDriveOverlay(st.contains("requiresDO=true"))
            }
        }
        if (!uxOk) ServiceLog.add(this, "UX 리스너 등록 실패")
        // 서비스가 주행 중에 (재)시작된 경우: 현재 상태로 즉시 적용
        handler.postDelayed({ applyDriveOverlay(carReader.uxRestrictionState().contains("requiresDO=true")) }, 3_000L)
        BootReceiver.scheduleHeartbeat(this) // 서비스가 죽어도 하트비트로 재시작
    }

    /**
     * 주행 중 표시 오버레이 (2026-09-05): 차량이 DO를 요구하는 동안만 띄운다.
     * 우리 액티비티는 그때 가려지지만, 이 창은 액티비티가 아니라 차단 검사 대상이 아니다 — 실차 판정 포인트.
     */
    private fun applyDriveOverlay(requiresDo: Boolean) {
        if (!Prefs.driveOverlay(this)) return
        if (requiresDo) {
            if (!DriveOverlay.isShown()) showDriveOverlayIfAppWasOnScreen()
        } else if (DriveOverlay.isShown()) {
            DriveOverlay.hide(this)
            ServiceLog.add(this, "주행 오버레이 내림 (정차)")
        }
    }

    /**
     * 오버레이 게이트 (2026-09-08, 실차에서 오버레이가 뜨는 것 확인 후 사용자 요청):
     * 앱을 켜두지 않았는데 차가 움직이기만 하면 무조건 떴다 → **앱이 화면에 있던 경우에만** 띄운다.
     * 차단 화면이 우리 액티비티를 덮으면 onPause가 먼저 오고 UX 이벤트가 몇 초 뒤에 오므로,
     * "지금 보임" 또는 "6초 안에 내려감"이면 앱이 떠 있던 것으로 본다. [닫기]를 눌렀으면 앱을 다시 열 때까지 안 띄운다.
     */
    private fun showDriveOverlayIfAppWasOnScreen() {
        val sinceHidden = System.currentTimeMillis() - MainActivity.lastVisibleTs
        val wasOnScreen = MainActivity.visible || sinceHidden < OVERLAY_GATE_MS
        if (MainActivity.overlayDismissed) {
            ServiceLog.add(this, "주행 오버레이 건너뜀 (닫기 누름 — 앱을 다시 열면 다시 뜸)")
            return
        }
        if (!wasOnScreen) {
            ServiceLog.add(this, "주행 오버레이 건너뜀 (앱이 화면에 없었음, 마지막 표시 " +
                (if (MainActivity.lastVisibleTs == 0L) "없음" else "" + (sinceHidden / 1000) + "초 전") + ")")
            return
        }
        ServiceLog.add(this, "주행 오버레이: " + DriveOverlay.show(this) +
            " (권한 " + DriveOverlay.canDraw(this) + ", 앱 " + (if (MainActivity.visible) "보임" else "" + (sinceHidden / 1000) + "초 전까지 보임") + ")")
    }

    private fun gearName(g: Int?): String = when (g) {
        null -> "?"; 1 -> "N"; 2 -> "R"; 4 -> "P"; 8 -> "D"; else -> g.toString()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PHOTO_FOREGROUND -> {
                // PhotoActivity가 화면에 뜬 채로 보낸 호출. 앱 UID가 TOP인 이 순간의 startService가
                // 서비스의 while-in-use 카메라 허용을 켠다 (Android 13 ActiveServices — 서비스가
                // 살아 있는 동안 유지). 그 뒤의 촬영은 액티비티가 닫혀도 된다
                val hasLoc = intent.getBooleanExtra(PhotoActivity.EXTRA_HAS_LOC, true)
                handler.removeCallbacks(photoFallback)
                ServiceLog.add(this, "사진 액티비티 표시됨 [앱 " + appInForeground() + "] → 촬영")
                setCameraFgsType(true)
                capturePark(0, hasLoc)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacks(ticker)
        stopGps()
        carReader.disconnect()
        DriveDisplay.release()
        DriveOverlay.hide(this)
        ServiceLog.add(this, "서비스 종료")
        // 죽는 순간 다음 하트비트를 확실히 걸어둔다 (이게 유일한 부활 경로다)
        BootReceiver.scheduleHeartbeat(this, fast = true)
        super.onDestroy()
    }

    // ---------- 알림 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "주행 기록", NotificationManager.IMPORTANCE_LOW)
        ch.description = "차량 데이터 기록 중 표시"
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("P.Log")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        try {
            startForeground(
                NOTI_ID, buildNotification("기록 대기 중"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Throwable) {
            // 위치 권한이 아직 없으면 location 타입 지정이 거부될 수 있음 → 일반 포그라운드로
            Log.w(TAG, "typed startForeground failed, fallback", e)
            startForeground(NOTI_ID, buildNotification("기록 대기 중 (권한 확인 필요)"))
        }
    }

    /**
     * 촬영 직전에만 포그라운드 서비스 타입에 camera를 얹는다 (2026-08-25).
     *
     * 왜: Android 11+ 는 포그라운드 서비스가 **camera 타입을 선언하고 있을 때만**
     * 백그라운드에서 카메라를 쓰게 해준다. 그래서 주행 종료 시 촬영이
     * `CAMERA_DISABLED (1) ... disabled by policy` 로 계속 거부됐다 (실차 로그로 확인).
     * 평소엔 location만 유지하고 촬영할 때만 올렸다 내린다.
     * CAMERA 권한이 없으면 아예 시도하지 않는다 — 권한 없이 camera 타입을 주면
     * startForeground가 SecurityException을 던져 주행 기록 자체가 죽는다.
     */
    private fun setCameraFgsType(on: Boolean) {
        try {
            val type = if (on)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTI_ID, buildNotification("기록 대기 중"), type)
        } catch (e: Throwable) {
            ServiceLog.add(this, "카메라 타입 전환 실패(" + (if (on) "on" else "off") + "): " +
                e.javaClass.simpleName)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_ID, buildNotification(text))
    }

    // ---------- GPS ----------

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startGps() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "no location permission yet")
            return
        }
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, TICK_MS, 3f, this)
        } catch (e: Throwable) {
            Log.w(TAG, "requestLocationUpdates failed", e)
        }
    }

    private fun stopGps() {
        try {
            val lm = getSystemService(LocationManager::class.java)
            lm.removeUpdates(this)
        } catch (e: Throwable) {
            Log.w(TAG, "removeUpdates failed", e)
        }
    }

    /** 권한을 나중에 받았을 때 MainActivity가 호출할 수 있도록 재시작 진입점 */
    fun restartGps() { stopGps(); startGps() }

    override fun onLocationChanged(location: Location) {
        val prev = lastLoc
        if (prev != null) {
            val dtSec = (location.time - prev.time) / 1000.0
            if (dtSec > 0) {
                val d = prev.distanceTo(location).toDouble()
                if (d / dtSec > MAX_JUMP_MS_SPEED) return // GPS 점프 무시
            }
        }
        lastLoc = location
        lastLocElapsed = SystemClock.elapsedRealtime()
        if (!gpsFixLogged) {
            gpsFixLogged = true
            // 주행이 통째로 안 잡힐 때, GPS가 아예 안 왔던 건지 구분하는 근거
            ServiceLog.add(this, "GPS 첫 수신")
        }

        if (tripActive) {
            val lp = tripLastPoint
            if (lp == null) {
                appendTripPoint(location)
            } else {
                val d = lp.distanceTo(location).toDouble()
                if (d >= 5.0) {
                    tripDistanceM += d
                    appendTripPoint(location)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun appendTripPoint(location: Location) {
        // 폴리라인 다운샘플: 마지막 저장점에서 15m 이상 이동 시에만 저장
        val lp = tripLastPoint
        if (lp == null || lp.distanceTo(location) >= 15f) {
            val pt = JSONArray()
            pt.put(Math.round(location.latitude * 100000.0) / 100000.0)
            pt.put(Math.round(location.longitude * 100000.0) / 100000.0)
            // 3번째 값: 배터리 잔량(Wh) — 지도에서 구간별 전비 색상 표시용 (없으면 2개짜리 점)
            val wh = lastBatteryWhForTrip
            if (wh != null) pt.put(Math.round(wh))
            tripPoints.put(pt)
            tripLastPoint = location
        }
    }

    // ---------- 메인 루프 ----------

    private fun tick() {
        val now = System.currentTimeMillis()
        val mono = SystemClock.elapsedRealtime()

        val batteryWh = carReader.batteryWh()
        val capacityWh = carReader.capacityWh()
            ?: (Prefs.capacityKwh(this) * 1000.0).toFloat()
        val rateKw = carReader.chargeRateKw()
        val rangeKm = carReader.rangeKm()
        val carSpeed = carReader.speedKmh()
        val ignition = carReader.ignitionState()
        val port = carReader.chargePortConnected()

        val socPct: Float? =
            if (batteryWh != null && capacityWh > 0f) (batteryWh / capacityWh * 100f) else null
        lastBatteryWhForTrip = batteryWh
        pushPower(now, rateKw ?: 0f)

        // ----- 바퀴 틱: 거리 + 속도의 주 소스 -----
        // PERF_ODOMETER는 시스템 권한이라 막혀 있지만 WHEEL_TICK은 CAR_SPEED로 읽힌다.
        // 실차 확인: 4바퀴 지원, 1틱 = 24000μm. 시동 사이클마다 0으로 리셋되므로 델타만 쓴다
        val ticks = carReader.wheelTicks()
        val wheelDeltaM = wheelDeltaMeters(ticks)
        val wheelDtSec = if (lastWheelMono > 0L) (mono - lastWheelMono) / 1000.0 else 0.0
        if (ticks != null) { lastWheelTicks = ticks; lastWheelMono = mono }
        if (tripActive) tripWheelM += wheelDeltaM
        // 바퀴 기반 속도 — GPS가 없고 차량 속도가 0으로 고정돼도 움직임을 잡아낸다
        val wheelKmh: Double =
            if (wheelDeltaM > 0.0 && wheelDtSec > 0.5 && wheelDtSec < 10.0)
                wheelDeltaM / wheelDtSec * 3.6
            else 0.0

        val gpsFresh = lastLoc != null && (mono - lastLocElapsed) < 15_000L
        val gpsSpeedKmh: Float? =
            if (gpsFresh && lastLoc!!.hasSpeed()) lastLoc!!.speed * 3.6f else null
        // 차량이 속도 속성을 "지원"하면서 값은 0으로 고정해 주는 경우가 있어
        // (에뮬레이터 확인, 실차 의심) null 폴백 대신 둘 중 큰 값을 쓴다
        // 세 소스 중 가장 큰 값. 차량 속도가 0 고정이거나 GPS가 막혀도 바퀴가 받쳐준다
        val speedKmh: Double =
            maxOf(maxOf(carSpeed ?: 0f, gpsSpeedKmh ?: 0f).toDouble(), wheelKmh)

        // ----- 주행 감지 -----
        if (!tripActive) {
            if (speedKmh >= MOVE_START_KMH) {
                if (movingSinceMs == 0L) movingSinceMs = mono
                if (mono - movingSinceMs >= TICK_MS) startTrip(now, batteryWh, socPct)
            } else {
                movingSinceMs = 0L
            }
        } else {
            if (speedKmh > tripMaxKmh) tripMaxKmh = speedKmh
            // 평균속도는 '움직인 시간'으로 낸다. 경과시간으로 나누면 신호대기와
            // 주행 종료 판정용 3분 정차가 통째로 들어가 말이 안 되는 값이 나온다
            if (speedKmh >= MOVE_STOP_KMH) tripMovingMs += TICK_MS
            // 속도 × 시간 적분. 차량 속도 속성만 살아 있으면 거리가 나온다
            val dtSec = if (lastTickMono > 0L) (mono - lastTickMono) / 1000.0 else 0.0
            // 250km/h 초과는 센서 튐으로 보고 적분에서 제외 (거리가 부풀지 않게)
            if (dtSec > 0.2 && dtSec < 10.0 && speedKmh < 250.0)
                tripSpeedM += speedKmh / 3.6 * dtSec
            // 회생: 주행 중 순간 전력이 +(배터리로 들어오는 방향)인 구간을 kWh로 적분
            if (rateKw != null && rateKw > 0.05f && dtSec > 0.2 && dtSec < 10.0)
                tripRegenKwh += rateKw * dtSec / 3600.0
            // 검증용 순 적분 (부호 그대로): 주행 끝에 Wh 델타와 비교
            if (rateKw != null && dtSec > 0.2 && dtSec < 10.0)
                tripNetIntegralKwh += rateKw * dtSec / 3600.0
            // P단 주차 사진: 시동 ON 상태에서 기어가 P로 바뀐 순간 (정지 중일 때만, 주행당 여러 번 가능 — 촬영 중이면 건너뜀)
            val gear = carReader.gear()
            if (gear == 4 && lastGear != null && lastGear != 4 && ignition == 4 && speedKmh < MOVE_STOP_KMH && !photoBusy) {
                parkPhotoAtP(now, socPct)
            }
            lastGear = gear
            // 이번 주행 소비/회생 전력 평균·최고 (샘플 단위)
            if (rateKw != null) {
                val c = (-rateKw).coerceAtLeast(0f)
                tripConsumeSum += c; tripConsumeCnt++; if (c > tripConsumePeak) tripConsumePeak = c
                val r = rateKw.coerceAtLeast(0f)
                if (r > 0.05f) { tripRegenSum += r; tripRegenCnt++; if (r > tripRegenPeak) tripRegenPeak = r }
            }
            val ignOff = ignition != null && ignition <= 2 // LOCK/OFF
            if (speedKmh < MOVE_STOP_KMH) {
                if (stoppedSinceMs == 0L) stoppedSinceMs = mono
                if (ignOff || (mono - stoppedSinceMs >= STOP_END_MS)) {
                    endTrip(now, batteryWh, socPct)
                }
            } else {
                stoppedSinceMs = 0L
            }
        }

        // ----- 충전 감지 -----
        // 회생제동도 순간 충전 전력(+kW)으로 잡히므로 전력만으로는 충전을 판정할 수 없다.
        // 정차 중 + 충전포트 연결일 때만 충전으로 인정 (포트 값을 못 주는 차량은 정차 조건만 적용)
        val stationary = !tripActive && speedKmh < MOVE_STOP_KMH
        val charging = rateKw != null && rateKw > CHARGE_START_KW &&
            stationary && port != false
        if (!chargeActive) {
            if (charging) startCharge(now, batteryWh, socPct)
        } else if (tripActive) {
            cancelCharge() // 주행이 시작됐다면 이 세션은 회생제동 오탐 → 저장 없이 폐기
        } else {
            val kwNow = (rateKw ?: 0f).toDouble()
            // 전력 적분 (Wh 데이터가 없을 때의 폴백 kWh)
            if (chargeLastTickMs > 0L && kwNow > 0) {
                chargeIntegralKwh += kwNow * ((mono - chargeLastTickMs) / 3_600_000.0)
            }
            if (kwNow > chargeMaxKw) chargeMaxKw = kwNow
            if (now - chargeLastSampleTs >= 60_000L) {
                val s = JSONArray()
                s.put((now - chargeStartTs) / 60_000L)
                s.put(Math.round(kwNow * 10.0) / 10.0)
                chargeProfile.put(s)
                chargeLastSampleTs = now
            }
            val quiet = kwNow < CHARGE_STOP_KW
            val portOff = port == false
            if (quiet) {
                if (chargeQuietSinceMs == 0L) chargeQuietSinceMs = mono
                if (portOff || (mono - chargeQuietSinceMs >= CHARGE_END_QUIET_MS)) {
                    endCharge(now, batteryWh, socPct)
                }
            } else {
                chargeQuietSinceMs = 0L
            }
        }
        chargeLastTickMs = mono
        lastTickMono = mono

        // ----- 스냅샷 발행 -----
        refreshTodayCacheIfDayChanged()
        val curTripKm = tripDistanceM / 1000.0
        // 이번 주행 진행 중 소비 kWh (배터리 Wh 델타, 저장 때와 같은 식)
        val liveTripEnergyKwh = run {
            val sWh = tripStartWh
            if (tripActive && sWh != null && batteryWh != null && sWh > batteryWh) (sWh - batteryWh) / 1000.0 else 0.0
        }
        val todayKm = todayKmCache + (if (tripActive) curTripKm else 0.0)
        val todayEff: Double? =
            if (todayEnergyCache > 0.3) (todayKmCache / todayEnergyCache) else null

        // 차량이 주면 쓰고 없으면 null (폴스타 비공식 API 필드 목록을 보고 추가한 항목들)
        val outTempC = carReader.outsideTempC()
        val chgRemainMin =
            if (chargeActive) carReader.chargeTimeRemainingSec()
                ?.let { if (it in 1..86_400) it / 60 else null }
            else null
        val chgLimitPct =
            if (chargeActive) carReader.chargePercentLimit()?.let { if (it > 0f) it else null }
            else null

        lastSnapshot = StatusSnapshot(
            ts = now,
            carConnected = carReader.isConnected,
            batteryWh = batteryWh,
            capacityWh = capacityWh,
            socPct = socPct,
            chargeRateKw = rateKw,
            rangeKm = rangeKm,
            // 화면에도 융합 속도(차량·GPS·바퀴 중 최대)를 준다 — 터널에서 GPS가 끊겨도 바퀴 속도로 표시 (2026-09-13)
            speedKmh = if (carSpeed == null && wheelKmh <= 0.0 && gpsSpeedKmh == null) null else speedKmh.toFloat(),
            gpsSpeedKmh = gpsSpeedKmh,
            ignition = ignition,
            portConnected = port,
            lat = if (gpsFresh) lastLoc!!.latitude else null,
            lon = if (gpsFresh) lastLoc!!.longitude else null,
            gpsFix = gpsFresh,
            tripActive = tripActive,
            tripKm = curTripKm,
            tripMinutes = if (tripActive) (now - tripStartTs) / 60_000L else 0L,
            chargeActive = chargeActive,
            chargeKwh = currentChargeKwh(batteryWh),
            todayKm = todayKm,
            todayEff = todayEff,
            tripRegenKwh = if (tripActive) tripRegenKwh else 0.0,
            todayRegenKwh = todayRegenCache,
            tripConsumeAvgKw = if (tripConsumeCnt > 0) (tripConsumeSum / tripConsumeCnt).toFloat() else 0f,
            tripConsumePeakKw = tripConsumePeak,
            tripRegenAvgKw = if (tripRegenCnt > 0) (tripRegenSum / tripRegenCnt).toFloat() else 0f,
            tripRegenPeakKw = tripRegenPeak,
            cycleSinceTs = cycleSinceTs,
            cycleConsumeKwh = cycleConsumeCache + (if (tripActive) liveTripEnergyKwh else 0.0),
            cycleRegenKwh = cycleRegenCache + (if (tripActive) tripRegenKwh else 0.0),
            tripEnergyKwh = if (tripActive) liveTripEnergyKwh else 0.0,
            cycleKm = cycleKmCache + (if (tripActive) curTripKm else 0.0),
            cycleSocEnd = cycleSocEnd,
            usualRegenRatioPct = usualRegenRatioPct,
            outsideTempC = outTempC,
            chargeRemainMin = chgRemainMin,
            chargeLimitPct = chgLimitPct
        )

        // ----- 생존 기록 + 하트비트 알람 유실 대비 -----
        ServiceLog.markAlive(this)
        if (mono - lastHeartbeatArm > 30 * 60_000L) {
            lastHeartbeatArm = mono
            BootReceiver.scheduleHeartbeat(this)
        }

        // 충전 중 알림에 남은 시간을 실어준다 (차량이 값을 줄 때만). 1분에 한 번만 갱신
        if (chargeActive && chgRemainMin != null && now - lastChargeNotiTs > 60_000L) {
            lastChargeNotiTs = now
            val txt = if (chgRemainMin >= 60)
                "충전 기록 중 · " + (chgRemainMin / 60) + "시간 " + (chgRemainMin % 60) + "분 남음"
            else "충전 기록 중 · " + chgRemainMin + "분 남음"
            updateNotification(txt + (chargeStation?.let { " · " + it } ?: ""))
        }

        // ----- 주행 중 표시 실험 (2026-09-05) -----
        DriveDisplay.update(this, lastSnapshot)
        DriveOverlay.update()
        if (tripActive && DriveDisplay.enabled(this) && now - lastDriveNotiTs > 10_000L) {
            lastDriveNotiTs = now
            val (t, a, _) = DriveDisplay.lines(lastSnapshot)
            updateNotification(t + " · " + a)
        }

        // ----- 유휴 시 자동 동기화 -----
        if (!tripActive && !chargeActive) maybeSync(now)
    }
    private var lastDriveNotiTs = 0L

    /**
     * 이번 tick의 바퀴 이동거리(m). 거리 누적과 속도 계산에 함께 쓴다.
     * 값은 [timestampNs, FL, FR, RL, RR]. 리셋(감소)되면 그 tick은 건너뛴다.
     * 네 바퀴 평균을 쓴다 — 한 바퀴가 헛돌아도 영향이 줄어든다.
     */
    private fun wheelDeltaMeters(now: LongArray?): Double {
        val prev = lastWheelTicks ?: return 0.0
        if (now == null || now.size < 5 || prev.size < 5) return 0.0
        val um = wheelUm
        var sumM = 0.0
        var n = 0
        for (i in 1..4) {
            val d = now[i] - prev[i]
            // d <= 0 은 시동 사이클 리셋. 상한은 읽기가 한동안 끊겼다 돌아온 경우를 위해 넉넉히
            if (d <= 0L || d > 2_000_000L) continue
            val perTick = um?.getOrNull(i)?.toDouble() ?: 24000.0
            sumM += d * perTick / 1_000_000.0
            n++
        }
        return if (n > 0) sumM / n else 0.0
    }

    /**
     * 기기 정책(DevicePolicyManager)이 카메라를 막고 있는지.
     * `CAMERA_DISABLED ... disabled by policy` 가 **차량이 건 정책** 때문인지,
     * 아니면 앱이 백그라운드라서인지(while-in-use)를 구분하는 결정적 근거다.
     */
    private fun cameraDisabledByPolicy(): String = try {
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (dpm == null) "?" else dpm.getCameraDisabled(null).toString()
    } catch (e: Throwable) { "?" }

    /**
     * 우리 앱이 지금 화면에 떠 있는지(포그라운드).
     * 실차 로그상 촬영 성공은 앱을 연 직후뿐이었다 — 이 값이 그 상관관계를 못박는다.
     */
    private fun appInForeground(): String = try {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val me = android.os.Process.myPid()
        val info = am?.runningAppProcesses?.firstOrNull { it.pid == me }
        when (info?.importance) {
            null -> "?"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "fgService"
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            else -> "background(" + info.importance + ")"
        }
    } catch (e: Throwable) { "?" }

    /**
     * 오버레이("다른 앱 위에 표시") 상태 (2026-09-04).
     * 실차 판정: do=false 로 액티비티 경로는 닫혔다. 볼트메터가 주행 중 떠 있는 남은 설명은
     * 오버레이 창이다(차단 서비스는 최상단 액티비티만 검사한다). 권한 보유 여부와
     * 권한 설정 화면이 이 차량에 존재하는지를 남긴다
     */
    private fun overlayState(): String = try {
        val granted = android.provider.Settings.canDrawOverlays(this)
        val screen = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:" + packageName)
        ).resolveActivity(packageManager) != null
        "granted=" + granted + " screen=" + screen
    } catch (e: Throwable) { "오류:" + e.javaClass.simpleName }

    /** 디스플레이가 켜져 있는지 — 카메라 차단이 화면 상태 때문인지 가리는 근거 */
    private fun isDisplayOn(): Boolean = try {
        getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: false
    } catch (e: Throwable) { false }

    /**
     * 주차 사진 촬영 (2026-09-01 — 재시도 방식으로 변경).
     *
     * 배경: 부팅으로 시작된 서비스는 백그라운드라 카메라가 `정책상 차단`된다.
     * 실차 로그상 **앱을 연 날만** 성공했다. 우회 시도는 두 번 다 막혔다 —
     * 오버레이는 AAOS에 권한 화면이 없고, 전체화면 인텐트는 AAOS가 액티비티 실행을 막고
     * "앱을 열 수 없습니다" 팝업만 띄운다(절전 예외 때와 같은 문구).
     *
     * 그래서 **화면을 전혀 건드리지 않는 조용한 재시도**로 간다.
     * 차단이 상태 의존적(디스플레이 on/off, 최근 사용 창 등)이라면 몇 분 뒤엔 열릴 수 있다.
     * 열리지 않으면 로그만 남고 끝 — 사용자에게 보이는 부작용이 없다.
     */
    // 촬영이 진행 중인 동안 true — 액티비티 경로와 폴백 경로가 겹쳐 두 번 찍는 것을 막는다
    private var photoBusy = false
    private var pendingPhotoHasLoc = true

    /** PhotoActivity를 띄운 뒤 4초가 지나도 안 떴으면(백그라운드 실행 차단) 예전 방식으로라도 시도 */
    private val photoFallback = Runnable {
        if (PhotoActivity.visibleSince == 0L) {
            ServiceLog.add(this, "사진 액티비티 안 뜸 (백그라운드 실행 차단 · 오버레이권한 " +
                DriveOverlay.canDraw(this) + " · 동반기기 " + CompanionLink.status(this) + ") → 백그라운드 촬영 시도")
            capturePark(0, pendingPhotoHasLoc)
        }
    }

    /**
     * 주차 사진 진입점 (2026-09-03).
     * 앱이 이미 화면에 있으면 바로 찍고, 아니면 투명 PhotoActivity를 띄워 포그라운드를 만든다.
     * 액티비티가 뜨면 ACTION_PHOTO_FOREGROUND 로 돌아와 촬영하고, 안 뜨면 photoFallback 이 돈다.
     */
    /** P단 진입 순간의 주차 사진: 위치를 먼저 임시 저장하고(사진을 붙일 행이 필요) 촬영. 주행이 끝나면 위치만 갱신하고 사진은 유지 */
    private fun parkPhotoAtP(now: Long, socPct: Float?) {
        val loc = lastLoc
        if (loc != null) db.saveParking(ParkingInfo(now, loc.latitude, loc.longitude, socPct))
        pPhotoTs = now
        photoDuringTrip = true
        ServiceLog.add(this, "P단 감지 → 주차 사진 시도 [시동 4 / 앱 " + appInForeground() + (if (loc == null) " / 위치 없음" else "") + "]")
        requestParkPhoto(loc != null)
    }

    private fun requestParkPhoto(hasLocation: Boolean) {
        if (tripActive && !photoDuringTrip) { ServiceLog.add(this, "주차 사진: 주행 중이라 건너뜀"); return }
        if (photoBusy) { ServiceLog.add(this, "주차 사진: 이전 촬영 진행 중이라 건너뜀"); return }
        val camOk = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!camOk) { ServiceLog.add(this, "주차 사진 실패: 카메라 권한 없음"); return }
        if (appInForeground() == "foreground") { capturePark(0, hasLocation); return }
        pendingPhotoHasLoc = hasLocation
        PhotoActivity.visibleSince = 0L
        try {
            startActivity(
                Intent(this, PhotoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(PhotoActivity.EXTRA_HAS_LOC, hasLocation)
            )
            // 백그라운드 액티비티 실행 예외: SYSTEM_ALERT_WINDOW 보유(targetSdk 22 자동 부여) 또는 동반 기기 연결.
            // 실차엔 동반 기기 대화상자가 없어(로그에 시도 흔적 없음) 오버레이 권한이 실제 열쇠다 (2026-09-05)
            ServiceLog.add(this, "사진 액티비티 실행 요청 (오버레이권한 " + DriveOverlay.canDraw(this) +
                " · 동반기기 " + CompanionLink.status(this) + ")")
        } catch (e: Throwable) {
            ServiceLog.add(this, "사진 액티비티 실행 예외: " + e.javaClass.simpleName)
        }
        handler.removeCallbacks(photoFallback)
        handler.postDelayed(photoFallback, 4_000L)
    }

    /** 촬영 종료(성공이든 전부 실패든): 잠금 해제 + 사진 액티비티 닫기 */
    private fun finishPhoto() {
        handler.post {
            photoBusy = false
            photoDuringTrip = false
            PhotoActivity.dismiss()
        }
    }

    private fun capturePark(attempt: Int, hasLocation: Boolean) {
        if (tripActive && !photoDuringTrip) return          // 새 주행이 시작됐으면 이 주차는 이미 지난 일이다
        if (photoBusy) { ServiceLog.add(this, "주차 사진: 이미 촬영 중"); return }
        val camOk = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!camOk) {
            ServiceLog.add(this, "주차 사진 실패: 카메라 권한 없음")
            return
        }
        // 카메라를 전부 차례로 시도한다 (2026-09-02). 지금까지는 실내 카메라 하나만 열어보고
        // 포기했는데, 순정 블랙박스는 실외향 카메라로 찍는다. 다른 카메라는 열릴 수 있다.
        // 순서: 실내 → 외장 → 후방 → 나머지
        val cams = ParkingCamera.listCameras(this)
        val order = cams.sortedBy { (_, f) ->
            when (f) { "실내" -> 0; "외장" -> 1; "후방" -> 2; else -> 3 }
        }.map { it.first }
        if (order.isEmpty()) { ServiceLog.add(this, "주차 사진 실패: 카메라 없음"); finishPhoto(); return }
        photoBusy = true
        captureWithCamera(order, 0, attempt, hasLocation)
    }

    /** order[idx] 카메라로 찍어보고, 안 되면 다음 카메라로 넘어간다 */
    private fun captureWithCamera(order: List<String>, idx: Int, attempt: Int, hasLocation: Boolean) {
        if ((tripActive && !photoDuringTrip) || idx >= order.size) { finishPhoto(); return }
        setCameraFgsType(true)
        ParkingCamera.captureAsync(this, order[idx]) { file, err ->
            handler.post { setCameraFgsType(false) }
            val nth = if (attempt == 0) "" else " (" + (attempt + 1) + "차 시도)"
            if (file != null && !hasLocation) {
                Prefs.setPhotoFailed(this, false)
                ServiceLog.add(this, "주차 사진 촬영 성공 (" + (file.length() / 1024) +
                    "KB)" + nth + " — 위치가 없어 등록은 안 함")
                finishPhoto()
            } else if (file != null) {
                db.setParkingPhotoTs(System.currentTimeMillis())
                Prefs.setPhotoFailed(this, false)
                // 사진이 차 안에 갇히지 않도록 다음 tick에서 바로 동기화하게 만든다
                handler.post { lastSyncAttempt = 0L }
                ServiceLog.add(this, "주차 사진 촬영 성공 (" + (file.length() / 1024) + "KB)" + nth +
                    " [화면 " + (if (isDisplayOn()) "on" else "off") +
                    " / 시동 " + (carReader.ignitionState()?.toString() ?: "?") +
                    " / 정책차단 " + cameraDisabledByPolicy() +
                    " / 앱 " + appInForeground() + "]")
                Log.i(TAG, "parking photo saved")
                finishPhoto()
            } else {
                Prefs.setPhotoFailed(this, true)
                // 왜 막혔는지 추측하지 않도록 그 순간의 상태를 같이 남긴다.
                // 화면이 꺼져서인지, 시동이 꺼져서인지, 그냥 백그라운드라서인지 구분된다
                ServiceLog.add(this, "주차 사진 실패" + nth + ": " + (err ?: "알 수 없음") +
                    " [화면 " + (if (isDisplayOn()) "on" else "off") +
                    " / 시동 " + (carReader.ignitionState()?.toString() ?: "?") +
                    " / 정책차단 " + cameraDisabledByPolicy() +
                    " / 앱 " + appInForeground() + "]")
                // 백그라운드 제한처럼 '나중엔 될 수도 있는' 실패만 재시도한다
                val e = err ?: ""
                // 이 카메라가 안 되면 다음 카메라로. 전부 실패해야 진짜 실패다
                if (idx + 1 < order.size) {
                    handler.post { captureWithCamera(order, idx + 1, attempt, hasLocation) }
                    return@captureAsync
                }
                finishPhoto()
                val retriable = e.contains("정책") || e.contains("차단") || e.contains("시간 초과")
                // 시동이 완전히 꺼질 때(OFF/LOCK) 한 번 더 (정책차단=false로 확인됐지만 비용이 없다)
                if (retriable && attempt == 0) waitForIgnitionOffThenCapture(hasLocation)
            }
        }
    }

    /**
     * 시동이 OFF/LOCK 으로 떨어지면 사진을 한 번 더 시도한다 (2026-09-01).
     * 최대 5분간 20초 간격으로 확인하고, 그 안에 안 꺼지면 포기한다.
     * 새 주행이 시작되면 즉시 중단 — 지난 주차의 사진은 의미가 없다.
     */
    private fun waitForIgnitionOffThenCapture(hasLocation: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5 * 60_000L
        val poll = object : Runnable {
            override fun run() {
                if (tripActive || !running) return
                val ign = carReader.ignitionState()
                if (ign != null && ign <= 2) {          // 1=LOCK, 2=OFF
                    ServiceLog.add(this@LoggerService, "시동 꺼짐 감지 → 주차 사진 재시도")
                    capturePark(1, hasLocation)
                    return
                }
                if (SystemClock.elapsedRealtime() < deadline) handler.postDelayed(this, 20_000L)
            }
        }
        handler.postDelayed(poll, 20_000L)
    }

    // ---------- 주행 ----------

    private fun startTrip(now: Long, batteryWh: Float?, socPct: Float?) {
        tripActive = true
        tripStartTs = now
        tripStartWh = batteryWh
        tripStartSoc = socPct
        tripDistanceM = 0.0
        tripMaxKmh = 0.0
        tripMovingMs = 0L
        tripRegenKwh = 0.0
        tripNetIntegralKwh = 0.0
        lastGear = carReader.gear()
        tripConsumeSum = 0.0; tripConsumeCnt = 0; tripConsumePeak = 0f
        tripRegenSum = 0.0; tripRegenCnt = 0; tripRegenPeak = 0f
        tripWheelM = 0.0
        tripSpeedM = 0.0
        tripPoints = JSONArray()
        tripLastPoint = null
        stoppedSinceMs = 0L
        lastLoc?.let { appendTripPoint(it) }
        updateNotification("주행 기록 중")
        if (Prefs.driveOverlay(this) && !DriveOverlay.isShown()) {
            // 오버레이 창: 액티비티가 아니라 주행 중 차단 검사 대상이 아니다 (실차 확인 2026-09-07). 앱이 떠 있던 경우에만
            showDriveOverlayIfAppWasOnScreen()
        }
        if (DriveDisplay.enabled(this)) {
            val (t, a, _) = DriveDisplay.lines(lastSnapshot)
            DriveDisplay.headsUp(this, "주행 시작 · " + a, t, navigationCategory = false)
            handler.postDelayed({
                if (tripActive) DriveDisplay.headsUp(this, "P.Log (내비 종류 시험)",
                    DriveDisplay.lines(lastSnapshot).second, navigationCategory = true)
            }, 20_000L)
        }
        // 주행 중 UX 제한 상태 (2026-09-04): 폴스타4가 주행 중에 DO를 요구하는지는 정차 로그로는 알 수 없다.
        // requiresDO=false 로 계속 나오면 "정차 중 열어 둔 앱은 주행 중에도 남는다"는 뜻이 된다
        ServiceLog.add(this, "주행 시작 [ux=" + carReader.uxRestrictionState() + " / 기어 " + gearName(carReader.gear()) + "]")
        handler.postDelayed({
            if (tripActive) ServiceLog.add(this, "주행 1분 경과 [ux=" + carReader.uxRestrictionState() +
                " / 속도 " + Math.round(lastSnapshot.speedKmh?.toDouble() ?: 0.0) + "]")
        }, 60_000L)
        Log.i(TAG, "trip start")
    }

    private fun endTrip(now: Long, batteryWh: Float?, socPct: Float?) {
        tripActive = false
        movingSinceMs = 0L
        stoppedSinceMs = 0L
        updateNotification("기록 대기 중")

        val durMin = (now - tripStartTs) / 60_000.0

        // 거리 소스: 바퀴 틱 우선, 없으면 GPS.
        // 바퀴 틱은 CAR_SPEED 권한(이미 허용)으로 읽히고 터널·지하에서도 끊기지 않는다.
        // GPS는 백그라운드에서 막히면 0m가 되어 멀쩡한 주행이 통째로 폐기됐다(08-27~30 실차 확인)
        // 우선순위: 바퀴(가장 정확) → GPS → 속도 적분(마지막 안전망).
        // 셋 다 독립적이라 하나가 막혀도 주행이 통째로 사라지지 않는다
        val distM: Double
        val src: String
        when {
            tripWheelM >= 50.0 -> { distM = tripWheelM; src = "바퀴" }
            tripDistanceM >= 50.0 -> { distM = tripDistanceM; src = "GPS" }
            else -> { distM = tripSpeedM; src = "속도적분" }
        }

        if (distM < MIN_TRIP_M || durMin < 1.0) {
            ServiceLog.add(this, "주행 폐기: " + Math.round(distM) + "m(" + src + "), " +
                String.format("%.1f", durMin) + "분 (너무 짧음)" +
                " [바퀴 " + Math.round(tripWheelM) + " / GPS " + Math.round(tripDistanceM) +
                " / 속도 " + Math.round(tripSpeedM) + "m]")
            Log.i(TAG, "trip discarded (too short)")
            return
        }
        val sWh = tripStartWh
        val energyKwh: Double? =
            if (sWh != null && batteryWh != null && sWh > batteryWh)
                ((sWh - batteryWh) / 1000.0)
            else null
        // 움직인 시간 기준 평균. 이동 시간이 없으면(이상 상황) 경과시간으로 폴백
        val movingH = tripMovingMs / 3_600_000.0
        val avgKmh =
            if (movingH > 0.003) (distM / 1000.0) / movingH
            else (distM / 1000.0) / ((now - tripStartTs) / 3_600_000.0)

        val first = if (tripPoints.length() > 0) tripPoints.optJSONArray(0) else null
        val last = if (tripPoints.length() > 0) tripPoints.optJSONArray(tripPoints.length() - 1) else null

        val trip = Trip(
            startTs = tripStartTs, endTs = now,
            distanceM = distM,
            energyKwh = energyKwh,
            socStart = tripStartSoc, socEnd = socPct,
            avgKmh = avgKmh, maxKmh = tripMaxKmh,
            startLat = first?.optDouble(0), startLon = first?.optDouble(1),
            endLat = last?.optDouble(0), endLon = last?.optDouble(1),
            polyline = tripPoints.toString(),
            regenKwh = Math.round(tripRegenKwh * 1000.0) / 1000.0
        )
        val tripId = db.insertTrip(trip)
        tripSavedCount++
        // 출발·도착 동네 이름 (백그라운드, 요청 2번). 끝나면 목록이 다음에 열릴 때 반영된다
        PlaceNames.resolveAsync(this, tripId, trip.startLat, trip.startLon, trip.endLat, trip.endLon)
        ServiceLog.add(this, "주행 저장 " + String.format("%.1f", trip.distanceKm) + "km " +
            src + " (평균 " + Math.round(avgKmh) + ", 최고 " + Math.round(tripMaxKmh) + ")" +
            " [바퀴 " + Math.round(tripWheelM) + " / GPS " + Math.round(tripDistanceM) +
            " / 속도 " + Math.round(tripSpeedM) + "m] [ux=" + carReader.uxRestrictionState() +
            " / 회생 " + String.format("%.2f", tripRegenKwh) + "kWh]")
        // 회생 적분 검증: 순 적분(−소비+회생) vs 배터리 Wh 델타. 두 값이 비슷하면 2초 샘플링 적분이 믿을 만하다 (2026-09-15)
        ServiceLog.add(this, "회생 검증: 전력 순적분 " + String.format("%.2f", tripNetIntegralKwh) + "kWh vs 배터리 델타 " +
            (if (energyKwh != null) String.format("-%.2f", energyKwh) else "?") + "kWh [회생 " + String.format("%.2f", tripRegenKwh) +
            " / 소비 적분 " + String.format("%.2f", tripRegenKwh - tripNetIntegralKwh) + "]")
        Prefs.addLifetimeKm(this, trip.distanceKm)
        refreshTodayCache()
        // 주행이 차 안에 갇히지 않도록 즉시 동기화 (10분 쿨다운을 기다리다
        // 서비스가 죽으면 다음 시동까지 서버에 안 올라간다)
        lastSyncAttempt = 0L

        // 주차 위치 저장 + 전면 카메라로 주차 사진 촬영 (실패해도 무시)
        val loc = lastLoc
        // P단에서 이미 찍은 사진(15분 안)이 있으면 위치만 갱신하고 사진은 그대로 둔다 (REPLACE로 지워지지 않게)
        val keepPhoto = if (now - pPhotoTs < 15 * 60_000L) db.parking()?.photoTs?.takeIf { it >= pPhotoTs } else null
        if (loc != null) {
            db.saveParking(ParkingInfo(now, loc.latitude, loc.longitude, socPct, keepPhoto))
        } else {
            // GPS가 막히면 주차 위치를 못 남긴다. 사진은 그래도 찍어서 원인을 계속 추적한다
            ServiceLog.add(this, "주차 위치 없음 (GPS 미수신) — 사진만 시도")
        }
        if (keepPhoto != null) ServiceLog.add(this, "주차 사진: P단에서 이미 촬영 (" + Fmt.time(keepPhoto) + ") → 재촬영 안 함")
        else requestParkPhoto(loc != null)
        Log.i(TAG, "trip saved: ${trip.distanceKm} km")
    }

    // ---------- 충전 ----------

    private fun currentChargeKwh(batteryWh: Float?): Double {
        if (!chargeActive) return 0.0
        val sWh = chargeStartWh
        return if (sWh != null && batteryWh != null && batteryWh > sWh)
            (batteryWh - sWh) / 1000.0
        else chargeIntegralKwh
    }

    private fun cancelCharge() {
        chargeActive = false
        chargeQuietSinceMs = 0L
        chargeIntegralKwh = 0.0
        chargeStation = null
        chargeOperator = null
        chargeRateOverride = null
        chargeOutputs = null
        chargeStLat = null
        chargeStLon = null
        updateNotification("기록 대기 중")
        Log.i(TAG, "charge canceled (trip started)")
    }

    private fun startCharge(now: Long, batteryWh: Float?, socPct: Float?) {
        chargeActive = true
        chargeStartTs = now
        chargeStartWh = batteryWh
        chargeStartSoc = socPct
        chargeMaxKw = 0.0
        chargeProfile = JSONArray()
        chargeLastSampleTs = 0L
        chargeQuietSinceMs = 0L
        chargeIntegralKwh = 0.0
        chargeStation = null
        chargeOperator = null
        chargeRateOverride = null
        chargeOutputs = null
        updateNotification("충전 기록 중")
        Log.i(TAG, "charge start")

        // 충전소 자동 식별 (마지막 GPS 좌표 기준 — 지하는 진입 전 좌표라도 대부분 매칭됨)
        val loc = lastLoc
        chargeStLat = loc?.latitude
        chargeStLon = loc?.longitude
        if (loc != null) {
            EvStations.resolveAsync(this, loc.latitude, loc.longitude) { found ->
                handler.post {
                    if (chargeActive && found != null) {
                        chargeStation = found.name
                        chargeOperator = found.operator
                        chargeRateOverride = found.rate
                        chargeOutputs = found.outputs
                        updateNotification("충전 기록 중 · ${found.name}")
                    }
                }
            }
        }
    }

    private fun endCharge(now: Long, batteryWh: Float?, socPct: Float?) {
        chargeActive = false
        chargeQuietSinceMs = 0L
        updateNotification("기록 대기 중")

        val kwh = run {
            val sWh = chargeStartWh
            if (sWh != null && batteryWh != null && batteryWh > sWh)
                (batteryWh - sWh) / 1000.0
            else chargeIntegralKwh
        }
        if (kwh < MIN_CHARGE_KWH) {
            Log.i(TAG, "charge discarded (too small)")
            return
        }
        val type = if (chargeMaxKw > DC_THRESHOLD_KW) "DC" else "AC"
        // 단가: 충전소 프로필에 사용자가 확정한 값이 있으면 그것,
        // 없으면 환경부 로밍 단가표(충전기 정격 출력 구간별 5단계)로 자동 매칭.
        // 정격 출력은 그 충전소의 충전기 목록(API) 중 실측 최대 전력에 맞는 것을 고른다.
        // 완속(AC)은 정격 매칭에서 제외한다 — 실측 7~11kW가 그대로 '30kW 미만' 구간이고,
        // 그 충전소에 급속기만 등록돼 있을 때 50kW로 잘못 올라붙는 것을 막는다
        val ratedKw = if (type == "AC") chargeMaxKw
                      else EvStations.ratedOutputFor(chargeOutputs, chargeMaxKw)
        val rate = chargeRateOverride ?: EvStations.roamingRate(ratedKw)
        val session = ChargeSession(
            startTs = chargeStartTs, endTs = now,
            kwh = Math.round(kwh * 100.0) / 100.0,
            cost = Math.round(kwh * rate).toDouble(),
            socStart = chargeStartSoc, socEnd = socPct,
            maxKw = Math.round(chargeMaxKw * 10.0) / 10.0,
            type = type,
            profile = chargeProfile.toString(),
            station = chargeStation,
            stLat = chargeStLat, stLon = chargeStLon
        )
        db.insertCharge(session)
        refreshCycleCache()   // 새 충전 = 회생 사이클 기준점 갱신
        Log.i(
            TAG, "charge saved: $kwh kWh $type, 정격 ${ratedKw}kW (실측 $chargeMaxKw, 후보 $chargeOutputs)" +
                " → ${rate}원/kWh @ ${chargeStation ?: "미식별"}/${chargeOperator ?: "-"}"
        )
    }

    // ---------- 오늘 합계 ----------

    private fun dayStartTs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun refreshTodayCache() {
        val t = db.tripTotals(dayStartTs(), Long.MAX_VALUE)
        todayKmCache = t[1] / 1000.0
        todayEnergyCache = t[2]
        todayRegenCache = t[3]
        todayCacheDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        refreshCycleCache()
    }

    /** 회생 사이클: 마지막 충전 종료 이후 주행 합계 + 최근 30일 평소 비율 (주행 저장·충전 종료·시작 때 갱신) */
    private fun refreshCycleCache() {
        val since = db.lastChargeEndTs()
        cycleSinceTs = since
        val c = db.tripTotals(since ?: 0L, Long.MAX_VALUE)
        cycleConsumeCache = c[2]; cycleRegenCache = c[3]
        cycleKmCache = c[1] / 1000.0
        cycleSocEnd = if (since != null) db.lastChargeSocEnd() else null
        val u = db.tripTotals(System.currentTimeMillis() - 30L * 86_400_000L, Long.MAX_VALUE)
        usualRegenRatioPct = if (u[2] > 0.5) (u[3] / u[2] * 100.0).toFloat() else 0f
    }

    private fun refreshTodayCacheIfDayChanged() {
        if (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) != todayCacheDay) refreshTodayCache()
    }

    // ---------- 동기화 ----------

    private var lastSyncAttempt = 0L

    private fun maybeSync(now: Long) {
        if (now - lastSyncAttempt < 10 * 60_000L) return
        if (Prefs.supabaseUrl(this).isEmpty() || Prefs.supabaseKey(this).isEmpty()) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val online = cm.activeNetwork != null
        if (!online) return
        lastSyncAttempt = now
        SyncManager.uploadAsync(applicationContext) { }
    }
}
