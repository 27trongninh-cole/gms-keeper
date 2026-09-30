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
        val stopped: Boolean?,
        val whitelisted: Boolean
    )

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
    fun run(ctx: Context, command: String, onResult: (Result<String>) -> Unit) {
        Thread {
            val r = runCatching { execViaNewProcess(command) }
            if (r.isSuccess) {
                AppLog.add("newProcess OK. Kết quả:\n${r.getOrNull()}")
                onResult(r)
            } else {
                AppLog.add("newProcess lỗi: ${r.exceptionOrNull()}. Thử UserService...")
                runViaUserService(ctx, command, onResult)
            }
        }.start()
    }

    /** Cách 2 (fallback): Shizuku UserService. Luôn gọi onResult đúng 1 lần (kể cả timeout). */
    private fun runViaUserService(ctx: Context, command: String, onResult: (Result<String>) -> Unit) {
        val app = ctx.applicationContext
        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var connRef: ServiceConnection? = null

        val args = Shizuku.UserServiceArgs(
            ComponentName(app.packageName, UserService::class.java.name)
        ).processNameSuffix("shell").daemon(false).version(2)

        fun finish(r: Result<String>) {
            if (done.compareAndSet(false, true)) onResult(r)
        }

        val timeout = Runnable {
            AppLog.add("TIMEOUT ${TIMEOUT_MS / 1000}s: UserService không kết nối được")
            finish(Result.failure(RuntimeException("Timeout: UserService không kết nối")))
            connRef?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                AppLog.add("UserService đã kết nối, đang chạy lệnh...")
                val self = this
                Thread {
                    val r = runCatching { IUserService.Stub.asInterface(binder).exec(command) }
                    r.onSuccess { AppLog.add("Kết quả:\n$it") }
                        .onFailure { AppLog.add("exec lỗi: $it") }
                    handler.removeCallbacks(timeout)
                    finish(r)
                    runCatching { Shizuku.unbindUserService(args, self, true) }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                AppLog.add("UserService ngắt kết nối")
            }
        }
        connRef = conn

        AppLog.add("bindUserService...")
        handler.postDelayed(timeout, TIMEOUT_MS)
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            AppLog.add("bindUserService ném lỗi: $t")
            handler.removeCallbacks(timeout)
            finish(Result.failure(t))
        }
    }

    private fun step(c: String) = "echo '>> $c'; $c 2>&1; echo \"exit=\$?\""

    private fun commandsFor(pkg: String) = listOf(
        "dumpsys deviceidle whitelist +$pkg",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "am set-standby-bucket $pkg active"
    )

    /** Whitelist Doze + appops + standby bucket cho các package. */
    fun apply(ctx: Context, pkgs: List<String>, onResult: (Result<String>) -> Unit) {
        val cmds = pkgs.flatMap { commandsFor(it) }
        AppLog.add("Áp dụng cho ${pkgs.size} package. ${info()}")
        run(ctx, cmds.joinToString("; ") { step(it) }, onResult)
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
                "echo \"stopped=\$(dumpsys package $p 2>/dev/null | grep -o 'stopped=[a-z]*' | head -1 | cut -d= -f2)\""
            ).joinToString("; ")
        }
        return parts.joinToString("; ") + "; echo \"##whitelist\"; $LIST_CMD"
    }

    fun diag(ctx: Context, pkgs: List<String>, onResult: (Result<Map<String, Diag>>) -> Unit) {
        run(ctx, diagScript(pkgs)) { r ->
            onResult(r.mapCatching { parseDiag(it, pkgs) })
        }
    }

    private fun parseDiag(out: String, pkgs: List<String>): Map<String, Diag> {
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
        return pkgs.associateWith { pkg ->
            val s = sections[pkg]?.toString().orEmpty()
            fun field(k: String): String {
                val re = Regex("(?ms)^$k=(.*?)(?=^(?:path|pid|bucket|rib|raib|stopped)=|\\z)")
                return re.find(s)?.groupValues?.get(1)?.trim().orEmpty()
            }
            Diag(
                installed = field("path").startsWith("package:"),
                pid = field("pid"),
                bucket = Regex("\\d+").find(field("bucket"))?.value?.toIntOrNull(),
                rib = field("rib"),
                raib = field("raib"),
                stopped = when (field("stopped")) {
                    "true" -> true
                    "false" -> false
                    else -> null
                },
                whitelisted = wl.lineSequence().any { it.contains(",$pkg,") }
            )
        }
    }

    private val autoBusy = AtomicBoolean(false)

    fun applyIfPossible(ctx: Context) {
        if (hasPermission() && autoBusy.compareAndSet(false, true)) {
            AppLog.add("Tự động áp dụng (binder/boot)")
            apply(ctx, Prefs.allPackages(ctx)) { autoBusy.set(false) }
        }
    }
}
