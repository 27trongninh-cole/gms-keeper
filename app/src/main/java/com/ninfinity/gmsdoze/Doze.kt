package com.ninfinity.gmsdoze

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

object Doze {
    val GOOGLE = listOf("com.google.android.gms", "com.google.android.gsf")

    private const val LIST_CMD = "dumpsys deviceidle whitelist"
    private const val TIMEOUT_MS = 10_000L

    /** Kết quả chẩn đoán 1 app. */
    data class Diag(
        val installed: Boolean,
        val pid: String,
        val bucket: Int?,
        val rib: String,   // RUN_IN_BACKGROUND
        val raib: String,  // RUN_ANY_IN_BACKGROUND
        val auto: String,  // Xiaomi AUTO_START (op 10008)
        val stopped: Boolean?,
        val whitelisted: Boolean
    )

    data class Conn(val ip: String, val port: Int, val lport: Int)

    /** Các kết nối TCP đang ESTABLISHED của GMS (đọc từ /proc/net/tcp*). */
    data class Fcm(val uid: Int?, val readable: Boolean, val conns: List<Conn>)

    data class DiagResult(val apps: Map<String, Diag>, val fcm: Fcm)

    fun shizukuReady(): Boolean =
        !Shizuku.isPreV11() && Shizuku.pingBinder()

    fun hasPermission(): Boolean =
        shizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun info(): String = runCatching {
        "Shizuku v${Shizuku.getVersion()}, uid=${Shizuku.getUid()} " +
            "(2000=adb/shell, 0=root), perm=${hasPermission()}"
    }.getOrElse { "Shizuku info lỗi: ${it.message}" }

    /** Cách 1: Shizuku.newProcess (private trong API 13 nên gọi bằng reflection), chạy ngay trong server Shizuku. */
    private fun execViaNewProcess(command: String): String {
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return out
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    /** Chạy 1 lệnh shell: thử newProcess trước, lỗi thì fallback sang UserService. Gọi onResult đúng 1 lần. */
    fun run(ctx: Context, command: String, st: AppLog.Stage, onResult: (Result<String>) -> Unit) {
        Thread {
            val r = runCatching { execViaNewProcess(command) }
            if (r.isSuccess) {
                st.add("newProcess OK. Kết quả:\n${r.getOrNull()}")
                st.ok = true
                onResult(r)
            } else {
                st.add("newProcess lỗi: ${r.exceptionOrNull()}. Thử UserService...")
                runViaUserService(ctx, command, st, onResult)
            }
        }.start()
    }

    /** Cách 2 (fallback): Shizuku UserService. Luôn gọi onResult đúng 1 lần (kể cả timeout). */
    private fun runViaUserService(ctx: Context, command: String, st: AppLog.Stage, onResult: (Result<String>) -> Unit) {
        val app = ctx.applicationContext
        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var connRef: ServiceConnection? = null

        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, UserService::class.java.name)
        ).processNameSuffix("shell").daemon(false).version(2)

        fun finish(r: Result<String>) {
            if (done.compareAndSet(false, true)) {
                st.ok = r.isSuccess
                onResult(r)
            }
        }

        val timeout = Runnable {
            st.add("TIMEOUT ${TIMEOUT_MS / 1000}s: UserService không kết nối được")
            finish(Result.failure(RuntimeException("Timeout: UserService không kết nối")))
            connRef?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                st.add("UserService đã kết nối, đang chạy lệnh...")
                val self = this
                Thread {
                    val r = runCatching { IUserService.Stub.asInterface(binder).exec(command) }
                    r.onSuccess { st.add("Kết quả:\n$it") }
                        .onFailure { st.add("exec lỗi: $it") }
                    handler.removeCallbacks(timeout)
                    finish(r)
                    runCatching { Shizuku.unbindUserService(args, self, true) }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                st.add("UserService ngắt kết nối")
            }
        }
        connRef = conn

