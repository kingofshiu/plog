package com.p4log.car

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 표시용 포맷 유틸 */
object Fmt {
    private val loc = Locale.KOREA

    fun km(v: Double): String = String.format(loc, "%,.1f km", v)
    fun kmInt(v: Double): String = String.format(loc, "%,d km", Math.round(v))
    fun kwh(v: Double): String = String.format(loc, "%.1f kWh", v)
    fun kw(v: Double): String = String.format(loc, "%.1f kW", v)
    fun won(v: Double): String = String.format(loc, "%,d원", Math.round(v))
    fun eff(v: Double?): String = if (v == null) "-" else String.format(loc, "%.1f km/kWh", v)
    fun pct(v: Float?): String = if (v == null) "-" else String.format(loc, "%.0f%%", v)

    fun dateTime(ts: Long): String = SimpleDateFormat("M.d HH:mm", loc).format(Date(ts))
    fun time(ts: Long): String = SimpleDateFormat("HH:mm", loc).format(Date(ts))
    fun fullDateTime(ts: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", loc).format(Date(ts))
    fun monthTitle(cal: Calendar): String =
        SimpleDateFormat("yyyy년 M월", loc).format(Date(cal.timeInMillis))

    fun duration(fromTs: Long, toTs: Long): String {
        val min = (toTs - fromTs) / 60_000L
        return if (min < 60) "${min}분" else "${min / 60}시간 ${min % 60}분"
    }

    fun agoText(ts: Long): String {
        val min = (System.currentTimeMillis() - ts) / 60_000L
        return when {
            min < 1 -> "방금 전"
            min < 60 -> "${min}분 전"
            min < 60 * 24 -> "${min / 60}시간 전"
            else -> "${min / (60 * 24)}일 전"
        }
    }

    /** 해당 월의 [시작ts, 다음달 시작ts) */
    fun monthRange(cal: Calendar): LongArray {
        val c = cal.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return longArrayOf(from, c.timeInMillis)
    }
}
