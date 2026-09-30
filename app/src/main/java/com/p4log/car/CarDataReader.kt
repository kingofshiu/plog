package com.p4log.car

import android.content.Context
import android.util.Log

/**
 * AAOS Car API 리플렉션 래퍼.
 *
 * android.car 라이브러리는 자동차 기기의 프레임워크에만 존재하므로
 * 컴파일 의존성 없이 리플렉션으로 접근한다. (일반 SDK로 빌드 가능)
 *
 * 속성 ID는 런타임에 android.car.VehiclePropertyIds에서 이름으로 해석하고,
 * 실패 시 AOSP 공개 상수값을 폴백으로 사용한다.
 */
class CarDataReader(private val context: Context) {

    companion object {
        private const val TAG = "P4Log.Car"

        // AOSP VehiclePropertyIds 공개 상수 (폴백)
        private val FALLBACK_IDS = mapOf(
            "PERF_VEHICLE_SPEED" to 291504647,
            "PERF_ODOMETER" to 291504644,
            "EV_BATTERY_LEVEL" to 291504905,
            "INFO_EV_BATTERY_CAPACITY" to 291504390,
            "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 291504908,
            "RANGE_REMAINING" to 291504904,
            "EV_CHARGE_PORT_CONNECTED" to 287310603,
            "IGNITION_STATE" to 289408009,
            // 아래 둘은 CAR_SPEED 권한으로 접근 가능한지 조사용 (2026-08-26)
            "PERF_VEHICLE_SPEED_DISPLAY" to 291504648,   // 0x11600208 계기판 표시 속도
            "WHEEL_TICK" to 290521862                    // 0x11510306 바퀴 회전 누적 틱
        )
        private const val AREA_GLOBAL = 0
    }

    private var car: Any? = null
    private var propMgr: Any? = null

    /** 진단 화면용: 속성명 -> 마지막 상태 문자열 */
    val diag = LinkedHashMap<String, String>()
    var connectionError: String? = null
        private set

    val isConnected: Boolean get() = propMgr != null

    fun connect(): Boolean {
        if (propMgr != null) return true
        return try {
            val carClass = Class.forName("android.car.Car")
            val created = try {
                // API 29+ : 정적 팩토리, 즉시 연결된 Car 반환
                carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            } catch (e: NoSuchMethodException) {
                null
            }
            if (created == null) {
                connectionError = "Car.createCar 사용 불가"
                return false
            }
            car = created
            propMgr = carClass.getMethod("getCarManager", String::class.java)
                .invoke(created, "property") // Car.PROPERTY_SERVICE
            if (propMgr == null) connectionError = "CarPropertyManager 획득 실패"
            propMgr != null
        } catch (e: ClassNotFoundException) {
            connectionError = "android.car 프레임워크 없음 (자동차 기기가 아님)"
            Log.w(TAG, "Car framework not found", e)
            false
        } catch (e: Throwable) {
            connectionError = "연결 실패: ${e.javaClass.simpleName} ${e.message ?: ""}"
            Log.w(TAG, "Car connect failed", e)
            false
        }
    }

    fun disconnect() {
        try {
            val c = car ?: return
            c.javaClass.getMethod("disconnect").invoke(c)
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect failed", e)
        } finally {
            car = null
            propMgr = null
        }
    }

    private fun propId(name: String): Int {
        return try {
            Class.forName("android.car.VehiclePropertyIds").getField(name).getInt(null)
        } catch (e: Throwable) {
            FALLBACK_IDS[name] ?: 0
        }
    }

