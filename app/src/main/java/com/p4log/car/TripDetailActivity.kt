package com.p4log.car

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

/** 주행 상세: 통계 + 경로 지도 */
class TripDetailActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_detail)

        findViewById<TextView>(R.id.td_back).setOnClickListener { finish() }

        val tripId = intent.getLongExtra("trip_id", -1L)
        val trip = Db.get(this).tripById(tripId)
        if (trip == null) { finish(); return }

        findViewById<TextView>(R.id.td_title).text =
            Fmt.fullDateTime(trip.startTs) + " 주행"
        findViewById<TextView>(R.id.td_km).text = Fmt.km(trip.distanceKm)
        findViewById<TextView>(R.id.td_time).text = Fmt.duration(trip.startTs, trip.endTs)
        findViewById<TextView>(R.id.td_eff).text = Fmt.eff(trip.effKmPerKwh)
        findViewById<TextView>(R.id.td_energy).text =
            if (trip.energyKwh != null) Fmt.kwh(trip.energyKwh) else "-"
        findViewById<TextView>(R.id.td_soc).text =
            if (trip.socStart != null && trip.socEnd != null)
                String.format("%.0f%% → %.0f%%", trip.socStart, trip.socEnd)
            else "-"
        findViewById<TextView>(R.id.td_speed_avg).text =
            String.format("%.0f km/h", trip.avgKmh)
        findViewById<TextView>(R.id.td_speed_max).text =
            String.format("%.0f km/h", trip.maxKmh)

        // 내 최근 주행 전비와 비교 (이번 주행 강조)
        findViewById<TripEffChartView>(R.id.td_chart)
            .setData(Db.get(this).recentTripEffs(15), tripId)

        val web = findViewById<WebView>(R.id.td_web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                web.evaluateJavascript("showTrip(${trip.polyline});", null)
            }
        })
        web.loadUrl("file:///android_asset/map.html")
    }
}
