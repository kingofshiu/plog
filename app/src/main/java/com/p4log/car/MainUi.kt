package com.p4log.car

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 메인 화면(탭바 + 5개 페이지). MainActivity와 주행 중 오버레이(DriveOverlay)가 같이 쓴다 (2026-09-05).
 * @param root activity_main 레이아웃의 루트
 * @param closeLabel 오버레이일 때 탭바 끝에 붙는 "닫기" 셀 (null이면 없음)
 */
class MainUi(
    private val host: AppHost,
    root: View,
    closeLabel: String? = null,
    private val onClose: (() -> Unit)? = null
) {
    private val content: FrameLayout = root.findViewById(R.id.main_content)
    private val tabBar: LinearLayout = root.findViewById(R.id.main_tabbar)
    private val pageViews = HashMap<Int, View>()
    private val pages = HashMap<Int, PageController>()
    private var currentTab = -1

    private val tabs: List<Triple<String, Int, (View) -> PageController>> = listOf(
        // 2026-09-05 (사용자 결정): "대시"를 "주행"으로 — 실시간 상태 + 전력 그래프 + 전비 비교 + 회생 + 최근 주행을
        // 한 화면에. 월별 주행 목록 탭은 삭제 (목록은 주행 탭 하단 최근 8건 + 웹/폰 앱)
        Triple("주행", R.layout.page_dashboard) { v: View -> DashboardPage(host, v) },
        Triple("충전", R.layout.page_charges) { v: View -> ChargesPage(host, v) },
        Triple("차량", R.layout.page_care) { v: View -> CarePage(host, v) },
        Triple("설정", R.layout.page_settings) { v: View -> SettingsPage(host, v) }
    )
    private val tabIcons = intArrayOf(
        R.drawable.ic_tab_trip, R.drawable.ic_bolt,
        R.drawable.ic_tab_car, R.drawable.ic_tab_settings
    )

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            pages[currentTab]?.onTick()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    init {
        val dp = host.context.resources.displayMetrics.density
        for (i in tabs.indices) addTabCell(tabs[i].first, tabIcons[i], dp) { showTab(i) }
        if (closeLabel != null) addTabCell(closeLabel, R.drawable.ic_close, dp) { onClose?.invoke() }
        showTab(0)
    }

    private fun addTabCell(label: String, icon: Int, dp: Float, onClick: () -> Unit) {
        val ctx = host.context
        // 셀은 균등 분할, 필(pill)은 아이콘+글자에만 붙도록 내부 컨테이너에 배경 적용
        val cell = FrameLayout(ctx)
        cell.setBackgroundResource(R.drawable.bg_tab)
        cell.isClickable = true

        val inner = LinearLayout(ctx)
        inner.orientation = LinearLayout.HORIZONTAL
        inner.gravity = android.view.Gravity.CENTER
        inner.setPadding((18 * dp).toInt(), (9 * dp).toInt(), (18 * dp).toInt(), (9 * dp).toInt())

        val iv = android.widget.ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(Color.parseColor("#C9C9CE"))
        inner.addView(iv, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))

        val tv = android.widget.TextView(ctx)
        tv.text = label
        tv.textSize = 22f  // 탭: 글꼴 체계 본문(24)보다 한 단계 작게 — 탭바 높이 64dp 안에서
        tv.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        tv.setTextColor(Color.parseColor("#C9C9CE"))
        val tvLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tvLp.marginStart = (8 * dp).toInt()
        inner.addView(tv, tvLp)

        cell.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        )
        cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        cell.setOnClickListener { onClick() }
        tabBar.addView(cell)
    }

    /** 화면에 보일 때 (Activity onResume / 오버레이 표시) */
    fun start() {
        pages[currentTab]?.onShow()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
    }

    /** 화면에서 사라질 때 */
    fun stop() {
        uiHandler.removeCallbacks(uiTicker)
        pages[currentTab]?.onHide()
    }

    fun showTab(index: Int) {
        if (index == currentTab) return
        pages[currentTab]?.onHide()
        for ((_, pv) in pageViews) pv.visibility = View.GONE
        val v: View = pageViews[index] ?: run {
            val nv = LayoutInflater.from(host.context).inflate(tabs[index].second, content, false)
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
        val dp = host.context.resources.displayMetrics.density
        for (i in 0 until tabs.size) {
            val cell = tabBar.getChildAt(i) as FrameLayout
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
}

/** 각 탭 페이지 공통 인터페이스 */
interface PageController {
    /** 탭이 화면에 나타날 때 */
    fun onShow()
    /** 1초마다 (현재 탭만) */
    fun onTick() {}
    /** 탭이 화면에서 사라질 때 (다른 탭으로 바뀌거나 화면이 내려감). 구독 해제용 (2026-09-13) */
    fun onHide() {}
}
