package com.p4log.car

import android.app.Activity
import android.os.Bundle

/** 전체 주행 기록 (Activity 래퍼 — 실제 화면은 TripsScreen) */
class TripsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trips)
        TripsScreen.bind(ActivityHost(this), findViewById(android.R.id.content))
    }
}
