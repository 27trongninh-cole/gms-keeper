package com.ninfinity.gmsdoze

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private val lines = ArrayList<String>()
    @Volatile var listener: (() -> Unit)? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.add("${fmt.format(Date())} $msg")
        if (lines.size > 300) lines.removeAt(0)
        Log.i("GmsKeeper", msg)
        listener?.invoke()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
