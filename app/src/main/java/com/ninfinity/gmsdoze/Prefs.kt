package com.ninfinity.gmsdoze

import android.content.Context

/** Danh sách app người dùng muốn bảo vệ thêm ngoài GMS. Mặc định: Zalo. */
object Prefs {
    private const val FILE = "gms_keeper"
    private const val KEY = "apps"
    private const val DEFAULT = "com.zing.zalo"

    fun apps(ctx: Context): List<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT).orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setApps(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, list.distinct().joinToString(",")).apply()
    }

    fun allPackages(ctx: Context): List<String> = (Doze.GOOGLE + apps(ctx)).distinct()

    /** Theo dõi kết nối FCM của GMS giữa các lần kiểm tra: khi nào thấy lần đầu, đã đổi mấy lần. */
    data class FcmTrack(val sinceMs: Long, val lastChangeMs: Long, val changes: Int)

    fun trackFcm(ctx: Context, key: String): FcmTrack {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val prev = sp.getString("fcm_key", null)
        var since = sp.getLong("fcm_since", now)
        var lastChange = sp.getLong("fcm_change", 0L)
        var changes = sp.getInt("fcm_changes", 0)
        if (prev != key) {
            since = now
            if (prev != null) {
                lastChange = now
                changes++
            }
            sp.edit()
                .putString("fcm_key", key)
                .putLong("fcm_since", since)
                .putLong("fcm_change", lastChange)
                .putInt("fcm_changes", changes)
                .apply()
        }
        return FcmTrack(since, lastChange, changes)
    }
}
