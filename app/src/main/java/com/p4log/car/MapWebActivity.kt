package com.p4log.car

import android.app.Activity
import android.os.Bundle

/** 지도 (Activity 래퍼 — 실제 화면은 MapScreen) */
class MapWebActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_web)
        val root = findViewById<android.view.View>(android.R.id.content)
        MapScreen.bind(
            ActivityHost(this), root,
            intent.getStringExtra("mode") ?: "point",
            intent.getStringExtra("title") ?: "지도",
            intent.getDoubleExtra("lat", 0.0), intent.getDoubleExtra("lon", 0.0),
            intent.getStringExtra("polyline")
        )
    }
}
