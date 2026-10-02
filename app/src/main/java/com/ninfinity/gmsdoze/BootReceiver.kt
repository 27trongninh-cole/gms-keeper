package com.ninfinity.gmsdoze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Boot: dựng process app. Nếu Shizuku đã chạy thì áp dụng ngay, nếu chưa thì
        // listener trong App sẽ áp dụng khi Shizuku lên.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Doze.applyIfPossible(context.applicationContext)
        }
    }
}
