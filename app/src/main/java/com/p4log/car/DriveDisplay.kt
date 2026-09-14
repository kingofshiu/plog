package com.p4log.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * 주행 중 표시 실험 (2026-09-05).
 *
 * 우리 액티비티는 주행 중 가려진다(사이드로드 DO 게이트). 그래서 **시스템이 그리는 표면**에 우리 숫자를 얹는다.
 * 1) 헤드업 알림 — SystemUI가 화면 상단에 그린다 (주행 시작 등 이벤트)
 * 2) 상시 알림 — 서비스 알림 본문을 실시간 숫자로 갱신 (알림창에서 확인)
 * 3) MediaSession '지금 재생' — 홈 화면 미디어 카드·계기판이 곡 제목/아티스트 자리에 우리 숫자를 보여 준다.
 *    소리는 내지 않고 재생 상태만 PLAYING으로 둔다. 순정 미디어 앱에서 P.Log를 소스로 고르면 보인다.
 * 전부 설정(진단 화면 [주행 표시 시험])으로 켜고 끈다. 기본은 꺼짐 — 미디어 카드를 가로챌 수 있어서.
 */
object DriveDisplay {
    private const val CH_DRIVE = "p4log_drive"
    private const val NOTI_DRIVE = 1003
    private var session: MediaSession? = null
    private var lastMetaMs = 0L

    fun enabled(c: Context): Boolean = Prefs.driveDisplay(c)

    /** 미디어 세션 (없으면 만든다). PlogMediaService가 토큰을 가져가고, 서비스 tick이 메타데이터를 갱신한다 */
    @Synchronized
    fun session(c: Context): MediaSession {
        session?.let { return it }
        val s = MediaSession(c.applicationContext, "P.Log")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { setPlaying(s) }
            override fun onPause() { setPlaying(s) }          // 멈추지 않는다 — 표시가 목적
            override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) { setPlaying(s) }
        })
        val pi = PendingIntent.getActivity(
            c, 3, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        s.setSessionActivity(pi)
        setPlaying(s)
        s.isActive = true
        session = s
        return s
    }

    private fun setPlaying(s: MediaSession) {
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    @Synchronized
    fun release() {
        try { session?.isActive = false; session?.release() } catch (e: Throwable) {}
        session = null
    }

    /** 지금 재생 카드에 보일 세 줄: 제목 / 아티스트 / 앨범 */
    fun lines(s: StatusSnapshot): Triple<String, String, String> {
        fun f1(v: Double) = String.format("%.1f", v)
        val soc = s.socPct?.let { Math.round(it).toString() + "%" } ?: "-"
        val range = s.rangeKm?.let { Math.round(it).toString() + "km" } ?: "-"
        val title = if (s.tripActive)
            "주행 " + f1(s.tripKm) + "km · " + Math.round(s.speedKmh ?: 0f) + "km/h"
        else "오늘 " + f1(s.todayKm) + "km"
        val artist = "배터리 " + soc + " · 잔여 " + range
        val album = (s.chargeRateKw?.let { f1(Math.abs(it.toDouble())) + "kW" } ?: "") +
            (s.todayEff?.let { " · " + f1(it) + "km/kWh" } ?: "")
        return Triple(title, artist, album.trim(' ', '·'))
    }

    /** tick마다 호출. 5초에 한 번만 메타데이터를 갱신한다 */
    fun update(c: Context, s: StatusSnapshot) {
        if (!enabled(c)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMetaMs < 5_000L) return
        lastMetaMs = now
        try {
            val (t, a, al) = lines(s)
            session(c).setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, a)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, al)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, t)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, a)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)
                    .build()
            )
        } catch (e: Throwable) {
            ServiceLog.add(c, "미디어 카드 갱신 실패: " + e.javaClass.simpleName)
        }
    }

    /** 헤드업 알림 (높은 중요도 채널). 주행 중 SystemUI가 띄워 주는지가 실차 판정 포인트 */
    fun headsUp(c: Context, title: String, text: String, navigationCategory: Boolean) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CH_DRIVE, "주행 표시", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                c, 4, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = Notification.Builder(c, CH_DRIVE)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
            // 차량 SystemUI는 주행 중 헤드업을 종류별로 거른다(전화·내비·메시지 우선). 어떤 종류가 통과하는지 보려고
            // 내비 카테고리로도 한 번 시험한다
            if (navigationCategory) b.setCategory(Notification.CATEGORY_NAVIGATION)
            nm.notify(NOTI_DRIVE + (if (navigationCategory) 1 else 0), b.build())
        } catch (e: Throwable) {
            ServiceLog.add(c, "헤드업 알림 실패: " + e.javaClass.simpleName)
        }
    }
}
