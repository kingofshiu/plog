package com.p4log.car

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import android.widget.TextView

/**
 * WebView + Leaflet 지도 화면. Activity(MapWebActivity)와 오버레이 스택이 같이 쓴다.
 * mode="point" : 단일 마커 (lat, lon) / mode="trip" : 경로 폴리라인 (polyline JSON)
 */
object MapScreen {
    @SuppressLint("SetJavaScriptEnabled")
    fun bind(host: AppHost, root: View, mode: String, title: String, lat: Double, lon: Double, polyline: String?) {
        root.findViewById<TextView>(R.id.map_title).text = title
        root.findViewById<TextView>(R.id.map_back).setOnClickListener { host.back() }
        val web = root.findViewById<WebView>(R.id.map_web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setWebViewClient(object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                when (mode) {
                    "trip" -> web.evaluateJavascript("showTrip(${polyline ?: "[]"});", null)
                    else -> web.evaluateJavascript("showPoint($lat, $lon);", null)
                }
            }
        })
        web.loadUrl(Prefs.mapUrl(web.context))
    }
}
