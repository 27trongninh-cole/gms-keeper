package com.ninfinity.gmsdoze

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var shizukuText: TextView
    private lateinit var listText: TextView
    private lateinit var button: Button

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val title = TextView(this).apply {
            text = "GMS Keeper"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        }
        shizukuText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, dp(8))
        }
        button = Button(this).apply {
            text = "Áp dụng"
            setOnClickListener { onButton() }
        }
        listText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(title)
        root.addView(shizukuText)
        root.addView(button)
        root.addView(ScrollView(this).apply { addView(listText) })

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
                shizukuText.text = "Shizuku chưa chạy. Mở app Shizuku và khởi động nó trước."
            }
            !Doze.hasPermission() -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    shizukuText.text = "Quyền bị từ chối. Vào app Shizuku → cấp quyền cho app này."
                } else {
                    Shizuku.requestPermission(1001)
                }
            }
            else -> {
                button.isEnabled = false
                Doze.apply(this) { r -> runOnUiThread { showResult(r); button.isEnabled = true } }
            }
        }
    }

    private fun refresh() {
        when {
            !Doze.shizukuReady() -> {
                shizukuText.text = "Shizuku: chưa chạy"
                button.text = "Áp dụng"
                listText.text = ""
            }
            !Doze.hasPermission() -> {
                shizukuText.text = "Shizuku: đang chạy, chưa cấp quyền"
                button.text = "Cấp quyền Shizuku"
                listText.text = ""
            }
            else -> {
                shizukuText.text = "Shizuku: sẵn sàng"
                button.text = "Áp dụng"
                Doze.readStatus(this) { r -> runOnUiThread { showResult(r) } }
            }
        }
    }

    private fun showResult(r: Result<String>) {
        r.onSuccess { out ->
            val lines = Doze.PACKAGES.joinToString("\n") { pkg ->
                (if (Doze.isWhitelisted(out, pkg)) "✅ " else "❌ ") + pkg
            }
            listText.text = lines + "\n\n" + out.lineSequence()
                .filter { it.contains("google") || it.startsWith("user") }
                .joinToString("\n")
        }.onFailure {
            listText.text = "Lỗi: ${it.message}"
        }
    }
}
