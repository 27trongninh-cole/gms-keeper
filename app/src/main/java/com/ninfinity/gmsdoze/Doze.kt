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

    private val HEARTBEAT_ACTIONS = listOf(
        "com.google.android.intent.action.MCS_HEARTBEAT",
        "com.google.android.intent.action.GTALK_HEARTBEAT"
    )

    /** Kiểm tra GMS/GSF có khai báo receiver tĩnh cho hai action nhịp hay không. */
    fun receiverScript(): String =
        HEARTBEAT_ACTIONS.joinToString("; ") { "echo '##$it'; cmd package query-receivers --brief -a $it 2>&1" }

    fun interpretReceivers(out: String): String {
        if (Regex("unknown command|usage:|Error:|not found", RegexOption.IGNORE_CASE).containsMatchIn(out) &&
            !out.contains("com.google.android")
        ) {
            return "Không kiểm tra được: máy không hỗ trợ lệnh query-receivers."
        }
        val lines = ArrayList<String>()
        var gms = false
        for (a in HEARTBEAT_ACTIONS) {
            val sec = Regex("(?s)##" + Regex.escape(a) + "(.*?)(?=##|\\z)").find(out)?.groupValues?.get(1).orEmpty()
            val short = a.substringAfterLast('.')
            when {
                sec.contains("com.google.android.gms") -> { gms = true; lines.add("$short: GMS có receiver") }
                sec.contains("com.google.android.gsf") -> lines.add("$short: chỉ GSF có receiver")
                else -> lines.add("$short: không có receiver tĩnh nào")
            }
        }
        val verdict = if (gms) {
            "GMS có khai báo receiver cho nhịp, nên lệnh nhiều khả năng được xử lý. Cần chạy thử dài hạn để biết có giúp ích không."
        } else {
            "Không thấy receiver tĩnh nào của GMS. Có thể GMS đăng ký động (không kiểm tra được từ ngoài) nhưng khả năng thấp. Chưa nên bật Giữ nhịp liên tục."
        }
        return lines.joinToString("\n") + "\n\n" + verdict
    }

    /** Chụp trạng thái chặn mạng nền của hệ thống (netpolicy/firewall/Doze) để phân tích. */
    fun netScript(pkgs: List<String>): String {
        val loop = pkgs.joinToString("; ") {
            "u=\$(pm list packages -U $it 2>/dev/null | grep \"package:$it \" | sed 's/.*uid://'); " +
                "echo \"uid $it=\$u\"; [ -n \"\$u\" ] && ALL=\"\$ALL|\$u\""
        }
        return "ALL=99999999; $loop; " +
            "echo '##netpolicy'; dumpsys netpolicy 2>&1 | grep -E \"Restrict|Device idle|Low Power|Restricted networking|UID=(\$ALL) \"; " +
            "echo '##trafficcontroller'; dumpsys connectivity trafficcontroller 2>&1 | head -60; " +
            "echo '##trafficcontroller-uids'; dumpsys connectivity trafficcontroller 2>&1 | grep -E \"(^|[^0-9])(\$ALL)([^0-9]|\$)\" | head -40; " +
            "echo '##deviceidle'; dumpsys deviceidle 2>&1 | grep -E 'mState=|mLightState=|mDeepEnabled|mLightEnabled|mScreenOn=|mCharging=|mForceIdle=|mNetworkConnected=' | head -12"
    }

    /** Bản gọn để chạy trong receiver (vài giây): màn hình, Doze, chính sách mạng, whitelist, kết nối TCP. */
    fun timedScript(): String =
        "echo '##screen'; dumpsys power 2>&1 | grep -E 'mWakefulness=|Display Power: state=' | head -3; " +
            "echo '##deviceidle'; dumpsys deviceidle 2>&1 | grep -E 'mState=|mLightState=|mScreenOn=|mNetworkConnected=' | head -6; " +
            "echo '##netpolicy'; dumpsys netpolicy 2>&1 | grep -E 'Restrict background|Restrict power|Device idle|Low Power Standby|Restricted networking'; " +
            "echo '##whitelist'; dumpsys deviceidle whitelist 2>&1 | grep -E '^user,'; " +
            "echo '##fcm'; cat /proc/net/tcp /proc/net/tcp6 2>&1 | " +
            "grep -E '^ *[0-9]+: [0-9A-Fa-f]+:[0-9A-Fa-f]+ [0-9A-Fa-f]+:[0-9A-Fa-f]+ 01 |denied|No such file'"

    fun sectionsOf(out: String): Map<String, String> {
        val map = LinkedHashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (line in out.lines()) {
            if (line.startsWith("##")) {
                cur = StringBuilder().also { map[line.substring(2).trim()] = it }
                continue
            }
            cur?.append(line)?.append('\n')
        }
        return map.mapValues { it.value.toString() }
    }

    fun parseFcmFrom(out: String): Fcm {
        val s = sectionsOf(out)
        return parseFcm(s["fcm"].orEmpty(), s["whitelist"].orEmpty())
    }

    /** Các kết nối TCP ESTABLISHED gom theo uid (từ dòng /proc/net/tcp*). */
    fun connsByUid(sec: String): Map<Int, List<Conn>> {
        val map = HashMap<Int, MutableList<Conn>>()
        for (line in sec.lineSequence()) {
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 8 || t[3] != "01") continue
            val uid = t[7].toIntOrNull() ?: continue
            val rem = t[2]
            val port = rem.substringAfterLast(':').toIntOrNull(16) ?: continue
            val lport = t[1].substringAfterLast(':').toIntOrNull(16) ?: 0
            map.getOrPut(uid) { ArrayList() }.add(Conn(hexIp(rem.substringBeforeLast(':')), port, lport))
        }
        return map
    }

    fun netSnapshot(ctx: Context, pkgs: List<String>, onDone: () -> Unit) {
        val st = AppLog.begin("Trạng thái mạng")
        run(ctx, netScript(pkgs), st) { onDone() }
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
