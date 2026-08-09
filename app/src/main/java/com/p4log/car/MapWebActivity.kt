package com.p4log.car

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.TextView

/**
 * WebView + Leaflet(OSM) 지도 화면.
 * mode="point"  : 단일 마커 (주차 위치) — extras: lat, lon, title
 * mode="trip"   : 경로 폴리라인 — extras: polyline (JSON [[lat,lon],...])
 *
 * assets/map.html 을 로드하고 자바스크립트 함수를 호출한다.
 * (지도 타일은 인터넷 필요)
 */
class MapWebActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_web)

        val title = intent.getStringExtra("title") ?: "지도"
        findViewById<TextView>(R.id.map_title).text = title
        findViewById<TextView>(R.id.map_back).setOnClickListener { finish() }

        val web = findViewById<WebView>(R.id.map_web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        val mode = intent.getStringExtra("mode") ?: "point"
        web.setWebViewClient(object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                when (mode) {
                    "trip" -> {
                        val poly = intent.getStringExtra("polyline") ?: "[]"
                        web.evaluateJavascript("showTrip($poly);", null)
                    }
                    else -> {
                        val lat = intent.getDoubleExtra("lat", 0.0)
                        val lon = intent.getDoubleExtra("lon", 0.0)
                        web.evaluateJavascript("showPoint($lat, $lon);", null)
                    }
                }
            }
        })
        web.loadUrl("file:///android_asset/map.html")
    }
}
