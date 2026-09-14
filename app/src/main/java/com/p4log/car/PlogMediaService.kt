package com.p4log.car

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * 순정 미디어 앱이 붙는 진입점 (2026-09-05 주행 중 표시 실험).
 * 미디어 앱은 시스템 앱이라 주행 중에도 화면에 남고, 우리가 준 목록(제목·부제)을 그대로 그린다.
 * 곡 대신 배터리·주행·전비 행을 준다. 소리는 없다.
 */
class PlogMediaService : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        sessionToken = DriveDisplay.session(this).sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? =
        BrowserRoot("root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val s = LoggerService.lastSnapshot
        val (t, a, al) = DriveDisplay.lines(s)
        fun item(id: String, title: String, sub: String) = MediaBrowser.MediaItem(
            MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
            MediaBrowser.MediaItem.FLAG_PLAYABLE
        )
        val items = mutableListOf(
            item("now", t, a),
            item("energy", al.ifEmpty { "전력 -" }, if (s.chargeActive) "충전 중" else "주행 표시 (P.Log)"),
            item("status", if (s.carConnected) "차량 연결됨" else "차량 미연결",
                if (s.gpsFix) "GPS 수신 중" else "GPS 없음")
        )
        result.sendResult(items)
    }
}
