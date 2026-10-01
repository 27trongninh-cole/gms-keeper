package com.ninfinity.gmsdoze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action
        val pending = goAsync()
        Thread {
            try {
                if (action == Snapshot.ACTION) Snapshot.run(app) else Heartbeat.tick(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
