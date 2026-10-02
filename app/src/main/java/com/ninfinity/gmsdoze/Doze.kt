package com.ninfinity.gmsdoze

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

object Doze {
    val GOOGLE = listOf("com.google.android.gms")

    private const val LIST_CMD = "dumpsys deviceidle whitelist"

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

    /** Shizuku.newProcess là private trong API 13 nên gọi bằng reflection; lệnh chạy trong server Shizuku (uid shell). */
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

    /** Chạy 1 lệnh shell ở luồng nền, ghi kết quả vào stage log. Gọi onResult đúng 1 lần. */
    fun run(command: String, st: AppLog.Stage, onResult: (Result<String>) -> Unit) {
        Thread {
            val r = runCatching { execViaNewProcess(command) }
            r.onSuccess { st.add("Kết quả:\n$it") }
                .onFailure { st.add("Lỗi: $it") }
            st.ok = r.isSuccess
            onResult(r)
        }.start()
    }

    private fun step(c: String) = "echo '>> $c'; $c 2>&1; echo \"exit=\$?\""

    private fun commandsFor(pkg: String) = listOf(
        "dumpsys deviceidle whitelist +$pkg",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set $pkg 10008 allow",
        "am set-standby-bucket $pkg active"
    )

    /** Whitelist Doze + appops (chạy nền, tự khởi động Xiaomi) + standby bucket cho các package. */
    fun apply(pkgs: List<String>, title: String = "Áp dụng", onResult: (Result<String>) -> Unit) {
        val cmds = pkgs.flatMap { commandsFor(it) }
        val st = AppLog.begin(title)
        st.add("${pkgs.size} package. ${info()}")
        run(cmds.joinToString("; ") { step(it) }, st, onResult)
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

    fun diag(pkgs: List<String>, onResult: (Result<DiagResult>) -> Unit) {
        run(diagScript(pkgs), AppLog.begin("Chẩn đoán")) { r ->
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

    /** Gọi khi Shizuku vừa lên hoặc sau boot: áp dụng lại nếu đã được cấp quyền. */
    fun applyIfPossible(ctx: Context) {
        if (hasPermission() && autoBusy.compareAndSet(false, true)) {
            apply(Prefs.allPackages(ctx), "Tự động áp dụng (Shizuku/boot)") { autoBusy.set(false) }
        }
    }
}
