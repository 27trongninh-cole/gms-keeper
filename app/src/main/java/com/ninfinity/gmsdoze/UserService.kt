package com.ninfinity.gmsdoze

import kotlin.system.exitProcess

/** Chạy trong process riêng với quyền shell (uid 2000) do Shizuku khởi tạo. */
class UserService : IUserService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }
}
