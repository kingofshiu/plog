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
    val speedKmh: Float? = null,         // 표시용 융합 속도 (차량·GPS·바퀴 중 최대, km/h — 2026-09-13부터. 이전엔 차량 원값)
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
    val todayEff: Double? = null,        // km/kWh
    // 아래 3개는 차량이 주면 표시하고 없으면 숨긴다 (2026-09-01)
    val tripRegenKwh: Double = 0.0,      // 이번 주행에서 회생으로 회수한 kWh (2026-09-05)
    val todayRegenKwh: Double = 0.0,     // 오늘 저장된 주행의 회생 합계
    // 이번 주행 전체의 소비/회생 전력 평균·최고 kW (2026-09-06, 주행 탭 계기용. 주행이 끝나도 다음 주행 시작까지 유지)
    val tripConsumeAvgKw: Float = 0f,
    val tripConsumePeakKw: Float = 0f,
    val tripRegenAvgKw: Float = 0f,      // 회생 중(>0.05kW)인 샘플만의 평균
    val tripRegenPeakKw: Float = 0f,
    // 회생 사이클 (2026-09-08, 사용자 결정 "마지막 충전 이후" 기준): 저장된 주행 합계 + 진행 중인 주행
    val cycleSinceTs: Long? = null,      // 마지막 충전 종료 시각 (없으면 전체 기간)
    val cycleConsumeKwh: Double = 0.0,   // 충전 이후 소비 kWh (trip.energy_kwh 합 + 이번 주행 진행분)
    val cycleRegenKwh: Double = 0.0,     // 충전 이후 회생 kWh
    val tripEnergyKwh: Double = 0.0,     // 이번 주행 소비 kWh (진행 중, Wh 델타)
    val cycleKm: Double = 0.0,           // 충전 이후 달린 km (저장된 주행 합 + 진행 중 주행, 2026-09-15 충전 사이클 패널)
    val cycleSocEnd: Float? = null,      // 마지막 충전이 끝났을 때 배터리 % (없으면 null) → "N% 사용" 계산용
    val usualRegenRatioPct: Float = 0f,  // 평소 회수 비율 % (최근 30일 주행 합계 기준)
    val outsideTempC: Float? = null,     // 외기 온도
    val chargeRemainMin: Int? = null,    // 충전 완료까지 남은 분
    val chargeLimitPct: Float? = null    // 사용자가 차에서 설정한 충전 목표 %
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
    val synced: Boolean = false,
    val regenKwh: Double? = null,  // 회생제동 회수 에너지 (v5, 2026-09-05)
    val energyDeltaKwh: Double? = null, // 배터리 Wh 델타 원값 (v7, 2026-09-17 — 1008Wh 계단)
    val energyIntKwh: Double? = null,   // 전력 순적분 원값 (v7). energyKwh = 적분 × 보정 계수
    val startPlace: String? = null, // 출발 동네 이름 (v6, 2026-09-06, PlaceNames 역지오코딩)
    val endPlace: String? = null
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
    val kind: String? = null,      // 내 충전기: "home" 집 / "work" 회사 / null 외부 (v8, 2026-09-24)
    val place: String? = null,     // 충전 위치 동네 이름 "광산구 소촌동" (v9, 2026-09-25 — 주행 목록의 출발→도착처럼)
    val synced: Boolean = false
)

/** 충전소 프로필: 한 번 확인된 충전소는 위치로 기억 → 다음부터 API 없이 단가 자동 적용 */
data class StationProfile(
    val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val name: String,
    val operator: String?,    // 운영사 (환경공단 API busiNm)
    val rate: Double?,        // 사용자 확정 단가(원/kWh) — null이면 미확정(로밍 단가표 적용)
    val outputs: String? = null, // 충전기 정격 출력 목록 (kW, 쉼표 구분. 예 "50,100,200")
    val kind: String? = null,    // 내 충전기: "home"/"work" — 위치만으로 인식, API·요금표 조회 없음 (v8, 2026-09-24)
    val tariff: String? = null,  // 내 충전기 요금 방식: "flat" 정액(rate) / "tou" 한전 시간대(HomeTariff)
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
    val socPct: Float?,
    val photoTs: Long? = null   // 주차 사진 촬영 시각 (없으면 사진 없음)
)
