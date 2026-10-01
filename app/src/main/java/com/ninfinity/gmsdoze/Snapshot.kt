package com.ninfinity.gmsdoze

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Chụp hẹn giờ: đặt báo thức, tới giờ (lúc màn hình đang tắt) chụp trạng thái hệ thống và lưu ra file,
 * để mở app lên sau đó vẫn xem được (mở màn hình sẽ làm trạng thái đổi nên không chụp tay được).
 */
object Snapshot {
    const val ACTION = "com.ninfinity.gmsdoze.SNAPSHOT"

    private fun file(ctx: Context) = File(ctx.filesDir, "timed_snapshot.txt")
    private fun sp(ctx: Context) = ctx.getSharedPreferences("gms_keeper", Context.MODE_PRIVATE)

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, HeartbeatReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, minutes: Int) {
        file(ctx).delete()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        sp(ctx).edit().putLong("snap_due", trigger).apply()
        val pi = pending(ctx)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    fun dueMs(ctx: Context): Long = sp(ctx).getLong("snap_due", 0L)

    fun savedText(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    /** Chạy ở luồng nền khi báo thức nổ. Giữ ngắn (vài giây) để không vượt giới hạn của receiver. */
    fun run(ctx: Context) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder("Chụp hẹn giờ lúc $time\n")

        var waited = 0
        while (!Doze.hasPermission() && waited < 4000) {
            Thread.sleep(300)
            waited += 300
        }
        if (!Doze.hasPermission()) {
            sb.append("Shizuku chưa sẵn sàng nên không chụp được.\n")
            file(ctx).writeText(sb.toString())
            return
        }

        val latch = CountDownLatch(1)
        var res: Result<String>? = null
        Doze.run(ctx, Doze.timedScript(), AppLog.begin("Chụp hẹn giờ")) { r ->
            res = r
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        val r = res
        if (r == null || r.isFailure) {
            sb.append("Lỗi: ${r?.exceptionOrNull()?.message ?: "hết thời gian chờ"}\n")
            file(ctx).writeText(sb.toString())
            return
        }
        val raw = r.getOrThrow()
        summarize(ctx, raw, sb)
        sb.append("\n== Dữ liệu thô ==\n").append(raw)
        file(ctx).writeText(sb.toString())
    }

    private fun summarize(ctx: Context, raw: String, sb: StringBuilder) {
        val s = Doze.sectionsOf(raw)
        sb.append("== Tóm tắt ==\n")
        sb.append("Màn hình: ").append(s["screen"].orEmpty().trim().replace("\n", " | ").ifEmpty { "không rõ" }).append('\n')
        sb.append("Doze: ").append(s["deviceidle"].orEmpty().trim().replace("\n", " | ").ifEmpty { "không rõ" }).append('\n')
        sb.append("Chính sách mạng: ").append(s["netpolicy"].orEmpty().trim().replace("\n", " | ").ifEmpty { "không rõ" }).append('\n')

        val wl = s["whitelist"].orEmpty()
        val byUid = Doze.connsByUid(s["fcm"].orEmpty())
        for (pkg in Prefs.allPackages(ctx)) {
            val uid = wl.lineSequence()
                .firstOrNull { it.contains(",$pkg,") }
                ?.split(",")?.getOrNull(2)?.trim()?.toIntOrNull()
            val conns = if (uid != null) byUid[uid].orEmpty() else emptyList()
            sb.append("$pkg (uid ${uid ?: "?"}): ${conns.size} kết nối")
            if (conns.isNotEmpty()) {
                sb.append(" → ").append(conns.take(5).joinToString(", ") { "${it.ip}:${it.port}" })
            }
            sb.append('\n')
        }

        val f = Doze.parseFcmFrom(raw)
        val cur = f.conns.firstOrNull { it.port in 5228..5230 }
        val key = if (cur != null) "${cur.ip}:${cur.port}<-${cur.lport}" else "none"
        val tr = Prefs.trackFcm(ctx, key)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        sb.append("FCM: ")
            .append(if (cur != null) "đang kết nối ${cur.ip}:${cur.port} (cổng nguồn ${cur.lport})" else "KHÔNG có kết nối FCM")
            .append('\n')
        sb.append("Kết nối FCM thấy lần đầu: ${fmt.format(Date(tr.sinceMs))}, số lần đổi: ${tr.changes}\n")
    }
}
