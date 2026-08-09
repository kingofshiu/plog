package com.p4log.car

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.util.Calendar

/** 충전 기록 탭 (월별) */
class ChargesPage(private val activity: Activity, root: View) : PageController {

    private val tvMonth: TextView = root.findViewById(R.id.charges_month)
    private val tvSummary: TextView = root.findViewById(R.id.charges_summary)
    private val listView: ListView = root.findViewById(R.id.charges_list)
    private val tvEmpty: TextView = root.findViewById(R.id.charges_empty)

    private val cal: Calendar = Calendar.getInstance()
    private var items: List<ChargeSession> = emptyList()
    private val dayFmt = java.text.SimpleDateFormat("M.d (E) HH:mm", java.util.Locale.KOREA)

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: activity.layoutInflater.inflate(R.layout.row_charge, parent, false)
            val s = items[position]
            v.findViewById<TextView>(R.id.rc_when).text = dayFmt.format(java.util.Date(s.startTs))
            v.findViewById<TextView>(R.id.rc_kwh).text = Fmt.kwh(s.kwh)
            v.findViewById<TextView>(R.id.rc_cost).text = Fmt.won(s.cost)
            val badge = v.findViewById<TextView>(R.id.rc_type)
            badge.text = if (s.type == "DC") "급속" else "완속"
            val typeColor =
                if (s.type == "DC") Color.parseColor("#FF7500") else Color.parseColor("#4DA3FF")
            badge.setTextColor(typeColor)
            badge.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (s.type == "DC") Color.parseColor("#43290F") else Color.parseColor("#152C45")
            )
            v.findViewById<TextView>(R.id.rc_detail).text = buildString {
                append(Fmt.duration(s.startTs, s.endTs))
                append("  ·  ")
                if (s.station != null) {
                    append(s.station)
                    append("  ·  ")
                }
                if (s.socStart != null && s.socEnd != null) {
                    append(String.format("%.0f→%.0f%%", s.socStart, s.socEnd))
                    append("  ·  ")
                }
                append("최대 ")
                append(Fmt.kw(s.maxKw))
            }
            return v
        }
    }

    init {
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val i = Intent(activity, ChargeDetailActivity::class.java)
            i.putExtra("charge_id", items[position].id)
            activity.startActivity(i)
        }
        root.findViewById<View>(R.id.charges_prev).setOnClickListener {
            cal.add(Calendar.MONTH, -1); reload()
        }
        root.findViewById<View>(R.id.charges_next).setOnClickListener {
            cal.add(Calendar.MONTH, 1); reload()
        }
    }

    override fun onShow() = reload()

    private fun reload() {
        val range = Fmt.monthRange(cal)
        val db = Db.get(activity)
        items = db.chargesBetween(range[0], range[1])
        adapter.notifyDataSetChanged()

        tvMonth.text = Fmt.monthTitle(cal)
        val totals = db.chargeTotals(range[0], range[1])
        tvSummary.text = String.format(
            "%d건 · %s · %s",
            totals[0].toInt(), Fmt.kwh(totals[1]), Fmt.won(totals[2])
        )
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
