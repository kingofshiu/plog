package com.p4log.car

/** 실시간 상태 스냅샷 (서비스 → UI) */
data class StatusSnapshot(
    val ts: Long = 0L,
    val carConnected: Boolean = false,
    val batteryWh: Float? = null,        // EV_BATTERY_LEVEL (Wh)
    val capacityWh: Float? = null,       // INFO_EV_BATTERY_CAPACITY (Wh)
    val socPct: Float? = null,           // 계산된 %
    val chargeRateKw: Float? = null,     // +충전 / -방전 (kW)
    val rangeKm: Float? = null,          // RANGE_REMAINING (km)
    val speedKmh: Float? = null,         // 차량 속도 (km/h)
    val gpsSpeedKmh: Float? = null,
    val ignition: Int? = null,           // IGNITION_STATE (2=OFF 3=ACC 4=ON)
    val portConnected: Boolean? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val gpsFix: Boolean = false,
    val tripActive: Boolean = false,
    val tripKm: Double = 0.0,
    val tripMinutes: Long = 0L,
    val chargeActive: Boolean = false,
    val chargeKwh: Double = 0.0,
    val todayKm: Double = 0.0,
    val todayEff: Double? = null         // km/kWh
)

data class Trip(
    val id: Long = 0,
    val startTs: Long,
    val endTs: Long,
    val distanceM: Double,
    val energyKwh: Double?,   // 배터리 Wh 델타 기반 (없으면 null)
    val socStart: Float?,
    val socEnd: Float?,
    val avgKmh: Double,
    val maxKmh: Double,
    val startLat: Double?, val startLon: Double?,
    val endLat: Double?, val endLon: Double?,
    val polyline: String,     // JSON [[lat,lon],...]
    val synced: Boolean = false
) {
    val distanceKm: Double get() = distanceM / 1000.0
    val effKmPerKwh: Double? get() =
        if (energyKwh != null && energyKwh > 0.05) distanceKm / energyKwh else null
}

data class ChargeSession(
    val id: Long = 0,
    val startTs: Long,
    val endTs: Long,
    val kwh: Double,
    val cost: Double,
    val socStart: Float?,
    val socEnd: Float?,
    val maxKw: Double,
    val type: String,         // "AC" / "DC"
    val profile: String,      // JSON [[분,kW],...]
    val station: String? = null,   // 자동 식별된 충전소 이름
    val stLat: Double? = null,     // 충전 위치 (프로필 저장용, 로컬 전용)
    val stLon: Double? = null,
    val synced: Boolean = false
)

/** 충전소 프로필: 한 번 확인된 충전소는 위치로 기억 → 다음부터 API 없이 단가 자동 적용 */
data class StationProfile(
    val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val name: String,
    val operator: String?,    // 운영사 (환경공단 API busiNm)
    val rate: Double?,        // 사용자 확정 단가(원/kWh) — null이면 미확정
    val updatedTs: Long
)

data class Consumable(
    val id: Long = 0,
    val name: String,
    val cycleKm: Long,        // 0이면 km 기준 없음
    val cycleMonths: Long,    // 0이면 개월 기준 없음
    val baseKm: Double,       // 마지막 교체 시점의 누적주행(km)
    val baseTs: Long          // 마지막 교체 시각
)

data class ParkingInfo(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val socPct: Float?
)
