package com.p4log.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 부팅 시 로거 서비스 자동 시작 시도 (AAOS에서 막히면 앱을 한 번 열면 됨) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            Log.i("P4Log.Boot", "boot received, starting service")
            try {
                LoggerService.start(context)
            } catch (e: Throwable) {
                Log.w("P4Log.Boot", "service start failed", e)
            }
        }
    }
}
