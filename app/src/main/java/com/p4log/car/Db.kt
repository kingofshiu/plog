package com.p4log.car

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 로컬 저장소 (SQLite, 외부 라이브러리 없음).
 * v3: parking.photo_ts 주차 사진 (2026-08-10)
 * v4: station_profile.outputs 충전기 정격 출력 목록 — 로밍 단가 구간 판정용 (2026-08-18)
 * v5: trip.regen_kwh 회생제동으로 회수한 에너지 (2026-09-05, 주행 탭 표시용. 서버엔 아직 안 올림)
 * v6: trip.start_place / end_place 출발·도착 동네 이름 (2026-09-06, 주행 기록 목록용. 서버엔 안 올림)
 */
class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, "p4log.db", null, 9) {

    /**
     * 매번 열릴 때 인덱스 보장 (2026-09-08, 기록이 쌓여도 기간 조회가 느려지지 않게).
     * CREATE INDEX IF NOT EXISTS 라 버전을 올릴 필요가 없다. trip(start_ts): 기간 목록·합계, charge(end_ts): 마지막 충전 시각.
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_start ON trip(start_ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_charge_end ON charge(end_ts)")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE trip (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL," +
                "distance_m REAL NOT NULL," +
                "energy_kwh REAL," +
                "soc_start REAL," +
                "soc_end REAL," +
                "avg_kmh REAL NOT NULL DEFAULT 0," +
                "max_kmh REAL NOT NULL DEFAULT 0," +
                "start_lat REAL, start_lon REAL," +
                "end_lat REAL, end_lon REAL," +
                "polyline TEXT NOT NULL DEFAULT '[]'," +
                "synced INTEGER NOT NULL DEFAULT 0," +
                "regen_kwh REAL," +
                "start_place TEXT, end_place TEXT," +
                "energy_delta_kwh REAL, energy_int_kwh REAL)"
        )
        db.execSQL(
            "CREATE TABLE charge (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL," +
                "kwh REAL NOT NULL," +
                "cost REAL NOT NULL," +
                "soc_start REAL," +
                "soc_end REAL," +
                "max_kw REAL NOT NULL DEFAULT 0," +
                "type TEXT NOT NULL DEFAULT 'AC'," +
                "profile TEXT NOT NULL DEFAULT '[]'," +
                "station TEXT," +
                "st_lat REAL, st_lon REAL," +
                "kind TEXT," +
                "place TEXT," +
                "synced INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(CREATE_STATION_PROFILE)
        db.execSQL(
            "CREATE TABLE consumable (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "cycle_km INTEGER NOT NULL DEFAULT 0," +
                "cycle_months INTEGER NOT NULL DEFAULT 0," +
                "base_km REAL NOT NULL DEFAULT 0," +
                "base_ts INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE parking (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                "ts INTEGER NOT NULL," +
                "lat REAL NOT NULL," +
                "lon REAL NOT NULL," +
                "soc REAL," +
                "photo_ts INTEGER)"
        )
        // 기본 소모품 (주기는 설정에서 수정 가능)
        val now = System.currentTimeMillis()
        insertConsumableInternal(db, "타이어", 45000, 0, 0.0, now)
        insertConsumableInternal(db, "브레이크 패드", 60000, 0, 0.0, now)
        insertConsumableInternal(db, "에어컨 필터", 15000, 12, 0.0, now)
        insertConsumableInternal(db, "와이퍼", 0, 12, 0.0, now)
        insertConsumableInternal(db, "감속기 오일", 60000, 0, 0.0, now)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2: 충전소 자동 인식 기능 (기존 데이터 보존)
            db.execSQL("ALTER TABLE charge ADD COLUMN station TEXT")
            db.execSQL("ALTER TABLE charge ADD COLUMN st_lat REAL")
            db.execSQL("ALTER TABLE charge ADD COLUMN st_lon REAL")
            db.execSQL(CREATE_STATION_PROFILE)
        }
        if (oldVersion < 3) {
            // v2 → v3: 주차 사진 촬영 시각
            db.execSQL("ALTER TABLE parking ADD COLUMN photo_ts INTEGER")
        }
        // v3 → v4: 충전기 정격 출력 목록.
        // oldVersion이 1이면 위에서 station_profile을 새 스키마로 막 만들었으므로 ALTER 금지(중복 컬럼)
        if (oldVersion in 2..3) {
            db.execSQL("ALTER TABLE station_profile ADD COLUMN outputs TEXT")
        }
        if (oldVersion < 5) {
            // v4 → v5: 회생제동 회수 에너지 (기존 주행은 NULL)
            db.execSQL("ALTER TABLE trip ADD COLUMN regen_kwh REAL")
        }
        if (oldVersion < 6) {
            // v5 → v6: 출발·도착 동네 이름 (기존 주행은 NULL → 목록에서 볼 때 채운다)
            db.execSQL("ALTER TABLE trip ADD COLUMN start_place TEXT")
            db.execSQL("ALTER TABLE trip ADD COLUMN end_place TEXT")
        }
        if (oldVersion < 7) {
            // v6 → v7: 에너지 원값 2종 (델타·적분). energy_kwh는 보정한 적분으로 바뀜 (2026-09-17). 기존 주행은 NULL
            db.execSQL("ALTER TABLE trip ADD COLUMN energy_delta_kwh REAL")
            db.execSQL("ALTER TABLE trip ADD COLUMN energy_int_kwh REAL")
        }
        if (oldVersion < 8) {
            // v7 → v8: 내 충전기(집·회사) — charge.kind('home'/'work'), station_profile.kind + tariff('flat'/'tou') (2026-09-24).
            // oldVersion 1이면 station_profile은 위에서 새 스키마로 만들어졌으니 charge만 ALTER
            db.execSQL("ALTER TABLE charge ADD COLUMN kind TEXT")
            if (oldVersion >= 2) {
                db.execSQL("ALTER TABLE station_profile ADD COLUMN kind TEXT")
                db.execSQL("ALTER TABLE station_profile ADD COLUMN tariff TEXT")
            }
        }
        if (oldVersion < 9) {
            // v8 -> v9: 충전 위치 동네 이름 (주행의 start_place 처럼, 2026-09-25). 기존 충전은 NULL -> 목록에서 볼 때 채운다
            db.execSQL("ALTER TABLE charge ADD COLUMN place TEXT")
        }
    }

    /** 에너지 보정용 합계: [Σ배터리 델타, Σ전력 순적분, 건수] — 최근 60건 중 둘 다 있는 주행 (v7) */
    fun energyCalibration(): DoubleArray {
        val c = readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(energy_delta_kwh),0), IFNULL(SUM(energy_int_kwh),0), COUNT(*) FROM (" +
                "SELECT energy_delta_kwh, energy_int_kwh FROM trip " +
                "WHERE energy_delta_kwh IS NOT NULL AND energy_int_kwh IS NOT NULL AND energy_int_kwh > 0.3 " +
                "ORDER BY start_ts DESC LIMIT 60)", null)
        c.use { return if (it.moveToFirst()) doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2)) else doubleArrayOf(0.0, 0.0, 0.0) }
    }

    private fun insertConsumableInternal(
        db: SQLiteDatabase, name: String, cycleKm: Long, cycleMonths: Long, baseKm: Double, baseTs: Long
    ) {
        val cv = ContentValues()
        cv.put("name", name)
        cv.put("cycle_km", cycleKm)
        cv.put("cycle_months", cycleMonths)
        cv.put("base_km", baseKm)
        cv.put("base_ts", baseTs)
        db.insert("consumable", null, cv)
    }

    // ---------- Trip ----------

    fun insertTrip(t: Trip): Long {
        val cv = ContentValues()
        cv.put("start_ts", t.startTs)
        cv.put("end_ts", t.endTs)
        cv.put("distance_m", t.distanceM)
        if (t.energyKwh != null) cv.put("energy_kwh", t.energyKwh) else cv.putNull("energy_kwh")
        if (t.socStart != null) cv.put("soc_start", t.socStart) else cv.putNull("soc_start")
        if (t.socEnd != null) cv.put("soc_end", t.socEnd) else cv.putNull("soc_end")
        cv.put("avg_kmh", t.avgKmh)
        cv.put("max_kmh", t.maxKmh)
        if (t.startLat != null) cv.put("start_lat", t.startLat) else cv.putNull("start_lat")
        if (t.startLon != null) cv.put("start_lon", t.startLon) else cv.putNull("start_lon")
        if (t.endLat != null) cv.put("end_lat", t.endLat) else cv.putNull("end_lat")
        if (t.endLon != null) cv.put("end_lon", t.endLon) else cv.putNull("end_lon")
        cv.put("polyline", t.polyline)
        cv.put("synced", 0)
        if (t.regenKwh != null) cv.put("regen_kwh", t.regenKwh) else cv.putNull("regen_kwh")
        if (t.startPlace != null) cv.put("start_place", t.startPlace)
        if (t.endPlace != null) cv.put("end_place", t.endPlace)
        if (t.energyDeltaKwh != null) cv.put("energy_delta_kwh", t.energyDeltaKwh)
        if (t.energyIntKwh != null) cv.put("energy_int_kwh", t.energyIntKwh)
        return writableDatabase.insert("trip", null, cv)
    }

    /** 출발·도착 동네 이름 저장 (PlaceNames가 백그라운드에서 채운다, v6) */
    fun setTripPlaces(id: Long, start: String?, end: String?) {
        val cv = ContentValues()
        if (start != null) cv.put("start_place", start)
        if (end != null) cv.put("end_place", end)
        if (cv.size() == 0) return
        writableDatabase.update("trip", cv, "id = ?", arrayOf(id.toString()))
    }

    private fun readTrip(c: Cursor): Trip = Trip(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        startTs = c.getLong(c.getColumnIndexOrThrow("start_ts")),
        endTs = c.getLong(c.getColumnIndexOrThrow("end_ts")),
        distanceM = c.getDouble(c.getColumnIndexOrThrow("distance_m")),
        energyKwh = if (c.isNull(c.getColumnIndexOrThrow("energy_kwh"))) null
                    else c.getDouble(c.getColumnIndexOrThrow("energy_kwh")),
        socStart = if (c.isNull(c.getColumnIndexOrThrow("soc_start"))) null
                   else c.getFloat(c.getColumnIndexOrThrow("soc_start")),
        socEnd = if (c.isNull(c.getColumnIndexOrThrow("soc_end"))) null
                 else c.getFloat(c.getColumnIndexOrThrow("soc_end")),
        avgKmh = c.getDouble(c.getColumnIndexOrThrow("avg_kmh")),
        maxKmh = c.getDouble(c.getColumnIndexOrThrow("max_kmh")),
        startLat = if (c.isNull(c.getColumnIndexOrThrow("start_lat"))) null
                   else c.getDouble(c.getColumnIndexOrThrow("start_lat")),
        startLon = if (c.isNull(c.getColumnIndexOrThrow("start_lon"))) null
                   else c.getDouble(c.getColumnIndexOrThrow("start_lon")),
        endLat = if (c.isNull(c.getColumnIndexOrThrow("end_lat"))) null
                 else c.getDouble(c.getColumnIndexOrThrow("end_lat")),
        endLon = if (c.isNull(c.getColumnIndexOrThrow("end_lon"))) null
                 else c.getDouble(c.getColumnIndexOrThrow("end_lon")),
        polyline = c.getString(c.getColumnIndexOrThrow("polyline")),
        synced = c.getInt(c.getColumnIndexOrThrow("synced")) == 1,
        regenKwh = c.getColumnIndex("regen_kwh").let { i ->
            if (i < 0 || c.isNull(i)) null else c.getDouble(i)
        },
        startPlace = c.getColumnIndex("start_place").let { i -> if (i < 0 || c.isNull(i)) null else c.getString(i) },
        endPlace = c.getColumnIndex("end_place").let { i -> if (i < 0 || c.isNull(i)) null else c.getString(i) },
        energyDeltaKwh = c.getColumnIndex("energy_delta_kwh").let { i -> if (i < 0 || c.isNull(i)) null else c.getDouble(i) },
        energyIntKwh = c.getColumnIndex("energy_int_kwh").let { i -> if (i < 0 || c.isNull(i)) null else c.getDouble(i) }
    )

    /** 최근 주행 N건 (최신순) — 주행 탭의 최근 목록 */
    fun recentTrips(limit: Int): List<Trip> {
        val list = ArrayList<Trip>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM trip ORDER BY start_ts DESC LIMIT ?", arrayOf(limit.toString())
        )
        c.use { while (it.moveToNext()) list.add(readTrip(it)) }
        return list
    }

    /** 기간 내 주행 목록 (최신순) */
    /**
     * 목록용 가벼운 조회 (2026-09-08): polyline(경로, 주행당 수십~수백 KB)을 빼고 읽는다.
     * 주행 기록 화면이 한 달·1년·전체 목록을 열 때 쓰고, 경로는 선택한 주행만 tripById로 따로 가져온다.
     * 반환된 Trip의 polyline은 "" (빈 문자열)이다.
     */
    fun tripsBetweenLite(fromTs: Long, toTs: Long): List<Trip> {
        val list = ArrayList<Trip>()
        val c = readableDatabase.rawQuery(
            "SELECT id, start_ts, end_ts, distance_m, energy_kwh, soc_start, soc_end, avg_kmh, max_kmh, " +
                "start_lat, start_lon, end_lat, end_lon, '' AS polyline, synced, regen_kwh, start_place, end_place " +
                "FROM trip WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        )
        c.use { while (it.moveToNext()) list.add(readTrip(it)) }
        return list
    }

    /** 경로만 (선택한 주행의 지도용) */
    fun tripPolyline(id: Long): String? {
        val c = readableDatabase.rawQuery("SELECT polyline FROM trip WHERE id = ?", arrayOf(id.toString()))
        c.use { return if (it.moveToFirst()) it.getString(0) else null }
    }

    fun tripsBetween(fromTs: Long, toTs: Long): List<Trip> {
        val list = ArrayList<Trip>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM trip WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        )
        c.use { while (it.moveToNext()) list.add(readTrip(it)) }
        return list
    }

    fun tripById(id: Long): Trip? {
        val c = readableDatabase.rawQuery("SELECT * FROM trip WHERE id = ?", arrayOf(id.toString()))
        c.use { return if (it.moveToFirst()) readTrip(it) else null }
    }

    fun unsyncedTrips(): List<Trip> {
        val list = ArrayList<Trip>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM trip WHERE synced = 0 ORDER BY start_ts ASC LIMIT 50", null
        )
        c.use { while (it.moveToNext()) list.add(readTrip(it)) }
        return list
    }

    fun markTripSynced(id: Long) {
        val cv = ContentValues(); cv.put("synced", 1)
        writableDatabase.update("trip", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 최근 주행의 전비 목록 (시간순, 전비 계산 가능한 것만): Pair(주행 id, km/kWh) */
    fun recentTripEffs(limit: Int): List<Pair<Long, Double>> {
        val c = readableDatabase.rawQuery(
            "SELECT id, distance_m, energy_kwh FROM trip " +
                "WHERE energy_kwh IS NOT NULL AND energy_kwh > 0.05 " +
                "ORDER BY start_ts DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val list = ArrayList<Pair<Long, Double>>()
        c.use {
            while (it.moveToNext()) {
                val km = it.getDouble(1) / 1000.0
                val kwh = it.getDouble(2)
                list.add(it.getLong(0) to km / kwh)
            }
        }
        return list.reversed()
    }

    /** [fromTs, toTs) 주행 합계: [건수, 총거리m, 총에너지kWh, 회생kWh] */
    fun tripTotals(fromTs: Long, toTs: Long): DoubleArray {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM(distance_m),0), IFNULL(SUM(energy_kwh),0), IFNULL(SUM(regen_kwh),0) " +
                "FROM trip WHERE start_ts >= ? AND start_ts < ?",
            arrayOf(fromTs.toString(), toTs.toString())
        )
        c.use {
            return if (it.moveToFirst())
                doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3))
            else doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
    }

    /**
     * "평소 전비" 기준 합계 (2026-09-21, 사용자 결정 "0.3 kWh 미만 주행 제외"): [거리 m 합, 에너지 kWh 합].
     * 0.5~1 km 이동이 우연히 배터리 Wh 계단(1,008 Wh)을 넘어 1.008 kWh로 찍히면 전비 0.7 km/kWh짜리 기록이 되고,
     * 에너지 0인 짧은 이동은 거리만 더해진다. 이 필터는 후자만 걸러낸다(실차 30일 51건 중 4건 제외, 평소 4.69 → 4.66).
     * 1.008 kWh짜리 짧은 이동까지 빼려면 거리 필터(2 km)가 더 필요한데 사용자가 "이것만"이라 하여 넣지 않았다.
     */
    fun usualEffTotals(fromTs: Long): DoubleArray {
        val c = readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(distance_m),0), IFNULL(SUM(energy_kwh),0) FROM trip WHERE start_ts >= ? AND energy_kwh >= 0.3",
            arrayOf(fromTs.toString())
        )
        c.use { return if (it.moveToFirst()) doubleArrayOf(it.getDouble(0), it.getDouble(1)) else doubleArrayOf(0.0, 0.0) }
    }

    /** 마지막 충전이 끝났을 때의 배터리 % (없으면 null). 충전 사이클 "N% 사용" 계산용 (2026-09-15) */
    fun lastChargeSocEnd(): Float? {
        val c = readableDatabase.rawQuery("SELECT soc_end FROM charge ORDER BY end_ts DESC LIMIT 1", null)
        c.use { return if (it.moveToFirst() && !it.isNull(0)) it.getFloat(0) else null }
    }

    /** 최고 전비 주행 (minM 이상 거리, 에너지 있는 것만). 개인 최고 기록 (2026-09-15) */
    fun bestEffTrip(minM: Double): Trip? {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM trip WHERE energy_kwh IS NOT NULL AND energy_kwh > 0.3 AND distance_m >= ? " +
                "ORDER BY (distance_m / energy_kwh) DESC LIMIT 1", arrayOf(minM.toString()))
        c.use { return if (it.moveToFirst()) readTrip(it) else null }
    }

    /** 최장 주행 (2026-09-15) */
    fun longestTrip(): Trip? {
        val c = readableDatabase.rawQuery("SELECT * FROM trip ORDER BY distance_m DESC LIMIT 1", null)
        c.use { return if (it.moveToFirst()) readTrip(it) else null }
    }

    /** 마지막 충전이 끝난 시각 (없으면 null). 회생 "마지막 충전 이후" 사이클의 기준점 (2026-09-08) */
    fun lastChargeEndTs(): Long? {
        val c = readableDatabase.rawQuery("SELECT MAX(end_ts) FROM charge", null)
        c.use { return if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    // ---------- Charge ----------

    fun insertCharge(s: ChargeSession): Long {
        val cv = ContentValues()
        cv.put("start_ts", s.startTs)
        cv.put("end_ts", s.endTs)
        cv.put("kwh", s.kwh)
        cv.put("cost", s.cost)
        if (s.socStart != null) cv.put("soc_start", s.socStart) else cv.putNull("soc_start")
        if (s.socEnd != null) cv.put("soc_end", s.socEnd) else cv.putNull("soc_end")
        cv.put("max_kw", s.maxKw)
        cv.put("type", s.type)
        cv.put("profile", s.profile)
        if (s.station != null) cv.put("station", s.station) else cv.putNull("station")
        if (s.stLat != null) cv.put("st_lat", s.stLat) else cv.putNull("st_lat")
        if (s.stLon != null) cv.put("st_lon", s.stLon) else cv.putNull("st_lon")
        if (s.kind != null) cv.put("kind", s.kind) else cv.putNull("kind")
        if (s.place != null) cv.put("place", s.place) else cv.putNull("place")
        cv.put("synced", 0)
        return writableDatabase.insert("charge", null, cv)
    }

    private fun readCharge(c: Cursor): ChargeSession = ChargeSession(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        startTs = c.getLong(c.getColumnIndexOrThrow("start_ts")),
        endTs = c.getLong(c.getColumnIndexOrThrow("end_ts")),
        kwh = c.getDouble(c.getColumnIndexOrThrow("kwh")),
        cost = c.getDouble(c.getColumnIndexOrThrow("cost")),
        socStart = if (c.isNull(c.getColumnIndexOrThrow("soc_start"))) null
                   else c.getFloat(c.getColumnIndexOrThrow("soc_start")),
        socEnd = if (c.isNull(c.getColumnIndexOrThrow("soc_end"))) null
                 else c.getFloat(c.getColumnIndexOrThrow("soc_end")),
        maxKw = c.getDouble(c.getColumnIndexOrThrow("max_kw")),
        type = c.getString(c.getColumnIndexOrThrow("type")),
        profile = c.getString(c.getColumnIndexOrThrow("profile")),
        station = if (c.isNull(c.getColumnIndexOrThrow("station"))) null
                  else c.getString(c.getColumnIndexOrThrow("station")),
        stLat = if (c.isNull(c.getColumnIndexOrThrow("st_lat"))) null
                else c.getDouble(c.getColumnIndexOrThrow("st_lat")),
        stLon = if (c.isNull(c.getColumnIndexOrThrow("st_lon"))) null
                else c.getDouble(c.getColumnIndexOrThrow("st_lon")),
        kind = if (c.isNull(c.getColumnIndexOrThrow("kind"))) null else c.getString(c.getColumnIndexOrThrow("kind")),
        place = if (c.isNull(c.getColumnIndexOrThrow("place"))) null else c.getString(c.getColumnIndexOrThrow("place")),
        synced = c.getInt(c.getColumnIndexOrThrow("synced")) == 1
    )

    fun chargeById(id: Long): ChargeSession? {
        val c = readableDatabase.rawQuery("SELECT * FROM charge WHERE id = ?", arrayOf(id.toString()))
        c.use { return if (it.moveToFirst()) readCharge(it) else null }
    }

    fun chargesBetween(fromTs: Long, toTs: Long): List<ChargeSession> {
        val list = ArrayList<ChargeSession>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM charge WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts DESC",
            arrayOf(fromTs.toString(), toTs.toString())
        )
        c.use { while (it.moveToNext()) list.add(readCharge(it)) }
        return list
    }

    fun unsyncedCharges(): List<ChargeSession> {
        val list = ArrayList<ChargeSession>()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM charge WHERE synced = 0 ORDER BY start_ts ASC LIMIT 50", null
        )
        c.use { while (it.moveToNext()) list.add(readCharge(it)) }
        return list
    }

    /** 위치 있는 충전을 전부 다시 올리게 표시 (2026-09-22 서버 v7 st_lat/st_lon 소급). 돌려주는 값 = 건수 */
    fun unsyncChargesWithLocation(): Int {
        val cv = ContentValues(); cv.put("synced", 0)
        return writableDatabase.update("charge", cv, "st_lat IS NOT NULL", null)
    }

    fun markChargeSynced(id: Long) {
        val cv = ContentValues(); cv.put("synced", 1)
        writableDatabase.update("charge", cv, "id = ?", arrayOf(id.toString()))
    }

    /** [fromTs, toTs) 충전 합계: [건수, 총kWh, 총비용] */
    fun chargeTotals(fromTs: Long, toTs: Long): DoubleArray {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM(kwh),0), IFNULL(SUM(cost),0) " +
                "FROM charge WHERE start_ts >= ? AND start_ts < ?",
            arrayOf(fromTs.toString(), toTs.toString())
        )
        c.use {
            return if (it.moveToFirst()) doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2))
            else doubleArrayOf(0.0, 0.0, 0.0)
        }
    }

    // ---------- Consumable ----------

    fun consumables(): List<Consumable> {
        val list = ArrayList<Consumable>()
        val c = readableDatabase.rawQuery("SELECT * FROM consumable ORDER BY id ASC", null)
        c.use {
            while (it.moveToNext()) {
                list.add(
                    Consumable(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        cycleKm = it.getLong(it.getColumnIndexOrThrow("cycle_km")),
                        cycleMonths = it.getLong(it.getColumnIndexOrThrow("cycle_months")),
                        baseKm = it.getDouble(it.getColumnIndexOrThrow("base_km")),
                        baseTs = it.getLong(it.getColumnIndexOrThrow("base_ts"))
                    )
                )
            }
        }
        return list
    }

    /** 교체 처리: 기준점을 현재 누적km/현재 시각으로 리셋 */
    fun resetConsumable(id: Long, currentTotalKm: Double) {
        val cv = ContentValues()
        cv.put("base_km", currentTotalKm)
        cv.put("base_ts", System.currentTimeMillis())
        writableDatabase.update("consumable", cv, "id = ?", arrayOf(id.toString()))
    }

    /** '이미 사용한 km' 수동 보정: base_km = 현재누적 - 사용량 */
    fun setConsumableUsedKm(id: Long, currentTotalKm: Double, usedKm: Double) {
        val cv = ContentValues()
        cv.put("base_km", currentTotalKm - usedKm)
        writableDatabase.update("consumable", cv, "id = ?", arrayOf(id.toString()))
    }

    fun updateConsumableCycle(id: Long, cycleKm: Long, cycleMonths: Long) {
        val cv = ContentValues()
        cv.put("cycle_km", cycleKm)
        cv.put("cycle_months", cycleMonths)
        writableDatabase.update("consumable", cv, "id = ?", arrayOf(id.toString()))
    }

    // ---------- 충전소 프로필 ----------

    private fun readProfile(c: Cursor): StationProfile = StationProfile(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
        lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        operator = if (c.isNull(c.getColumnIndexOrThrow("operator"))) null
                   else c.getString(c.getColumnIndexOrThrow("operator")),
        rate = if (c.isNull(c.getColumnIndexOrThrow("rate"))) null
               else c.getDouble(c.getColumnIndexOrThrow("rate")),
        outputs = if (c.isNull(c.getColumnIndexOrThrow("outputs"))) null
                  else c.getString(c.getColumnIndexOrThrow("outputs")),
        kind = if (c.isNull(c.getColumnIndexOrThrow("kind"))) null else c.getString(c.getColumnIndexOrThrow("kind")),
        tariff = if (c.isNull(c.getColumnIndexOrThrow("tariff"))) null else c.getString(c.getColumnIndexOrThrow("tariff")),
        updatedTs = c.getLong(c.getColumnIndexOrThrow("updated_ts"))
    )

    /** 반경 radiusM 내에서 가장 가까운 충전소 프로필 (없으면 null) */
    fun nearestStationProfile(lat: Double, lon: Double, radiusM: Double): StationProfile? {
        val c = readableDatabase.rawQuery("SELECT * FROM station_profile", null)
        var best: StationProfile? = null
        var bestD = radiusM
        val out = FloatArray(1)
        c.use {
            while (it.moveToNext()) {
                val p = readProfile(it)
                android.location.Location.distanceBetween(lat, lon, p.lat, p.lon, out)
                if (out[0] <= bestD) { bestD = out[0].toDouble(); best = p }
            }
        }
        return best
    }

    /** 60m 내 기존 프로필이 있으면 갱신, 없으면 생성. null로 넘긴 항목은 기존 값을 유지한다 */
    fun upsertStationProfile(
        lat: Double, lon: Double, name: String, operator: String?, rate: Double?,
        outputs: String? = null, kind: String? = null, tariff: String? = null
    ) {
        val existing = nearestStationProfile(lat, lon, 60.0)
        val cv = ContentValues()
        cv.put("name", name)
        if (operator != null) cv.put("operator", operator)
        if (rate != null) cv.put("rate", rate)
        if (outputs != null) cv.put("outputs", outputs)
        if (kind != null) { cv.put("kind", kind); cv.put("tariff", tariff ?: "flat") }   // 내 충전기 (2026-09-24)
        cv.put("updated_ts", System.currentTimeMillis())
        if (existing != null) {
            writableDatabase.update("station_profile", cv, "id = ?", arrayOf(existing.id.toString()))
        } else {
            cv.put("lat", lat)
            cv.put("lon", lon)
            writableDatabase.insert("station_profile", null, cv)
        }
    }

    /** 사용자가 고른 충전소를 충전 기록에 기록 (이름 + 충전소 좌표). 재업로드 대상 (2026-09-08) */
    /** 어떤 자리(반경 radiusM) 안에서 한 충전 전부 — 집·회사로 지정할 때 기존 기록에도 적용 (2026-09-24) */
    fun chargesNear(lat: Double, lon: Double, radiusM: Double): List<ChargeSession> {
        val out = ArrayList<ChargeSession>()
        val d = FloatArray(1)
        val c = readableDatabase.rawQuery("SELECT * FROM charge WHERE st_lat IS NOT NULL AND st_lon IS NOT NULL", null)
        c.use {
            while (it.moveToNext()) {
                val s = readCharge(it)
                android.location.Location.distanceBetween(lat, lon, s.stLat!!, s.stLon!!, d)
                if (d[0] <= radiusM) out.add(s)
            }
        }
        return out
    }

    /** 내 충전기 지정: 종류·이름·요금을 바꾸고 재업로드 대상으로 (2026-09-24) */
    fun setChargeKind(id: Long, kind: String, station: String, cost: Double) {
        val cv = ContentValues()
        cv.put("kind", kind); cv.put("station", station); cv.put("cost", Math.round(cost).toDouble()); cv.put("synced", 0)
        writableDatabase.update("charge", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 충전 위치 동네 이름 저장 + 재업로드 (2026-09-25) */
    fun setChargePlace(id: Long, place: String) {
        val cv = ContentValues(); cv.put("place", place); cv.put("synced", 0)
        writableDatabase.update("charge", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setChargeStation(id: Long, name: String, lat: Double, lon: Double) {
        val cv = ContentValues()
        cv.put("station", name); cv.put("st_lat", lat); cv.put("st_lon", lon); cv.put("synced", 0)
        writableDatabase.update("charge", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 폰(웹)에서 고친 요금·충전소를 반영 (2026-09-21 양방향). 서버가 원본이므로 재업로드 표시는 안 한다 */
    fun applyRemoteChargeEdit(id: Long, cost: Double, station: String?, kind: String? = null) {
        val cv = ContentValues()
        cv.put("cost", cost)
        if (station != null) cv.put("station", station)
        if (kind != null) cv.put("kind", kind)
        writableDatabase.update("charge", cv, "id = ?", arrayOf(id.toString()))
    }

    /** 충전 기록 단가 수정: 요금 재계산 + 재업로드 대상으로 표시 */
    fun updateChargeRate(id: Long, kwh: Double, rate: Double) {
        val cv = ContentValues()
        cv.put("cost", Math.round(kwh * rate).toDouble())
        cv.put("synced", 0)
        writableDatabase.update("charge", cv, "id = ?", arrayOf(id.toString()))
    }

    // ---------- Parking ----------

    fun saveParking(p: ParkingInfo) {
        val cv = ContentValues()
        cv.put("id", 1)
        cv.put("ts", p.ts)
        cv.put("lat", p.lat)
        cv.put("lon", p.lon)
        if (p.socPct != null) cv.put("soc", p.socPct) else cv.putNull("soc")
        if (p.photoTs != null) cv.put("photo_ts", p.photoTs) else cv.putNull("photo_ts")
        writableDatabase.insertWithOnConflict("parking", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 주차 사진 촬영 완료 시각 기록 (비동기 촬영이 끝난 뒤 호출).
     * @return 갱신된 행 수. 0이면 아직 주차 기록이 없어 사진을 붙일 곳이 없다는 뜻
     */
    fun setParkingPhotoTs(ts: Long): Int {
        val cv = ContentValues(); cv.put("photo_ts", ts)
        return writableDatabase.update("parking", cv, "id = 1", null)
    }

    fun parking(): ParkingInfo? {
        val c = readableDatabase.rawQuery("SELECT * FROM parking WHERE id = 1", null)
        c.use {
            return if (it.moveToFirst()) ParkingInfo(
                ts = it.getLong(it.getColumnIndexOrThrow("ts")),
                lat = it.getDouble(it.getColumnIndexOrThrow("lat")),
                lon = it.getDouble(it.getColumnIndexOrThrow("lon")),
                socPct = if (it.isNull(it.getColumnIndexOrThrow("soc"))) null
                         else it.getFloat(it.getColumnIndexOrThrow("soc")),
                photoTs = if (it.isNull(it.getColumnIndexOrThrow("photo_ts"))) null
                          else it.getLong(it.getColumnIndexOrThrow("photo_ts"))
            ) else null
        }
    }

    companion object {
        private const val CREATE_STATION_PROFILE =
            "CREATE TABLE station_profile (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "lat REAL NOT NULL," +
                "lon REAL NOT NULL," +
                "name TEXT NOT NULL," +
                "operator TEXT," +
                "rate REAL," +
                "outputs TEXT," +
                "kind TEXT, tariff TEXT," +
                "updated_ts INTEGER NOT NULL DEFAULT 0)"

        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }
}
