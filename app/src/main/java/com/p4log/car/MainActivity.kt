package com.p4log.car

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 100
    }

    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private val pageViews = HashMap<Int, View>()
    private val pages = HashMap<Int, PageController>()
    private var currentTab = -1

    // 탭 정의: (라벨, 레이아웃, 컨트롤러 생성)
    private val tabs: List<Triple<String, Int, (View) -> PageController>> = listOf(
        Triple("대시", R.layout.page_dashboard) { v: View -> DashboardPage(this, v) },
        Triple("주행", R.layout.page_trips) { v: View -> TripsPage(this, v) },
        Triple("충전", R.layout.page_charges) { v: View -> ChargesPage(this, v) },
        Triple("차량", R.layout.page_care) { v: View -> CarePage(this, v) },
        Triple("설정", R.layout.page_settings) { v: View -> SettingsPage(this, v) }
    )
    private val tabIcons = intArrayOf(
        R.drawable.ic_tab_dash, R.drawable.ic_tab_trip, R.drawable.ic_bolt,
        R.drawable.ic_tab_car, R.drawable.ic_tab_settings
    )

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            pages[currentTab]?.onTick()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.main_content)
        tabBar = findViewById(R.id.main_tabbar)

        val dp = resources.displayMetrics.density
        for (i in tabs.indices) {
            // 셀은 균등 분할, 필(pill)은 아이콘+글자에만 붙도록 내부 컨테이너에 배경 적용
            val cell = android.widget.FrameLayout(this)
            cell.setBackgroundResource(R.drawable.bg_tab)
            cell.isClickable = true

            val inner = LinearLayout(this)
            inner.orientation = LinearLayout.HORIZONTAL
            inner.gravity = android.view.Gravity.CENTER
            inner.setPadding((18 * dp).toInt(), (9 * dp).toInt(), (18 * dp).toInt(), (9 * dp).toInt())

            val iv = android.widget.ImageView(this)
            iv.setImageResource(tabIcons[i])
            iv.setColorFilter(Color.parseColor("#C9C9CE"))
            inner.addView(iv, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))

            val tv = android.widget.TextView(this)
            tv.text = tabs[i].first
            tv.textSize = 18f
            tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            tv.setTextColor(Color.parseColor("#C9C9CE"))
            val tvLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tvLp.marginStart = (8 * dp).toInt()
            inner.addView(tv, tvLp)

            cell.addView(
                inner,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
            cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            cell.setOnClickListener { showTab(i) }
            tabBar.addView(cell)
        }

        requestNeededPermissions()
        LoggerService.start(this)
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        pages[currentTab]?.onShow()
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTicker)
    }

    fun showTab(index: Int) {
        if (index == currentTab) return
        for ((_, pv) in pageViews) pv.visibility = View.GONE
        val v: View = pageViews[index] ?: run {
            val nv = layoutInflater.inflate(tabs[index].second, content, false)
            content.addView(nv)
            pageViews[index] = nv
            pages[index] = tabs[index].third(nv)
            nv
        }
        v.visibility = View.VISIBLE
        // 탭 전환 애니메이션: 살짝 아래에서 떠오르며 페이드 인
        v.alpha = 0f
        v.translationY = 24f
        v.animate().alpha(1f).translationY(0f).setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        currentTab = index
        val dp = resources.displayMetrics.density
        for (i in 0 until tabBar.childCount) {
            val cell = tabBar.getChildAt(i) as android.widget.FrameLayout
            val inner = cell.getChildAt(0) as LinearLayout
            val sel = i == index
            val c = if (sel) Color.parseColor("#FF7500") else Color.parseColor("#C9C9CE")
            (inner.getChildAt(0) as android.widget.ImageView).setColorFilter(c)
            (inner.getChildAt(1) as android.widget.TextView).setTextColor(c)
            if (sel) inner.setBackgroundResource(R.drawable.bg_tab_selected)
            else inner.background = null
            // 배경 교체가 패딩을 초기화하므로 재적용
            inner.setPadding((18 * dp).toInt(), (9 * dp).toInt(), (18 * dp).toInt(), (9 * dp).toInt())
        }
        pages[index]?.onShow()
    }

    // ---------- 권한 ----------

    private fun neededPermissions(): Array<String> {
        val perms = ArrayList<String>()
        perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.POST_NOTIFICATIONS")
        }
        // 차량 위험 권한 — AAOS가 요청 다이얼로그를 띄워줌
        perms.add("android.car.permission.CAR_ENERGY")
        perms.add("android.car.permission.CAR_ENERGY_PORTS")
        perms.add("android.car.permission.CAR_SPEED")
        perms.add("android.car.permission.CAR_POWERTRAIN")
        return perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    private fun requestNeededPermissions() {
        val need = neededPermissions()
        if (need.isNotEmpty()) requestPermissions(need, REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            // 위치 권한을 받았으면 서비스 재시작해서 GPS 활성화
            LoggerService.start(this)
        }
    }
}

/** 각 탭 페이지 공통 인터페이스 */
interface PageController {
    /** 탭이 화면에 나타날 때 */
    fun onShow()
    /** 1초마다 (현재 탭만) */
    fun onTick() {}
}
