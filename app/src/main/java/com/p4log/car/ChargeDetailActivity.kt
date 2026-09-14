package com.p4log.car

import android.app.Activity
import android.os.Bundle

/** 충전 상세 (Activity 래퍼 — 실제 화면은 ChargeDetailScreen) */
class ChargeDetailActivity : Activity() {
    companion object {
        const val DEMO_ID = ChargeDetailScreen.DEMO_ID
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charge_detail)
        val root = findViewById<android.view.View>(android.R.id.content)
        val ok = ChargeDetailScreen.bind(ActivityHost(this), root, intent.getLongExtra("charge_id", -1L))
        if (!ok) finish()
    }
}
