package com.ninfinity.gmsdoze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Chỉ cần receiver này để process app được khởi động sau boot.
        // Nếu Shizuku đã chạy thì áp dụng luôn; nếu chưa, listener trong App sẽ làm khi Shizuku lên.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Doze.applyIfPossible(context.applicationContext)
        }
    }
}
