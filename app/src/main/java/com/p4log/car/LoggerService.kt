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
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, LoggerService::class.java)
            try {
                context.startForegroundService(i)
            } catch (e: Throwable) {
                Log.w(TAG, "startForegroundService failed", e)
            }
        }
    }

    private lateinit var carReader: CarDataReader
    private lateinit var db: Db
    private val handler = Handler(Looper.getMainLooper())

    // GPS 상태
    private var lastLoc: Location? = null
    private var lastLocElapsed: Long = 0L

    // 주행 상태
    private var tripActive = false
    private var tripStartTs = 0L
    private var tripStartWh: Float? = null
    private var tripStartSoc: Float? = null
    private var tripDistanceM = 0.0
    private var tripMaxKmh = 0.0
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
    private var chargeStLat: Double? = null
    private var chargeStLon: Double? = null

    // 오늘 주행 캐시
    private var todayKmCache = 0.0
    private var todayEnergyCache = 0.0
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(ticker)
        stopGps()
        carReader.disconnect()
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

        val gpsFresh = lastLoc != null && (mono - lastLocElapsed) < 15_000L
        val gpsSpeedKmh: Float? =
            if (gpsFresh && lastLoc!!.hasSpeed()) lastLoc!!.speed * 3.6f else null
        // 차량이 속도 속성을 "지원"하면서 값은 0으로 고정해 주는 경우가 있어
        // (에뮬레이터 확인, 실차 의심) null 폴백 대신 둘 중 큰 값을 쓴다
        val speedKmh: Double = maxOf(carSpeed ?: 0f, gpsSpeedKmh ?: 0f).toDouble()

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

        // ----- 스냅샷 발행 -----
        refreshTodayCacheIfDayChanged()
        val curTripKm = tripDistanceM / 1000.0
        val todayKm = todayKmCache + (if (tripActive) curTripKm else 0.0)
        val todayEff: Double? =
            if (todayEnergyCache > 0.3) (todayKmCache / todayEnergyCache) else null

        lastSnapshot = StatusSnapshot(
            ts = now,
            carConnected = carReader.isConnected,
            batteryWh = batteryWh,
            capacityWh = capacityWh,
            socPct = socPct,
            chargeRateKw = rateKw,
            rangeKm = rangeKm,
            speedKmh = carSpeed?.toFloat(),
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
            todayEff = todayEff
        )

        // ----- 유휴 시 자동 동기화 -----
        if (!tripActive && !chargeActive) maybeSync(now)
    }

    // ---------- 주행 ----------

    private fun startTrip(now: Long, batteryWh: Float?, socPct: Float?) {
        tripActive = true
        tripStartTs = now
        tripStartWh = batteryWh
        tripStartSoc = socPct
        tripDistanceM = 0.0
        tripMaxKmh = 0.0
        tripPoints = JSONArray()
        tripLastPoint = null
        stoppedSinceMs = 0L
        lastLoc?.let { appendTripPoint(it) }
        updateNotification("주행 기록 중")
        Log.i(TAG, "trip start")
    }

    private fun endTrip(now: Long, batteryWh: Float?, socPct: Float?) {
        tripActive = false
        movingSinceMs = 0L
        stoppedSinceMs = 0L
        updateNotification("기록 대기 중")

        val durMin = (now - tripStartTs) / 60_000.0
        if (tripDistanceM < MIN_TRIP_M || durMin < 1.0) {
            Log.i(TAG, "trip discarded (too short)")
            return
        }
        val sWh = tripStartWh
        val energyKwh: Double? =
            if (sWh != null && batteryWh != null && sWh > batteryWh)
                ((sWh - batteryWh) / 1000.0)
            else null
        val avgKmh = (tripDistanceM / 1000.0) / ((now - tripStartTs) / 3_600_000.0)

        val first = if (tripPoints.length() > 0) tripPoints.optJSONArray(0) else null
        val last = if (tripPoints.length() > 0) tripPoints.optJSONArray(tripPoints.length() - 1) else null

        val trip = Trip(
            startTs = tripStartTs, endTs = now,
            distanceM = tripDistanceM,
            energyKwh = energyKwh,
            socStart = tripStartSoc, socEnd = socPct,
            avgKmh = avgKmh, maxKmh = tripMaxKmh,
            startLat = first?.optDouble(0), startLon = first?.optDouble(1),
            endLat = last?.optDouble(0), endLon = last?.optDouble(1),
            polyline = tripPoints.toString()
        )
        db.insertTrip(trip)
        Prefs.addLifetimeKm(this, trip.distanceKm)
        refreshTodayCache()

        // 주차 위치 저장
        val loc = lastLoc
        if (loc != null) {
            db.saveParking(ParkingInfo(now, loc.latitude, loc.longitude, socPct))
        }
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
        // 단가 우선순위: 충전소 프로필(사용자 확정) → 운영사 공표 단가 → 설정의 기본 요금
        val rate = chargeRateOverride
            ?: EvStations.operatorRate(chargeOperator, type)
            ?: if (type == "DC") Prefs.rateDc(this) else Prefs.rateAc(this)
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
        Log.i(TAG, "charge saved: $kwh kWh $type")
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
        todayCacheDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
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
