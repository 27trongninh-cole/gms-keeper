package com.ninfinity.gmsdoze

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // Màu theo icon: xanh dương #1F5FBF, xanh lá #2EAD6B
    private val cBlue = Color.parseColor("#1F5FBF")
    private val cNavy = Color.parseColor("#163F87")
    private val cGreen = Color.parseColor("#2EAD6B")
    private val cRed = Color.parseColor("#D64545")
    private val cAmber = Color.parseColor("#C98A00")
    private var cBg = 0
    private var cCard = 0
    private var cText = 0
    private var cSub = 0

    private lateinit var scroll: ScrollView
    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuInfo: TextView
    private val pkgChips = HashMap<String, TextView>()
    private lateinit var button: Button
    private lateinit var hint: TextView
    private lateinit var logToggle: Button
    private lateinit var logCard: LinearLayout
    private lateinit var logText: TextView
    private var running = false

    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        cBg = Color.parseColor(if (night) "#0E1626" else "#F3F6FB")
        cCard = Color.parseColor(if (night) "#172236" else "#FFFFFF")
        cText = Color.parseColor(if (night) "#EAF0FA" else "#14213D")
        cSub = Color.parseColor(if (night) "#9AA8BF" else "#5B6B85")

        buildUi()
        // header xanh đậm => icon status bar luôn màu sáng
        window.insetsController?.setSystemBarsAppearance(
            0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
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

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

    private fun lp(top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            if (weight > 0f) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight
        ).apply { setMargins(dp(16), dp(top), dp(16), 0) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 18)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = lp(top = 14)
    }

    private fun chip(): TextView = TextView(this).apply {
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    private fun setChip(v: TextView, text: String, color: Int) {
        v.text = text
        v.setTextColor(color)
        v.background = rounded((color and 0xFFFFFF) or (0x28 shl 24), 14)
    }

    private fun textButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(cBlue)
        stateListAnimator = null
        background = null
    }

    private fun buildUi() {
        // Header
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cNavy)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(tv("GMS Keeper", 22f, Color.WHITE, bold = true))
        titles.addView(tv("Giữ thông báo Google luôn sống", 13f, Color.parseColor("#BFD6FA")))
        top.addView(titles)
        header.addView(top)

        // Card Shizuku
        val c1 = card()
        c1.addView(tv("SHIZUKU", 11f, cSub, bold = true))
        val r1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        r1.addView(tv("Trạng thái", 15f, cText), lp(weight = 1f).apply { setMargins(0, 0, 0, 0) })
        shizukuChip = chip()
        r1.addView(shizukuChip)
        c1.addView(r1)
        shizukuInfo = tv("", 11f, cSub, mono = true).apply { setPadding(0, dp(8), 0, 0) }
        c1.addView(shizukuInfo)

        // Card Google
        val c2 = card()
        c2.addView(tv("GOOGLE", 11f, cSub, bold = true))
        c2.addView(pkgRow("Google Play Services", "com.google.android.gms"))
        c2.addView(pkgRow("Google Services Framework", "com.google.android.gsf"))

        // Nút chính
        button = Button(this).apply {
            text = "Áp dụng"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = rounded(cBlue, 16)
            minimumHeight = dp(54)
            layoutParams = lp(top = 18)
            setOnClickListener { onButton() }
        }
        hint = tv("", 13f, cSub).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), 0)
        }

        // Log ẩn
        logToggle = textButton("Hiện log").apply {
            layoutParams = lp(top = 6)
            setOnClickListener { toggleLog() }
        }
        logCard = card().apply { visibility = View.GONE }
        logText = tv(AppLog.text(), 11f, cText, mono = true).apply { setTextIsSelectable(true) }
        logCard.addView(logText)
        logCard.addView(textButton("Sao chép log").apply {
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text()))
                Toast.makeText(this@MainActivity, "Đã sao chép log", Toast.LENGTH_SHORT).show()
            }
        })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(c1)
        content.addView(c2)
        content.addView(button)
        content.addView(hint)
        content.addView(logToggle)
        content.addView(logCard)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(header)
        col.addView(content)

        scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(col)
        }
        // targetSdk 35 => edge-to-edge: header phủ dưới status bar, nội dung chừa nav bar
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(dp(20), bars.top + dp(20), dp(20), dp(28))
            content.setPadding(0, 0, 0, bars.bottom + dp(24))
            insets
        }
        setContentView(scroll)
    }

    private fun pkgRow(name: String, pkg: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(tv(name, 15f, cText))
        labels.addView(tv(pkg, 11f, cSub, mono = true))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val c = chip()
        setChip(c, "—", cSub)
        pkgChips[pkg] = c
        row.addView(c)
        return row
    }

    private fun toggleLog() {
        val show = logCard.visibility != View.VISIBLE
        logCard.visibility = if (show) View.VISIBLE else View.GONE
        logToggle.text = if (show) "Ẩn log" else "Hiện log"
        if (show) {
            logText.text = AppLog.text()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------- Logic ----------

    private fun setButtonEnabled(enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.5f
    }

    private fun clearChips() = Doze.PACKAGES.forEach { pkgChips[it]?.let { c -> setChip(c, "—", cSub) } }

    private fun onButton() {
        if (running) return
        when {
            !Doze.shizukuReady() -> {
                hint.setTextColor(cRed)
                hint.text = "Shizuku chưa chạy. Mở app Shizuku và khởi động nó."
            }
            !Doze.hasPermission() -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    hint.setTextColor(cRed)
                    hint.text = "Quyền bị từ chối. Vào app Shizuku để cấp lại cho GMS Keeper."
                } else {
                    Shizuku.requestPermission(1001)
                }
            }
            else -> {
                running = true
                setButtonEnabled(false)
                hint.setTextColor(cSub)
                hint.text = "Đang áp dụng..."
                Doze.apply(this) { r ->
                    runOnUiThread {
                        running = false
                        setButtonEnabled(true)
                        showResult(r, applied = true)
                    }
                }
            }
        }
    }

    private fun refresh() {
        val ready = Doze.shizukuReady()
        val perm = Doze.hasPermission()
        when {
            !ready -> {
                setChip(shizukuChip, "Chưa chạy", cRed)
                shizukuInfo.text = "Mở app Shizuku và khởi động nó"
                button.text = "Áp dụng"
                clearChips()
            }
            !perm -> {
                setChip(shizukuChip, "Chưa cấp quyền", cAmber)
                shizukuInfo.text = Doze.info()
                button.text = "Cấp quyền Shizuku"
                clearChips()
            }
            else -> {
                setChip(shizukuChip, "Sẵn sàng", cGreen)
                shizukuInfo.text = Doze.info()
                button.text = "Áp dụng"
                Doze.readStatus(this) { r -> runOnUiThread { showResult(r, applied = false) } }
            }
        }
    }

    private fun showResult(r: Result<String>, applied: Boolean) {
        r.onSuccess { out ->
            var all = true
            Doze.PACKAGES.forEach { pkg ->
                val ok = Doze.isWhitelisted(out, pkg)
                if (!ok) all = false
                pkgChips[pkg]?.let { c ->
                    if (ok) setChip(c, "Đã whitelist", cGreen) else setChip(c, "Chưa", cRed)
                }
            }
            if (applied) {
                val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                if (all) {
                    hint.setTextColor(cGreen)
                    hint.text = "Đã áp dụng lúc $t"
                } else {
                    hint.setTextColor(cAmber)
                    hint.text = "Đã chạy lệnh nhưng chưa thấy trong whitelist. Xem log để biết lý do."
                }
            }
        }.onFailure {
            clearChips()
            hint.setTextColor(cRed)
            hint.text = "Lỗi: ${it.message}. Mở log để xem chi tiết."
        }
    }
}
