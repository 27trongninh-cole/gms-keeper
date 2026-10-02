package com.ninfinity.gmsdoze

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
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

    private enum class St { OK, PARTIAL, STOPPED, MISSING }

    private lateinit var scroll: ScrollView
    private lateinit var header: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuInfo: TextView
    private lateinit var appsBox: LinearLayout
    private val pkgChips = HashMap<String, TextView>()
    private var lastDiag: Map<String, Doze.Diag> = emptyMap()
    private var lastFcm: Doze.Fcm? = null
    private var lastTrack: Prefs.FcmTrack? = null
    private lateinit var fcmChip: TextView
    private lateinit var button: Button
    private lateinit var hint: TextView
    private lateinit var logToggle: Button
    private lateinit var logCard: LinearLayout
    private lateinit var logList: LinearLayout
    private var logRenderPending = false
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
        AppLog.listener = { runOnUiThread { scheduleLogRender() } }
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
        titles.addView(tv("Giữ thông báo luôn sống", 13f, Color.parseColor("#BFD6FA")))
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
        c2.addView(pkgRow("Google Play Services", "com.google.android.gms", false))
        c2.addView(fcmRow())

        // Card ứng dụng bảo vệ
        val c3 = card()
        c3.addView(tv("ỨNG DỤNG BẢO VỆ", 11f, cSub, bold = true))
        c3.addView(tv("Chạm để xem chi tiết · giữ để bỏ", 11f, cSub).apply { setPadding(0, dp(2), 0, 0) })
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c3.addView(appsBox)
        c3.addView(textButton("＋ Thêm ứng dụng").apply { setOnClickListener { showPicker() } })

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
        logCard.addView(tv("NHẬT KÝ", 11f, cSub, bold = true))
        logCard.addView(tv("Mỗi giai đoạn có nút sao chép riêng · chạm nội dung để mở rộng", 11f, cSub).apply {
            setPadding(0, dp(2), 0, 0)
        })
        logList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logCard.addView(logList)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(c1)
        content.addView(c2)
        content.addView(c3)
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
        rebuildAppRows()
        setContentView(scroll)
    }

    private fun pkgRow(name: String, pkg: String, removable: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            setOnClickListener { showDetail(pkg, name) }
            if (removable) setOnLongClickListener { confirmRemove(pkg, name); true }
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

    private fun fcmRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            setOnClickListener { showFcmDetail() }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(tv("Kết nối FCM", 15f, cText))
        labels.addView(tv("GMS → máy chủ Google (5228–5230)", 11f, cSub, mono = true))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fcmChip = chip()
        setChip(fcmChip, "—", cSub)
        row.addView(fcmChip)
        return row
    }

    private enum class FcmSt { OK, FALLBACK, DOWN, UNKNOWN }

    private fun fcmStateOf(f: Doze.Fcm) = when {
        !f.readable || f.uid == null -> FcmSt.UNKNOWN
        f.conns.any { it.port in 5228..5230 } -> FcmSt.OK
        f.conns.isNotEmpty() -> FcmSt.FALLBACK
        else -> FcmSt.DOWN
    }

    private fun renderFcm(f: Doze.Fcm) {
        when (fcmStateOf(f)) {
            FcmSt.OK -> setChip(fcmChip, "Đang kết nối", cGreen)
            FcmSt.FALLBACK -> setChip(fcmChip, "Cổng dự phòng", cAmber)
            FcmSt.DOWN -> setChip(fcmChip, "Không có kết nối", cRed)
            FcmSt.UNKNOWN -> setChip(fcmChip, "Không đọc được", cSub)
        }
    }

    private fun fcmDetailText(f: Doze.Fcm): String {
        val fcmPort = f.conns.count { it.port in 5228..5230 }
        val p443 = f.conns.count { it.port == 443 }
        val lines = ArrayList<String>()
        lines.add("UID của GMS: ${f.uid ?: "không rõ"}")
        lines.add("Kết nối TCP đang mở: ${f.conns.size}")
        lines.add("Tới cổng 5228–5230: $fcmPort")
        lines.add("Tới cổng 443: $p443")
        f.conns.take(8).forEach { lines.add("  ${it.ip}:${it.port}  (cổng nguồn ${it.lport})") }
        val tr = lastTrack
        if (tr != null && fcmStateOf(f) == FcmSt.OK) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val mins = (System.currentTimeMillis() - tr.sinceMs) / 60000
            lines.add("Kết nối này thấy lần đầu: ${fmt.format(Date(tr.sinceMs))} (${mins} phút trước)")
            lines.add(
                "Số lần kết nối bị đổi: ${tr.changes}" +
                    if (tr.lastChangeMs > 0) " (gần nhất ${fmt.format(Date(tr.lastChangeMs))})" else ""
            )
        }
        val advice = when (fcmStateOf(f)) {
            FcmSt.OK ->
                "GMS đang giữ kết nối FCM tới Google. Cách dùng: kiểm tra trước khi tắt màn hình, rồi kiểm tra lại ngay khi thông báo trễ đến. Nếu mốc \"thấy lần đầu\" đổi hoặc số lần đổi tăng thì kết nối đã bị ngắt và nối lại giữa chừng (nghi mạng hoặc nhà mạng cắt kết nối rảnh). Nếu giữ nguyên thì kết nối vẫn sống và thông báo trễ do chỗ khác."
            FcmSt.FALLBACK ->
                "GMS có kết nối nhưng không tới cổng 5228–5230. FCM đang đi qua cổng dự phòng vì cổng chính bị chặn (router, nhà mạng hoặc VPN). Kiểu này chậm và hay rớt. Tắt WARP/VPN, thử mạng khác."
            FcmSt.DOWN ->
                "GMS không có kết nối TCP nào đang mở, tức FCM đang đứt. Thử tắt WARP/VPN, bật rồi tắt chế độ máy bay. Nếu lặp lại sau mỗi lần tắt màn hình thì nghi HyperOS cắt mạng nền của GMS."
            FcmSt.UNKNOWN ->
                "Máy không cho đọc /proc/net nên không kiểm tra được kết nối."
        }
        return lines.joinToString("\n") + "\n\n" + advice +
            "\n\nĐây là ảnh chụp tại thời điểm bấm, kết nối có thể thay đổi khi màn hình tắt."
    }

    private fun showFcmDetail() {
        val f = lastFcm
        AlertDialog.Builder(this)
            .setTitle("Kết nối FCM")
            .setMessage(if (f == null) "Chưa có dữ liệu. Thử lại sau vài giây." else fcmDetailText(f))
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun rebuildAppRows() {
        pkgChips.keys.retainAll(Doze.GOOGLE.toSet())
        appsBox.removeAllViews()
        val apps = Prefs.apps(this)
        if (apps.isEmpty()) {
            appsBox.addView(tv("Chưa có ứng dụng nào", 13f, cSub).apply { setPadding(0, dp(12), 0, 0) })
        }
        apps.forEach { appsBox.addView(pkgRow(labelOf(it), it, true)) }
    }

    private fun toggleLog() {
        val show = logCard.visibility != View.VISIBLE
        logCard.visibility = if (show) View.VISIBLE else View.GONE
        logToggle.text = if (show) "Ẩn log" else "Hiện log"
        if (show) renderLog()
    }

    // ---------- Log theo giai đoạn ----------

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setTextColor(cBlue)
        stateListAnimator = null
        background = rounded((cBlue and 0xFFFFFF) or (0x22 shl 24), 12)
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun copyToClipboard(text: String, label: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", text))
        Toast.makeText(this, "Đã sao chép: $label", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleLogRender() {
        if (logCard.visibility != View.VISIBLE || logRenderPending) return
        logRenderPending = true
        logList.postDelayed({
            logRenderPending = false
            renderLog()
        }, 200)
    }

    private fun renderLog() {
        logList.removeAllViews()
        val stages = AppLog.all().asReversed() // mới nhất ở trên
        if (stages.isEmpty()) {
            logList.addView(tv("Chưa có log", 13f, cSub).apply { setPadding(0, dp(10), 0, 0) })
            return
        }
        stages.forEach { logList.addView(stageView(it)) }
    }

    private fun stageView(st: AppLog.Stage): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded((cText and 0xFFFFFF) or (0x12 shl 24), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, 0) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = when (st.ok) { true -> "✓"; false -> "✗"; null -> "…" }
        val markColor = when (st.ok) { true -> cGreen; false -> cRed; null -> cSub }
        head.addView(
            tv("${st.time}  ${st.title}", 12f, cText, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(tv(mark, 14f, markColor, bold = true).apply { setPadding(dp(8), 0, dp(8), 0) })
        head.addView(smallButton("Sao chép") { copyToClipboard(st.copyText(), st.title) })

        val body = tv(st.body(), 11f, cSub, mono = true).apply {
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
            var expanded = false
            setOnClickListener {
                expanded = !expanded
                maxLines = if (expanded) Int.MAX_VALUE else 6
            }
        }
        box.addView(head)
        box.addView(body)
        return box
    }

    // ---------- Thêm / bỏ app ----------

    private fun showPicker() {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val items = pm.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName && it.first !in Doze.GOOGLE }
            .sortedBy { it.second.lowercase() }
        val current = Prefs.apps(this).toMutableSet()
        val checked = BooleanArray(items.size) { items[it].first in current }
        AlertDialog.Builder(this)
            .setTitle("Chọn ứng dụng cần bảo vệ")
            .setMultiChoiceItems(items.map { it.second }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton("Lưu") { _, _ ->
                // giữ lại app đã lưu nhưng không hiện trong danh sách launcher
                val visible = items.map { it.first }.toSet()
                val kept = current.filter { it !in visible }
                val picked = items.filterIndexed { i, _ -> checked[i] }.map { it.first }
                Prefs.setApps(this, kept + picked)
                rebuildAppRows()
                refresh()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun confirmRemove(pkg: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Bỏ $name?")
            .setMessage("Ứng dụng sẽ không còn được áp dụng whitelist. Cài đặt đã áp dụng trước đó vẫn giữ nguyên trong hệ thống.")
            .setPositiveButton("Bỏ") { _, _ ->
                Prefs.setApps(this, Prefs.apps(this).filter { it != pkg })
                rebuildAppRows()
                refresh()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ---------- Chi tiết chẩn đoán ----------

    private fun allowed(s: String) = s.contains("allow", ignoreCase = true)

    private fun opName(s: String) = when {
        allowed(s) -> "cho phép"
        s.contains("ignore", true) || s.contains("deny", true) || s.contains("error", true) -> "bị chặn"
        else -> "mặc định"
    }

    private fun bucketName(b: Int?) = when (b) {
        5 -> "Exempted (5)"
        10 -> "Active (10)"
        20 -> "Working set (20)"
        30 -> "Frequent (30)"
        40 -> "Rare (40)"
        45 -> "Restricted (45)"
        50 -> "Never (50)"
        null -> "không rõ"
        else -> "$b"
    }

    private fun isLaunchable(pkg: String) = packageManager.getLaunchIntentForPackage(pkg) != null

    private fun autoBlocked(s: String) = s.contains("ignore", true) || s.contains("deny", true)

    private fun autoName(s: String) = when {
        allowed(s) -> "cho phép"
        autoBlocked(s) -> "bị chặn"
        else -> "không rõ"
    }

    private fun stateOf(pkg: String, d: Doze.Diag) = when {
        !d.installed -> St.MISSING
        // App không có màn hình mở được (vd GSF) thì force-stop không phải lỗi người dùng sửa được
        d.stopped == true && isLaunchable(pkg) -> St.STOPPED
        !d.whitelisted || !allowed(d.raib) || autoBlocked(d.auto) -> St.PARTIAL
        else -> St.OK
    }

    private fun detailText(pkg: String, d: Doze.Diag): String {
        val launchable = isLaunchable(pkg)
        val lines = listOf(
            "Tiến trình: " + if (d.pid.isNotBlank()) "đang chạy (pid ${d.pid.split(" ").first()})" else "không chạy lúc này",
            "Standby bucket: " + bucketName(d.bucket),
            "Whitelist Doze: " + if (d.whitelisted) "có" else "không",
            "Chạy nền: " + opName(d.rib),
            "Chạy nền không giới hạn: " + opName(d.raib),
            "Tự khởi động (Xiaomi): " + autoName(d.auto),
            "Force-stop: " + when (d.stopped) { true -> "có"; false -> "không"; null -> "không rõ" }
        )
        val advice = when (stateOf(pkg, d)) {
            St.MISSING -> "Ứng dụng này chưa được cài."
            St.STOPPED -> "App đang ở trạng thái force-stop nên không nhận thông báo. Mở app một lần, sau đó đừng vuốt tắt nó khỏi đa nhiệm (hãy khóa app)."
            St.PARTIAL ->
                if (autoBlocked(d.auto)) "Tự khởi động đang bị chặn nên HyperOS không cho FCM dựng lại app. Bấm Áp dụng, hoặc bật tay trong Bảo mật → Quyền → Tự khởi động."
                else "Chưa áp dụng đủ. Bấm Áp dụng ở màn hình chính."
            St.OK ->
                if (d.stopped == true && !launchable)
                    "Gói này không có màn hình để mở, nên force-stop thường vô hại: thông báo FCM do Google Play Services xử lý, không phụ thuộc gói này."
                else if (d.pid.isBlank())
                    "Hệ thống đã cho phép. Tiến trình không chạy lúc này (HyperOS đã kill hoặc bạn đã vuốt tắt). FCM vẫn dựng lại được app nếu Tự khởi động cho phép. Nếu vẫn trễ: pin Không hạn chế và khóa app trong đa nhiệm."
                else
                    "Hệ thống đã cho phép. Nếu vẫn trễ thì nguyên nhân nằm ngoài Doze (trình quản lý pin của Xiaomi hoặc tự app quản lý)."
        }
        return lines.joinToString("\n") + "\n\n" + advice
    }

    private fun showDetail(pkg: String, name: String) {
        val d = lastDiag[pkg]
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(if (d == null) "Chưa có dữ liệu. Thử lại sau vài giây." else detailText(pkg, d))
            .setPositiveButton("Đóng", null)
            .show()
    }

    // ---------- Logic ----------

    private fun setButtonEnabled(enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.5f
    }

    private fun clearChips() {
        pkgChips.values.forEach { setChip(it, "—", cSub) }
        setChip(fcmChip, "—", cSub)
    }

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
                Doze.apply(Prefs.allPackages(this)) { r ->
                    runOnUiThread {
                        running = false
                        setButtonEnabled(true)
                        r.onSuccess {
                            refresh(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
                        }.onFailure {
                            hint.setTextColor(cRed)
                            hint.text = "Lỗi: ${it.message}. Mở log để xem chi tiết."
                        }
                    }
                }
            }
        }
    }

    private fun refresh(announceTime: String? = null) {
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
                Doze.diag(Prefs.allPackages(this)) { r ->
                    runOnUiThread { onDiag(r, announceTime) }
                }
            }
        }
    }

    private fun onDiag(r: Result<Doze.DiagResult>, announceTime: String?) {
        r.onSuccess { res ->
            val map = res.apps
            lastDiag = map
            lastFcm = res.fcm
            val cur = res.fcm.conns.firstOrNull { it.port in 5228..5230 }
            lastTrack = Prefs.trackFcm(
                this,
                if (cur != null) "${cur.ip}:${cur.port}<-${cur.lport}" else "none"
            )
            renderFcm(res.fcm)
            var allOk = true
            for ((pkg, c) in pkgChips) {
                val d = map[pkg]
                if (d == null) {
                    setChip(c, "—", cSub)
                    continue
                }
                val st = stateOf(pkg, d)
                if (st != St.OK && st != St.MISSING) allOk = false
                when (st) {
                    St.OK -> setChip(c, "Đã bảo vệ", cGreen)
                    St.PARTIAL -> setChip(c, "Chưa đủ", cAmber)
                    St.STOPPED -> setChip(c, "Force-stop", cRed)
                    St.MISSING -> setChip(c, "Chưa cài", cSub)
                }
            }
            if (announceTime != null) {
                if (allOk) {
                    hint.setTextColor(cGreen)
                    hint.text = "Đã áp dụng lúc $announceTime"
                } else {
                    hint.setTextColor(cAmber)
                    hint.text = "Đã chạy lệnh nhưng còn app chưa đủ. Chạm vào app để xem chi tiết."
                }
            }
        }.onFailure {
            clearChips()
            hint.setTextColor(cRed)
            hint.text = "Lỗi: ${it.message}. Mở log để xem chi tiết."
        }
    }
}