    private fun readRaw(name: String, kind: Char): Any? {
        val mgr = propMgr ?: run {
            if (!connect()) return null
            propMgr ?: return null
        }
        val id = propId(name)
        if (id == 0) { diag[name] = "속성 ID 해석 실패"; return null }
        return try {
            val v: Any? = when (kind) {
                'f' -> mgr.javaClass.getMethod(
                    "getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).invoke(mgr, id, AREA_GLOBAL)
                'i' -> mgr.javaClass.getMethod(
                    "getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).invoke(mgr, id, AREA_GLOBAL)
                'b' -> mgr.javaClass.getMethod(
                    "getBooleanProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).invoke(mgr, id, AREA_GLOBAL)
                else -> null
            }
            diag[name] = "OK: $v"
            v
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            diag[name] = when {
                cause is SecurityException -> "권한 거부 — ${cause.message ?: "SecurityException"}"
                cause is IllegalArgumentException -> "차량 미지원 속성"
                else -> "오류: ${cause.javaClass.simpleName} ${cause.message ?: ""}"
            }
            null
        }
    }

    private fun readFloat(name: String): Float? = readRaw(name, 'f') as? Float
    private fun readInt(name: String): Int? = readRaw(name, 'i') as? Int
    private fun readBool(name: String): Boolean? = readRaw(name, 'b') as? Boolean

    // ---- 공개 읽기 API (모두 null 허용: 미지원/권한거부 시 null) ----

    /** 배터리 잔량 (Wh) */
    fun batteryWh(): Float? = readFloat("EV_BATTERY_LEVEL")

    /** 배터리 총용량 (Wh) */
    fun capacityWh(): Float? = readFloat("INFO_EV_BATTERY_CAPACITY")

    /** 순간 충전(+)/방전(-) 전력 (kW) — 원본 단위 mW */
    fun chargeRateKw(): Float? = readFloat("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE")?.let { it / 1_000_000f }

    /** 남은 주행가능거리 (km) — 원본 단위 m */
    fun rangeKm(): Float? = readFloat("RANGE_REMAINING")?.let { it / 1000f }

    /** 차량 속도 (km/h) — 원본 단위 m/s */
    fun speedKmh(): Float? = readFloat("PERF_VEHICLE_SPEED")?.let { it * 3.6f }

    /** 시동 상태: 1=LOCK 2=OFF 3=ACC 4=ON 5=START */
    fun ignitionState(): Int? = readInt("IGNITION_STATE")

    /** 충전 포트 연결 여부 */
    fun chargePortConnected(): Boolean? = readBool("EV_CHARGE_PORT_CONNECTED")

    /** 적산거리 (km) — privileged 권한이라 대부분 실패함. 진단용 */
    fun odometerKm(): Float? = readFloat("PERF_ODOMETER")

    /**
     * 계기판 표시 속도 (km/h). PERF_VEHICLE_SPEED가 0으로 고정 보고되는 차량의 대안.
     * 권한은 PERF_VEHICLE_SPEED와 같은 CAR_SPEED다.
     */
    fun speedDisplayKmh(): Float? = readFloat("PERF_VEHICLE_SPEED_DISPLAY")?.let { it * 3.6f }

    /**
     * 바퀴 회전 누적 틱 (WHEEL_TICK). 반환 [timestampNs, 앞좌, 앞우, 뒤좌, 뒤우].
     *
     * **이게 되면 GPS 대신 바퀴 굴림 기반으로 거리를 잴 수 있다.**
     * PERF_ODOMETER와 달리 WHEEL_TICK은 시스템 권한이 아니라 CAR_SPEED(런타임 권한)를 쓰므로
     * 사이드로드 앱도 접근 가능성이 있다 — 실차에서 되는지 확인이 필요하다.
     */
    /** 마지막 WHEEL_TICK 샘플의 차량 타임스탬프(elapsedRealtimeNanos). 바퀴 속도의 dt는 이걸로 계산한다 (2026-09-17). 0이면 미지원 */
    @Volatile var lastWheelTickNs = 0L
        private set

    fun wheelTicks(): LongArray? {
        val mgr = propMgr ?: run { if (!connect()) return null; propMgr ?: return null }
        val id = propId("WHEEL_TICK")
        if (id == 0) { diag["WHEEL_TICK"] = "속성 ID 해석 실패"; return null }
        return try {
            val longArrClass = java.lang.reflect.Array
                .newInstance(java.lang.Long::class.java, 0).javaClass
            val cpv = mgr.javaClass.getMethod(
                "getProperty", Class::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(mgr, longArrClass, id, AREA_GLOBAL)
            if (cpv == null) { diag["WHEEL_TICK"] = "값 없음(null)"; return null }
            lastWheelTickNs = try { (cpv.javaClass.getMethod("getTimestamp").invoke(cpv) as? Long) ?: 0L } catch (e: Throwable) { 0L }
            val raw = cpv.javaClass.getMethod("getValue").invoke(cpv)
            val boxed = raw as? Array<*> ?: run {
                diag["WHEEL_TICK"] = "예상 못한 타입: " + (raw?.javaClass?.simpleName ?: "null")
                return null
            }
            val out = LongArray(boxed.size) { (boxed[it] as? Number)?.toLong() ?: 0L }
            diag["WHEEL_TICK"] = "OK: " + out.joinToString(",")
            out
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            diag["WHEEL_TICK"] = when {
                cause is SecurityException -> "권한 거부 — ${cause.message ?: "SecurityException"}"
                cause is IllegalArgumentException -> "차량 미지원 속성"
                else -> "오류: ${cause.javaClass.simpleName} ${cause.message ?: ""}"
            }
            null
        }
    }

    /**
     * WHEEL_TICK 1틱당 이동거리 (마이크로미터). configArray = [지원바퀴비트, FL, FR, RL, RR].
     * 이 값이 있어야 틱 → 미터 변환이 가능하다.
     */
    fun wheelTickMicrometers(): List<Int>? {
        val mgr = propMgr ?: run { if (!connect()) return null; propMgr ?: return null }
        val id = propId("WHEEL_TICK")
        if (id == 0) return null
        return try {
            val cfg = mgr.javaClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType)
                .invoke(mgr, id)
            if (cfg == null) { diag["WHEEL_TICK_CONFIG"] = "설정 없음"; return null }
            val list = cfg.javaClass.getMethod("getConfigArray").invoke(cfg) as? List<*>
            val out = list?.mapNotNull { (it as? Number)?.toInt() }
            diag["WHEEL_TICK_CONFIG"] =
                if (out.isNullOrEmpty()) "설정 배열 비어 있음" else "OK: " + out.joinToString(",")
            out
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            diag["WHEEL_TICK_CONFIG"] = "오류: ${cause.javaClass.simpleName} ${cause.message ?: ""}"
            null
        }
    }

    /**
     * 아래 4개는 폴스타 비공식 클라우드 API(kildahldev/unofficial-polestar-api)가 제공하는 항목 중
     * **차 안에서 계정 로그인 없이 읽을 수 있는지** 조사 대상으로 고른 것들 (2026-08-31).
     * 되는 것만 골라 기능에 붙인다. 이름 해석이 안 되면 propId가 0 → "속성 ID 해석 실패"로 남는다.
     */
    /** 외기 온도 (°C) — 전비가 계절에 따라 얼마나 떨어지는지 보려면 이게 필요하다 */
    fun outsideTempC(): Float? = readFloat("ENV_OUTSIDE_TEMPERATURE")

    /** 충전 완료까지 남은 시간 (초) */
    fun chargeTimeRemainingSec(): Int? = readInt("EV_CHARGE_TIME_REMAINING")

    /** 충전 목표 % (사용자가 차에서 설정한 상한) */
    fun chargePercentLimit(): Float? = readFloat("EV_CHARGE_PERCENT_LIMIT")

    /** 타이어 공기압 — 영역(area)별 속성이라 GLOBAL 읽기는 실패할 수 있다. 조사만 */
    fun tirePressure(): Float? = readFloat("TIRE_PRESSURE")

    /** 진단: 전 속성 1회 읽기 시도 */
    fun probeAll() {
        batteryWh(); capacityWh(); chargeRateKw(); rangeKm()
        speedKmh(); speedDisplayKmh(); ignitionState(); chargePortConnected(); odometerKm()
        wheelTicks(); wheelTickMicrometers()
        outsideTempC(); chargeTimeRemainingSec(); chargePercentLimit(); tirePressure()
    }

    /**
     * CarService가 우리 메인 화면을 '주행 중 표시 가능(distraction optimized)'으로 보는지 (2026-09-02).
     * 매니페스트 meta-data가 실제로 먹히는지는 이 API 답으로만 확실히 알 수 있다.
     * true면 주행 중에도 화면이 안 가려지고, 앱이 떠 있는 상태로 주차 사진 촬영이 가능해진다.
     */
    fun mainActivityDistractionOptimized(): String {
        return try {
            val c = car ?: run { if (!connect()) return "차량 미연결"; car ?: return "차량 미연결" }
            val pkgMgr = c.javaClass.getMethod("getCarManager", String::class.java)
                .invoke(c, "package")                     // Car.PACKAGE_SERVICE
                ?: return "CarPackageManager 없음"
            val r = pkgMgr.javaClass.getMethod(
                "isActivityDistractionOptimized", String::class.java, String::class.java
            ).invoke(pkgMgr, context.packageName, MainActivity::class.java.name)
            r?.toString() ?: "null"
        } catch (e: Throwable) {
            "오류: " + (e.cause ?: e).javaClass.simpleName
        }
    }

    /**
     * 차량이 지금 '주행 중 UX 제한'을 걸고 있는지 (2026-09-02).
     * requiresDO=true 면 DO 아닌 앱은 주행 중 화면에서 가려진다. false면 P.Log는 그냥 떠 있을 수 있고,
     * 그 경우 주차 사진은 "앱을 화면에 둔 채 운전"만으로 해결된다.
     */
    fun uxRestrictionState(): String {
        return try {
            val c = car ?: run { if (!connect()) return "차량 미연결"; car ?: return "차량 미연결" }
            val mgr = c.javaClass.getMethod("getCarManager", String::class.java)
                .invoke(c, "uxrestriction")               // Car.CAR_UX_RESTRICTION_SERVICE
                ?: return "매니저 없음"
            val r = mgr.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(mgr)
                ?: return "null"
            val req = r.javaClass.getMethod("isRequiresDistractionOptimization").invoke(r)
            val active = r.javaClass.getMethod("getActiveRestrictions").invoke(r)
            "requiresDO=" + req + " active=0x" + Integer.toHexString((active as? Int) ?: -1)
        } catch (e: Throwable) {
            "오류: " + (e.cause ?: e).javaClass.simpleName
        }
    }

    /**
     * UX 제한이 바뀔 때마다 콜백 (2026-09-05). 폴스타가 **언제** DO를 요구하는지(D 넣는 순간? 실제 이동?)
     * 실차에서 판정하기 위한 것. 리스너 인터페이스는 리플렉션 Proxy로 만든다.
     */
    fun registerUxListener(cb: (String) -> Unit): Boolean {
        return try {
            val c = car ?: run { if (!connect()) return false; car ?: return false }
            val mgr = c.javaClass.getMethod("getCarManager", String::class.java)
                .invoke(c, "uxrestriction") ?: return false
            val iface = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader, arrayOf(iface)
            ) { _, m, args ->
                if (m.name == "onUxRestrictionsChanged" && args != null && args.isNotEmpty()) {
                    val r = args[0]
                    val req = try { r.javaClass.getMethod("isRequiresDistractionOptimization").invoke(r) } catch (e: Throwable) { "?" }
                    val act = try { r.javaClass.getMethod("getActiveRestrictions").invoke(r) } catch (e: Throwable) { -1 }
                    cb("requiresDO=" + req + " active=0x" + Integer.toHexString((act as? Int) ?: -1))
                }
                null
            }
            mgr.javaClass.getMethod("registerListener", iface).invoke(mgr, proxy)
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ---------- 실시간 변경 알림 구독 (2026-09-13, 사용자: "실시간 반응이 느리다 / 부하 없는 쪽으로") ----------
    // 2초 폴링 대신 CarPropertyManager.registerCallback으로 값이 바뀔 때만 받는다. 화면이 보일 때만 켜고(DashboardPage.onShow) 사라지면 끈다.
    // 기록(LoggerService 2초 tick)은 그대로 — 이건 화면 표시만 빠르게 하는 경로. 인터페이스는 리플렉션 Proxy.
    private var liveCallback: Any? = null
    private val LIVE_PROPS = arrayOf("PERF_VEHICLE_SPEED", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "EV_BATTERY_LEVEL")

    /**
     * cb(name, value): 속도 km/h · 전력 kW(+충전/−소비) · 배터리 Wh. 콜백은 차량 서비스 스레드에서 온다.
     * 반환: "ok:[등록된 속성]" 또는 실패 사유 — svclog로 실차 판정용.
     */
    fun subscribeLive(cb: (String, Float) -> Unit): String {
        unsubscribeLive()
        val mgr = propMgr ?: run { if (!connect()) return "차량 연결 없음"; propMgr ?: return "CarPropertyManager 없음" }
        return try {
            val iface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val idToName = HashMap<Int, String>()
            for (n in LIVE_PROPS) { val id = propId(n); if (id != 0) idToName[id] = n }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, m, args ->
                // registerCallback이 콜백을 맵 키로 쓰므로 Object 메서드를 제대로 답해야 한다 (null 반환 → hashCode 언박싱 NPE, 에뮬레이터 확인)
                when (m.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                    "equals" -> return@newProxyInstance (args != null && args.isNotEmpty() && args[0] === p)
                    "toString" -> return@newProxyInstance "P4LiveCallback@" + Integer.toHexString(System.identityHashCode(p))
                }
                if (m.name == "onChangeEvent" && args != null && args.isNotEmpty()) {
                    val v = args[0]
                    try {
                        val id = v.javaClass.getMethod("getPropertyId").invoke(v) as Int
                        val raw = v.javaClass.getMethod("getValue").invoke(v)
                        val name = idToName[id]
                        val f = (raw as? Number)?.toFloat()
                        if (name != null && f != null) {
                            diag[name] = "OK(live): $f"
                            cb(name, when (name) {
                                "PERF_VEHICLE_SPEED" -> f * 3.6f
                                "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" -> f / 1_000_000f
                                else -> f
                            })
                        }
                    } catch (e: Throwable) { /* 한 이벤트 실패는 무시 */ }
                }
                null
            }
            val reg = mgr.javaClass.getMethod("registerCallback", iface, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val ok = ArrayList<String>()
            val fail = ArrayList<String>()
            for ((id, n) in idToName) {
                try {
                    // SENSOR_RATE_UI(5Hz)면 충분 — 값이 바뀔 때만 오고 화면은 어차피 그 이상 빠르게 못 읽는다
                    val r = reg.invoke(mgr, proxy, id, 5f) as? Boolean
                    if (r != false) ok.add(n.substringAfterLast('_').lowercase()) else fail.add(n)
                } catch (e: Throwable) { fail.add(n + ":" + (e.cause ?: e).javaClass.simpleName) }
            }
            liveCallback = proxy
            if (ok.isEmpty()) "등록 실패 " + fail else "ok:" + ok + (if (fail.isEmpty()) "" else " 실패:" + fail)
        } catch (e: Throwable) {
            "구독 불가: " + e.javaClass.simpleName + " " + (e.message ?: "")
        }
    }

    fun unsubscribeLive() {
        val proxy = liveCallback ?: return
        liveCallback = null
        val mgr = propMgr ?: return
        try {
            val iface = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            mgr.javaClass.getMethod("unregisterCallback", iface).invoke(mgr, proxy)
        } catch (e: Throwable) { /* 이미 끊긴 경우 */ }
    }

    /** 기어 (GEAR_SELECTION 0x11400400): 1=N 2=R 4=P 8=D. 실패 시 null */
    fun gear(): Int? = try {
        readInt("GEAR_SELECTION")
    } catch (e: Throwable) { null }

    /** 서비스 시작 시 한 줄로 남길 요약 (실차 원격 진단용) */
    fun probeSummary(): String {
        speedKmh(); speedDisplayKmh(); odometerKm(); wheelTicks(); wheelTickMicrometers()
        outsideTempC(); chargeTimeRemainingSec(); chargePercentLimit(); tirePressure()
        fun d(k: String) = (diag[k] ?: "안 읽음").take(70)
        return "speed=" + d("PERF_VEHICLE_SPEED") +
            " | speedDisp=" + d("PERF_VEHICLE_SPEED_DISPLAY") +
            " | odo=" + d("PERF_ODOMETER") +
            " | wheelTick=" + d("WHEEL_TICK") +
            " | tickCfg=" + d("WHEEL_TICK_CONFIG") +
            " | outTemp=" + d("ENV_OUTSIDE_TEMPERATURE") +
            " | chgTime=" + d("EV_CHARGE_TIME_REMAINING") +
            " | chgLimit=" + d("EV_CHARGE_PERCENT_LIMIT") +
            " | tire=" + d("TIRE_PRESSURE") +
            " | do=" + mainActivityDistractionOptimized() +
            " | ux=" + uxRestrictionState()
    }
}
