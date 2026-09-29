package com.ninfinity.gmsdoze

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var shizukuText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var button: Button

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        AppLog.listener = { runOnUiThread { logText.text = AppLog.text() } }
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        AppLog.listener = null
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "GMS Keeper"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        shizukuText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
        }
        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(4), 0, dp(8))
        }
        button = Button(this).apply {
            text = "Áp dụng"
            setOnClickListener { onButton() }
        }
        val copy = Button(this).apply {
            text = "Sao chép log"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text()))
                Toast.makeText(this@MainActivity, "Đã sao chép log", Toast.LENGTH_SHORT).show()
            }
        }
        logText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = AppLog.text()
        }
        root.addView(title)
        root.addView(shizukuText)
        root.addView(statusText)
        root.addView(button)
        root.addView(copy)
        root.addView(ScrollView(this).apply {
            setPadding(0, dp(12), 0, 0)
            addView(logText)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        // targetSdk 35 => edge-to-edge, phải tự chừa status/nav bar
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(dp(20), bars.top + dp(20), dp(20), bars.bottom + dp(20))
            insets
        }
        setContentView(root)
    }

    private fun onButton() {
        when {
            !Doze.shizukuReady() -> {
                AppLog.add("Shizuku chưa chạy. Mở app Shizuku và khởi động nó.")
            }
            !Doze.hasPermission() -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    AppLog.add("Quyền bị từ chối. Vào app Shizuku → cấp quyền cho GMS Keeper.")
                } else {
                    Shizuku.requestPermission(1001)
                }
            }
            else -> {
                button.isEnabled = false
                statusText.text = "Đang chạy..."
                Doze.apply(this) { r ->
                    runOnUiThread { showResult(r); button.isEnabled = true }
                }
            }
        }
    }

    private fun refresh() {
        when {
            !Doze.shizukuReady() -> {
                shizukuText.text = "Shizuku: chưa chạy"
                statusText.text = ""
                button.text = "Áp dụng"
            }
            !Doze.hasPermission() -> {
                shizukuText.text = "Shizuku: đang chạy, chưa cấp quyền"
                statusText.text = ""
                button.text = "Cấp quyền Shizuku"
            }
            else -> {
                shizukuText.text = "Shizuku: sẵn sàng (${Doze.info()})"
                button.text = "Áp dụng"
                Doze.readStatus(this) { r -> runOnUiThread { showResult(r) } }
            }
        }
    }

    private fun showResult(r: Result<String>) {
        r.onSuccess { out ->
            statusText.text = Doze.PACKAGES.joinToString("\n") { pkg ->
                (if (Doze.isWhitelisted(out, pkg)) "✅ " else "❌ ") + pkg
            }
        }.onFailure {
            statusText.text = "❌ Lỗi: ${it.message}"
        }
    }
}
