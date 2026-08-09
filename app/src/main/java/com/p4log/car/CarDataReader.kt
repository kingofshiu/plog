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
            "IGNITION_STATE" to 289408009
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

    /** 진단: 전 속성 1회 읽기 시도 */
    fun probeAll() {
        batteryWh(); capacityWh(); chargeRateKw(); rangeKm()
        speedKmh(); ignitionState(); chargePortConnected(); odometerKm()
    }
}