        st.add("bindUserService...")
        handler.postDelayed(timeout, TIMEOUT_MS)
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            st.add("bindUserService ném lỗi: $t")
            handler.removeCallbacks(timeout)
            finish(Result.failure(t))
        }
    }

    private fun step(c: String) = "echo '>> $c'; $c 2>&1; echo \"exit=\$?\""

    private fun commandsFor(pkg: String) = listOf(
        "dumpsys deviceidle whitelist +$pkg",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set $pkg 10008 allow",
        "am set-standby-bucket $pkg active"
    )

    /** Nhịp gửi tới GMS để giữ kết nối FCM (thử nghiệm: chưa kiểm chứng trên HyperOS 3.0). */
    fun heartbeatScript(): String = listOf(
        "am broadcast -a com.google.android.intent.action.MCS_HEARTBEAT -p com.google.android.gms",
        "am broadcast -a com.google.android.intent.action.GTALK_HEARTBEAT -p com.google.android.gms"
    ).joinToString("; ") { step(it) }

    /**
     * Đo xem nhịp có tạo thêm lưu lượng trên kết nối FCM không: chụp số byte (ss -i) 3 giây không gửi nhịp,
     * rồi 3 giây có gửi nhịp, và so sánh.
     */
    fun probeScript(): String {
        val ss = "ss -tien 2>&1 | grep -A1 -E ':(5228|5229|5230) '"
        return "echo '##A'; $ss; sleep 3; echo '##B'; $ss; ${heartbeatScript()}; sleep 3; echo '##C'; $ss"
    }

    fun interpretProbe(out: String): String {
        val m = Regex("(?s)##A(.*?)##B(.*?)##C(.*)").find(out)
            ?: return "Không đọc được kết quả đo."
        fun bytes(s: String): Pair<Long, Long>? {
            val sent = Regex("bytes_sent:(\\d+)").find(s)?.groupValues?.get(1)?.toLongOrNull()
            val recv = Regex("bytes_received:(\\d+)").find(s)?.groupValues?.get(1)?.toLongOrNull()
            return if (sent != null && recv != null) sent to recv else null
        }
        val a = bytes(m.groupValues[1])
        val b = bytes(m.groupValues[2])
        val c = bytes(m.groupValues[3])
        if (a == null || b == null || c == null) {
            return "Không đo được: máy thiếu lệnh ss hoặc không có số liệu byte, hoặc GMS không có kết nối FCM lúc đo."
        }
        val ctlS = b.first - a.first
        val ctlR = b.second - a.second
        val hbS = c.first - b.first
        val hbR = c.second - b.second
        if (minOf(minOf(ctlS, ctlR), minOf(hbS, hbR)) < 0) {
            return "Kết nối vừa bị đổi giữa lúc đo nên số liệu không đúng. Đo lại."
        }
        val verdict = if (hbS - ctlS >= 16 || hbR - ctlR >= 16) {
            "Nhịp CÓ tạo thêm lưu lượng trên kết nối FCM, nhiều khả năng có tác dụng."
        } else {
            "Không thấy khác biệt. GMS có thể đang bỏ qua nhịp này."
        }
        return "3 giây không gửi nhịp: +$ctlS byte gửi, +$ctlR byte nhận.\n" +
            "3 giây có gửi nhịp: +$hbS byte gửi, +$hbR byte nhận.\n" + verdict
    }

    /** Whitelist Doze + appops + standby bucket cho các package. */
    fun apply(
        ctx: Context,
        pkgs: List<String>,
        title: String = "Áp dụng",
        onResult: (Result<String>) -> Unit
    ) {
        val cmds = pkgs.flatMap { commandsFor(it) }
        val st = AppLog.begin(title)
        st.add("${pkgs.size} package. ${info()}")
        run(ctx, cmds.joinToString("; ") { step(it) }, st, onResult)
    }

    private fun diagScript(pkgs: List<String>): String {
        val parts = pkgs.map { p ->
            listOf(
                "echo \"##$p\"",
                "echo \"path=\$(pm path $p 2>/dev/null)\"",
                "echo \"pid=\$(pidof $p)\"",
                "echo \"bucket=\$(am get-standby-bucket $p 2>&1)\"",
                "echo \"rib=\$(cmd appops get $p RUN_IN_BACKGROUND 2>&1)\"",
                "echo \"raib=\$(cmd appops get $p RUN_ANY_IN_BACKGROUND 2>&1)\"",
                "echo \"auto=\$(cmd appops get $p 10008 2>&1)\"",
                "echo \"stopped=\$(dumpsys package $p 2>/dev/null | grep -o 'stopped=[a-z]*' | head -1 | cut -d= -f2)\""
            ).joinToString("; ")
        }
        val fcm = "echo \"##fcm\"; cat /proc/net/tcp /proc/net/tcp6 2>&1 | " +
            "grep -E '^ *[0-9]+: [0-9A-Fa-f]+:[0-9A-Fa-f]+ [0-9A-Fa-f]+:[0-9A-Fa-f]+ 01 |denied|No such file'"
        return parts.joinToString("; ") + "; echo \"##whitelist\"; $LIST_CMD; $fcm"
    }

    fun diag(ctx: Context, pkgs: List<String>, onResult: (Result<DiagResult>) -> Unit) {
        run(ctx, diagScript(pkgs), AppLog.begin("Chẩn đoán")) { r ->
            onResult(r.mapCatching { parseDiag(it, pkgs) })
        }
    }

    private fun parseDiag(out: String, pkgs: List<String>): DiagResult {
        val sections = HashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (line in out.lines()) {
            if (line.startsWith("##")) {
                cur = StringBuilder().also { sections[line.substring(2).trim()] = it }
                continue
            }
            cur?.append(line)?.append('\n')
        }
        val wl = sections["whitelist"]?.toString().orEmpty()
        val apps = pkgs.associateWith { pkg ->
            val s = sections[pkg]?.toString().orEmpty()
            fun field(k: String): String {
                val re = Regex("(?ms)^$k=(.*?)(?=^(?:path|pid|bucket|rib|raib|auto|stopped)=|\\z)")
                return re.find(s)?.groupValues?.get(1)?.trim().orEmpty()
            }
            Diag(
                installed = field("path").startsWith("package:"),
                pid = field("pid"),
                bucket = Regex("\\d+").find(field("bucket"))?.value?.toIntOrNull(),
                rib = field("rib"),
                raib = field("raib"),
                auto = field("auto"),
                stopped = when (field("stopped")) {
                    "true" -> true
                    "false" -> false
                    else -> null
                },
                whitelisted = wl.lineSequence().any { it.contains(",$pkg,") }
            )
        }
        return DiagResult(apps, parseFcm(sections["fcm"]?.toString().orEmpty(), wl))
    }

    private fun parseFcm(sec: String, whitelist: String): Fcm {
        val uid = whitelist.lineSequence()
            .firstOrNull { it.contains(",com.google.android.gms,") }
            ?.split(",")?.getOrNull(2)?.trim()?.toIntOrNull()
        val readable = !Regex("denied|No such file|not permitted", RegexOption.IGNORE_CASE)
            .containsMatchIn(sec)
        val conns = ArrayList<Conn>()
        if (uid != null) {
            for (line in sec.lineSequence()) {
                val t = line.trim().split(Regex("\\s+"))
                if (t.size < 8 || t[3] != "01") continue
                if (t[7].toIntOrNull() != uid) continue
                val rem = t[2]
                val port = rem.substringAfterLast(':').toIntOrNull(16) ?: continue
                val lport = t[1].substringAfterLast(':').toIntOrNull(16) ?: 0
                conns.add(Conn(hexIp(rem.substringBeforeLast(':')), port, lport))
            }
        }
        return Fcm(uid, readable, conns)
    }

    private fun hexIp(h: String): String = when {
        h.length == 8 -> (3 downTo 0).joinToString(".") { h.substring(it * 2, it * 2 + 2).toInt(16).toString() }
        h.length == 32 && h.startsWith("0000000000000000FFFF0000", ignoreCase = true) -> hexIp(h.substring(24))
        else -> "IPv6"
    }

    private val autoBusy = AtomicBoolean(false)

    fun applyIfPossible(ctx: Context) {
        if (hasPermission() && autoBusy.compareAndSet(false, true)) {
            apply(ctx, Prefs.allPackages(ctx), "Tự động áp dụng (Shizuku/boot)") { autoBusy.set(false) }
        }
    }
}
