package com.ninfinity.gmsdoze

import android.content.Context

/** Danh sách app người dùng muốn bảo vệ thêm ngoài GMS/GSF. Mặc định: Zalo. */
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
}
