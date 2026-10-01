package com.ninfinity.gmsdoze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Boot hoặc cập nhật app: dựng lại process, áp dụng lại whitelist (nếu Shizuku đã chạy)
        // và đặt lại báo thức giữ nhịp (báo thức bị xoá sau reboot/cập nhật).
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val app = context.applicationContext
            Doze.applyIfPossible(app)
            Heartbeat.scheduleNext(app)
        }
    }
}
