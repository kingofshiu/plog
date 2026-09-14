package com.p4log.car

import android.app.Activity
import android.os.Bundle

/** 주행 상세 (Activity 래퍼 — 실제 화면은 TripDetailScreen) */
class TripDetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_detail)
        val root = findViewById<android.view.View>(android.R.id.content)
        val ok = TripDetailScreen.bind(ActivityHost(this), root, intent.getLongExtra("trip_id", -1L))
        if (!ok) finish()
    }
}
