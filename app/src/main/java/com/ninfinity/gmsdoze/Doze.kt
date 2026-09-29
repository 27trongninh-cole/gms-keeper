package com.ninfinity.gmsdoze

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object Doze {
    val PACKAGES = listOf("com.google.android.gms", "com.google.android.gsf")

    private const val LIST_CMD = "dumpsys deviceidle whitelist"

    fun shizukuReady(): Boolean =
        !Shizuku.isPreV11() && Shizuku.pingBinder()

    fun hasPermission(): Boolean =
        shizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Chạy 1 lệnh shell qua Shizuku, trả kết quả ở luồng nền. */
    fun run(ctx: Context, command: String, onResult: (Result<String>) -> Unit) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, UserService::class.java.name)
        ).processNameSuffix("shell").daemon(false).version(1)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val self = this
                Thread {
                    val r = runCatching { IUserService.Stub.asInterface(binder).exec(command) }
                    onResult(r)
                    runCatching { Shizuku.unbindUserService(args, self, true) }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            onResult(Result.failure(t))
        }
    }

    /** Thêm GMS + GSF vào whitelist rồi trả về danh sách mới. */
    fun apply(ctx: Context, onResult: (Result<String>) -> Unit) {
        val cmd = PACKAGES.joinToString("; ") { pkg ->
            listOf(
                "dumpsys deviceidle whitelist +$pkg",
                "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket $pkg active"
            ).joinToString("; ")
        } + "; $LIST_CMD"
        run(ctx, cmd, onResult)
    }

    fun readStatus(ctx: Context, onResult: (Result<String>) -> Unit) = run(ctx, LIST_CMD, onResult)

    /** Output dạng "user,com.google.android.gms,10123" hoặc "system,..." */
    fun isWhitelisted(output: String, pkg: String): Boolean =
        output.lineSequence().any { it.contains(",$pkg,") }

    fun applyIfPossible(ctx: Context) {
        if (hasPermission()) apply(ctx) {}
    }
}
