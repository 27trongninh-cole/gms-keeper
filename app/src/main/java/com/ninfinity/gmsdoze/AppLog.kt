package com.ninfinity.gmsdoze

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log chia theo giai đoạn (mỗi lần chạy lệnh là một Stage) để sao chép riêng lẻ. */
object AppLog {
    class Stage(val title: String) {
        val time: String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        @Volatile
        var ok: Boolean? = null
            set(v) {
                field = v
                AppLog.notifyChanged()
            }

        private val lines = ArrayList<String>()

        @Synchronized
        fun add(msg: String) {
            lines.add(msg)
            Log.i("GmsKeeper", "[$title] $msg")
            AppLog.notifyChanged()
        }

        @Synchronized
        fun body(): String = lines.joinToString("\n")

        fun copyText(): String {
            val mark = when (ok) {
                true -> " ✓"
                false -> " ✗"
                null -> ""
            }
            return "[$time] $title$mark\n${body()}"
        }
    }

    private const val MAX_STAGES = 25
    private val stages = ArrayList<Stage>()

    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun begin(title: String): Stage {
        val s = Stage(title)
        stages.add(s)
        if (stages.size > MAX_STAGES) stages.removeAt(0)
        notifyChanged()
        return s
    }

    @Synchronized
    fun all(): List<Stage> = ArrayList(stages)

    fun notifyChanged() {
        listener?.invoke()
    }
}
