package com.ninfinity.gmsdoze

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object Doze {
    val PACKAGES = listOf("com.google.android.gms", "com.google.android.gsf")

    private const val LIST_CMD = "dumpsys deviceidle whitelist"
    private const val TIMEOUT_MS = 10_000L

    fun shizukuReady(): Boolean =
        !Shizuku.isPreV11() && Shizuku.pingBinder()

    fun hasPermission(): Boolean =
        shizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun info(): String = runCatching {
        "Shizuku v${Shizuku.getVersion()}, uid=${Shizuku.getUid()} " +
            "(2000=adb/shell, 0=root), perm=${hasPermission()}"
    }.getOrElse { "Shizuku info lỗi: ${it.message}" }

    /** Chạy 1 lệnh shell qua Shizuku UserService. Luôn gọi onResult đúng 1 lần (kể cả timeout). */
    fun run(ctx: Context, command: String, onResult: (Result<String>) -> Unit) {
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

    /** Whitelist Doze + appops + standby bucket cho GMS/GSF, rồi trả về danh sách mới. */
    fun apply(ctx: Context, onResult: (Result<String>) -> Unit) {
        val cmds = PACKAGES.flatMap { pkg ->
            listOf(
                "dumpsys deviceidle whitelist +$pkg",
                "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket $pkg active"
            )
        } + LIST_CMD
        AppLog.add("Áp dụng. ${info()}")
        run(ctx, cmds.joinToString("; ") { step(it) }, onResult)
    }

    fun readStatus(ctx: Context, onResult: (Result<String>) -> Unit) = run(ctx, step(LIST_CMD), onResult)

    /** Output dạng "user,com.google.android.gms,10123" hoặc "system,..." */
    fun isWhitelisted(output: String, pkg: String): Boolean =
        output.lineSequence().any { it.contains(",$pkg,") }

    fun applyIfPossible(ctx: Context) {
        if (hasPermission()) {
            AppLog.add("Tự động áp dụng (binder/boot)")
            apply(ctx) {}
        }
    }
}
