package com.p4log.car

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

/** 주행 기록 탭 (월별) */
class TripsPage(private val activity: Activity, root: View) : PageController {

    private val tvMonth: TextView = root.findViewById(R.id.trips_month)
    private val tvSummary: TextView = root.findViewById(R.id.trips_summary)
    private val listView: ListView = root.findViewById(R.id.trips_list)
    private val tvEmpty: TextView = root.findViewById(R.id.trips_empty)

    private val cal: Calendar = Calendar.getInstance()
    private var items: List<Trip> = emptyList()
    private val dayFmt = java.text.SimpleDateFormat("M.d (E) HH:mm", java.util.Locale.KOREA)

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: activity.layoutInflater.inflate(R.layout.row_trip, parent, false)
            val t = items[position]
            v.findViewById<TextView>(R.id.rt_when).text = dayFmt.format(java.util.Date(t.startTs))
            v.findViewById<TextView>(R.id.rt_km).text = Fmt.km(t.distanceKm)
            val effView = v.findViewById<TextView>(R.id.rt_eff)
            val eff = t.effKmPerKwh
            if (eff != null) {
                effView.visibility = View.VISIBLE
                effView.text = "전비 " + Fmt.eff(eff)
            } else {
                effView.visibility = View.GONE
            }
            v.findViewById<TextView>(R.id.rt_detail).text = buildString {
                append(Fmt.duration(t.startTs, t.endTs))
                if (t.socStart != null && t.socEnd != null) {
                    append("  ·  배터리 ")
                    append(String.format("%.0f→%.0f%%", t.socStart, t.socEnd))
                }
                append("  ·  최고 ")
                append(String.format("%.0f km/h", t.maxKmh))
            }
            return v
        }
    }

    init {
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val i = Intent(activity, TripDetailActivity::class.java)
            i.putExtra("trip_id", items[position].id)
            activity.startActivity(i)
        }
        root.findViewById<View>(R.id.trips_prev).setOnClickListener {
            cal.add(Calendar.MONTH, -1); reload()
        }
        root.findViewById<View>(R.id.trips_next).setOnClickListener {
            cal.add(Calendar.MONTH, 1); reload()
        }
    }

    override fun onShow() = reload()

    private fun reload() {
        val range = Fmt.monthRange(cal)
        val db = Db.get(activity)
        items = db.tripsBetween(range[0], range[1])
        adapter.notifyDataSetChanged()

        tvMonth.text = Fmt.monthTitle(cal)
        val totals = db.tripTotals(range[0], range[1])
        val km = totals[1] / 1000.0
        val kwh = totals[2]
        tvSummary.text = String.format(
            "%d건 · %s · 전비 %s",
            totals[0].toInt(), Fmt.km(km),
            if (kwh > 0.3) Fmt.eff(km / kwh) else "-"
        )
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
