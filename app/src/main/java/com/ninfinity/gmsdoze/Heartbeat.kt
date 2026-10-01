package com.ninfinity.gmsdoze

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Giữ nhịp: định kỳ gửi heartbeat tới GMS (qua Shizuku), có lịch khung giờ + ngày trong tuần. */
object Heartbeat {
    const val ACTION = "com.ninfinity.gmsdoze.HEARTBEAT"
    private const val FILE = "gms_keeper"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- cấu hình ----
    fun enabled(ctx: Context) = sp(ctx).getBoolean("hb_on", false)
    fun intervalMin(ctx: Context) = sp(ctx).getInt("hb_interval", 5)
    fun schedOn(ctx: Context) = sp(ctx).getBoolean("hb_sched_on", false)
    fun startMin(ctx: Context) = sp(ctx).getInt("hb_start", 7 * 60)
    fun endMin(ctx: Context) = sp(ctx).getInt("hb_end", 22 * 60)
    /** bit0 = Thứ 2 ... bit6 = Chủ nhật */
    fun days(ctx: Context) = sp(ctx).getInt("hb_days", 0x7F)

    fun setEnabled(ctx: Context, on: Boolean) {
        val e = sp(ctx).edit().putBoolean("hb_on", on)
        if (on) e.putInt("hb_count", 0).putLong("hb_last", 0L)
        e.apply()
    }

    fun setInterval(ctx: Context, m: Int) = sp(ctx).edit().putInt("hb_interval", m).apply()
    fun setSchedOn(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("hb_sched_on", on).apply()
    fun setStart(ctx: Context, m: Int) = sp(ctx).edit().putInt("hb_start", m).apply()
    fun setEnd(ctx: Context, m: Int) = sp(ctx).edit().putInt("hb_end", m).apply()
    fun toggleDay(ctx: Context, idx: Int) = sp(ctx).edit().putInt("hb_days", days(ctx) xor (1 shl idx)).apply()

    // ---- lịch ----
    private fun dayIdx(cal: Calendar) = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    private fun hasDay(mask: Int, i: Int) = ((mask shr i) and 1) == 1

    fun inWindow(ctx: Context, whenMs: Long): Boolean {
        if (!schedOn(ctx)) return true
        val cal = Calendar.getInstance().apply { timeInMillis = whenMs }
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = startMin(ctx)
        val e = endMin(ctx)
        val d = days(ctx)
        val today = dayIdx(cal)
        return when {
            s == e -> hasDay(d, today)                       // bằng nhau = cả ngày
            s < e -> hasDay(d, today) && m >= s && m < e
            else -> (m >= s && hasDay(d, today)) ||           // khung qua nửa đêm
                (m < e && hasDay(d, (today + 6) % 7))
        }
    }

    private fun nextWindowStart(ctx: Context, after: Long): Long? {
        val s = startMin(ctx)
        val d = days(ctx)
        for (off in 0..7) {
            val cal = Calendar.getInstance().apply { timeInMillis = after }
            cal.add(Calendar.DAY_OF_YEAR, off)
            cal.set(Calendar.HOUR_OF_DAY, s / 60)
            cal.set(Calendar.MINUTE, s % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > after && hasDay(d, dayIdx(cal))) return cal.timeInMillis
        }
        return null
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, HeartbeatReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Đặt lại báo thức cho lần kế tiếp (hoặc huỷ nếu đang tắt). */
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        am.cancel(pi)
        if (!enabled(ctx)) {
            sp(ctx).edit().putLong("hb_next", 0L).apply()
            return
        }
        val now = System.currentTimeMillis()
        val candidate = now + intervalMin(ctx) * 60_000L
        val trigger = when {
            !schedOn(ctx) -> candidate
            inWindow(ctx, now) && inWindow(ctx, candidate) -> candidate
            else -> nextWindowStart(ctx, now) ?: (now + 6 * 3_600_000L)
        }
        sp(ctx).edit().putLong("hb_next", trigger).apply()
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun record(ctx: Context, ok: Boolean, msg: String) {
        val p = sp(ctx)
        p.edit()
            .putLong("hb_last", System.currentTimeMillis())
            .putBoolean("hb_last_ok", ok)
            .putString("hb_last_msg", msg)
            .putInt("hb_count", p.getInt("hb_count", 0) + if (ok) 1 else 0)
            .apply()
    }

    /** Chạy ở luồng nền khi báo thức nổ. */
    fun tick(ctx: Context) {
        if (!enabled(ctx)) return
        scheduleNext(ctx) // đặt lần kế trước để chuỗi không bị đứt nếu gửi lỗi
        if (!inWindow(ctx, System.currentTimeMillis())) return

        // process có thể vừa được dựng lại: chờ Shizuku gửi binder
        var waited = 0
        while (!Doze.hasPermission() && waited < 6000) {
            Thread.sleep(300)
            waited += 300
        }
        if (!Doze.hasPermission()) {
            record(ctx, false, "Shizuku chưa sẵn sàng")
            return
        }
        val latch = CountDownLatch(1)
        var res: Result<String>? = null
        Doze.run(ctx, Doze.heartbeatScript(), AppLog.Stage("Giữ nhịp")) { r ->
            res = r
            latch.countDown()
        }
        latch.await(12, TimeUnit.SECONDS)
        val r = res
        if (r != null && r.isSuccess) {
            record(ctx, true, "")
        } else {
            val msg = r?.exceptionOrNull()?.message ?: "hết thời gian chờ"
            record(ctx, false, msg)
            AppLog.begin("Giữ nhịp lỗi").add(msg)
        }
    }

    /** Nút "Gửi thử 1 nhịp": có ghi vào log. */
    fun sendNow(ctx: Context, done: () -> Unit) {
        val st = AppLog.begin("Giữ nhịp (gửi thử)")
        Doze.run(ctx, Doze.heartbeatScript(), st) { r ->
            record(ctx, r.isSuccess, r.exceptionOrNull()?.message ?: "")
            done()
        }
    }

    /** Nút "Kiểm tra GMS có xử lý nhịp": trả về kết luận dạng chữ. */
    fun measure(ctx: Context, done: (String) -> Unit) {
        val st = AppLog.begin("Kiểm tra receiver nhịp")
        Doze.run(ctx, Doze.receiverScript(), st) { r ->
            val msg = r.fold({ Doze.interpretReceivers(it) }, { "Lỗi: ${it.message}" })
            st.add("Kết luận: $msg")
            done(msg)
        }
    }

    fun statusText(ctx: Context): String {
        if (!enabled(ctx)) return "Đang tắt"
        val p = sp(ctx)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val parts = ArrayList<String>()
        val last = p.getLong("hb_last", 0L)
        if (last > 0) {
            val ok = p.getBoolean("hb_last_ok", false)
            val msg = p.getString("hb_last_msg", "").orEmpty()
            parts.add("Lần gần nhất: ${fmt.format(Date(last))} " + if (ok) "✓" else "✗ ($msg)")
        } else {
            parts.add("Chưa gửi nhịp nào")
        }
        if (schedOn(ctx) && !inWindow(ctx, System.currentTimeMillis())) {
            parts.add("Ngoài khung giờ: đang nghỉ")
        }
        val next = p.getLong("hb_next", 0L)
        if (next > 0) parts.add("Lần kế: ${fmt.format(Date(next))}")
        parts.add("Đã gửi ${p.getInt("hb_count", 0)} nhịp")
        return parts.joinToString("\n")
    }
}
