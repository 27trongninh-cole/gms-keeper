package com.ninfinity.gmsdoze

import android.app.Application
import rikka.shizuku.Shizuku

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Shizuku gửi binder cho app mỗi khi nó khởi động (kể cả sau reboot),
        // nên process app được dựng lại và listener này tự áp dụng whitelist.
        Shizuku.addBinderReceivedListenerSticky {
            Doze.applyIfPossible(this)
        }
    }
}
