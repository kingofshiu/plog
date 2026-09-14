package com.p4log.car

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

/** 주행 상세: 통계 + 경로 지도. Activity와 오버레이 스택이 같이 쓴다 (2026-09-05) */
object TripDetailScreen {
    @SuppressLint("SetJavaScriptEnabled")
    fun bind(host: AppHost, root: View, tripId: Long): Boolean {
        root.findViewById<View>(R.id.td_back).setOnClickListener { host.back() }
        val trip = Db.get(host.context).tripById(tripId) ?: DashboardPage.demoTrip(tripId) ?: return false

        root.findViewById<TextView>(R.id.td_title).text = Fmt.fullDateTime(trip.startTs) + " 주행"
        root.findViewById<TextView>(R.id.td_km).text = Fmt.km(trip.distanceKm)
        root.findViewById<TextView>(R.id.td_time).text = Fmt.duration(trip.startTs, trip.endTs)
        root.findViewById<TextView>(R.id.td_eff).text = Fmt.eff(trip.effKmPerKwh)
        root.findViewById<TextView>(R.id.td_energy).text =
            if (trip.energyKwh != null) Fmt.kwh(trip.energyKwh) else "-"
        root.findViewById<TextView>(R.id.td_soc).text =
            if (trip.socStart != null && trip.socEnd != null)
                String.format("%.0f%% → %.0f%%", trip.socStart, trip.socEnd)
            else "-"
        root.findViewById<TextView>(R.id.td_speed_avg).text = String.format("%.0f km/h", trip.avgKmh)
        root.findViewById<TextView>(R.id.td_speed_max).text = String.format("%.0f km/h", trip.maxKmh)

        // 내 최근 주행 전비와 비교 (이번 주행 강조)
        root.findViewById<TripEffChartView>(R.id.td_chart)
            .setData(Db.get(host.context).recentTripEffs(15), tripId)

        val web = root.findViewById<WebView>(R.id.td_web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                web.evaluateJavascript("showTrip(${trip.polyline});", null)
            }
        })
        web.loadUrl(Prefs.mapUrl(web.context))
        return true
    }
}
