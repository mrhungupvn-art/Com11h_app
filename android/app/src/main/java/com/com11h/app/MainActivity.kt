package com.com11h.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

private class Api(private val context: Context) {
    private val baseUrl = "https://com11h.com/api/index.php"
    private val prefs = context.getSharedPreferences("com11h_secure", Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString("token", null)
    fun saveToken(token: String) = prefs.edit().putString("token", token).apply()
    fun clearToken() = prefs.edit().remove("token").apply()

    fun request(
        action: String,
        method: String = "GET",
        body: String? = null,
        params: Map<String, String> = emptyMap(),
        idempotencyKey: String? = null
    ): JSONObject {
        val query = mutableListOf("action=${URLEncoder.encode(action, "UTF-8")}")
        params.forEach { (k, v) ->
            query += "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val conn = (URL("$baseUrl?${query.joinToString("&")}").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            token()?.let { setRequestProperty("Authorization", "Bearer $it") }
            idempotencyKey?.let { setRequestProperty("X-Idempotency-Key", it) }
        }

        return try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

class MainActivity : Activity() {
    private lateinit var api: Api
    private lateinit var root: LinearLayout
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var paymentPolling = false
    private val cart = linkedMapOf<Int, Int>()
    private val foodCache = hashMapOf<Int, JSONObject>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun money(v: Int) = String.format("%,d", v).replace(',', '.') + "đ"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(this)
        loadCart()
        showHome()
    }

    override fun onDestroy() {
        saveCart()
        paymentPolling = false
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun saveCart() {
        val arr = JSONArray()
        cart.forEach { (id, qty) -> arr.put(JSONObject().put("id", id).put("qty", qty)) }
        getSharedPreferences("com11h_secure", MODE_PRIVATE).edit().putString("cart", arr.toString()).apply()
    }

    private fun loadCart() {
        cart.clear()
        val raw = getSharedPreferences("com11h_secure", MODE_PRIVATE).getString("cart", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id")
                val qty = o.optInt("qty")
                if (id > 0 && qty > 0) cart[id] = qty
            }
        } catch (_: Exception) { }
    }

    private fun setup(title: String) {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(Color.rgb(248, 250, 248))
        }
        setContentView(page)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(Color.rgb(22, 128, 60))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        }
        content.addView(bar)
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root = content
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        minimumHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun input(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun showHome() {
        setup("COM11H 🍚")
        root.addView(TextView(this).apply {
            text = "Cơm trưa ngon – đặt nhanh – giao tận nơi"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(22))
        })
        root.addView(button("🍱  Xem thực đơn") { showMenu() })
        root.addView(button("🛒  Giỏ hàng (${cart.values.sum()})") { showCart() })
        root.addView(button("📦  Đơn hàng của tôi") { showOrders() })
        root.addView(button("👤  Tài khoản") { showProfile() })
        if (api.token() == null) root.addView(button("🔐  Đăng nhập / Đăng ký") { showLogin() })
        else root.addView(button("🚪  Đăng xuất") { api.clearToken(); showHome() })
    }

    private fun showLogin() {
        setup("Đăng nhập")
        val phone = input("Số điện thoại").apply { inputType = InputType.TYPE_CLASS_PHONE }
        val pass = input("Mật khẩu", true)
        root.addView(phone); root.addView(pass)
        lateinit var login: Button
        login = button("Đăng nhập") {
            val p = phone.text.toString().trim()
            val pw = pass.text.toString()
            if (p.isEmpty() || pw.isEmpty()) { toast("Vui lòng nhập số điện thoại và mật khẩu"); return@button }
            login.isEnabled = false
            executor.execute {
                try {
                    val r = api.request("login", "POST", JSONObject(mapOf("phone" to p, "password" to pw, "device" to "COM11H Android")).toString())
                    runOnUiThread {
                        login.isEnabled = true
                        if (r.optBoolean("ok")) {
                            api.saveToken(r.getJSONObject("data").getString("token"))
                            toast("Đăng nhập thành công")
                            showHome()
                        } else toast(r.optString("message", "Đăng nhập thất bại"))
                    }
                } catch (_: Exception) { runOnUiThread { login.isEnabled = true; toast("Không kết nối được máy chủ") } }
            }
        }
        root.addView(login)
        root.addView(button("Đăng ký tài khoản") { showRegister() })
        root.addView(button("← Quay lại") { showHome() })
    }

    private fun showRegister() {
        setup("Đăng ký tài khoản")
        val name = input("Họ tên")
        val phone = input("Số điện thoại").apply { inputType = InputType.TYPE_CLASS_PHONE }
        val pass = input("Mật khẩu – tối thiểu 6 ký tự", true)
        val pass2 = input("Nhập lại mật khẩu", true)
        root.addView(name); root.addView(phone); root.addView(pass); root.addView(pass2)
        lateinit var register: Button
        register = button("Tạo tài khoản") {
            val n = name.text.toString().trim(); val p = phone.text.toString().trim(); val pw = pass.text.toString(); val pw2 = pass2.text.toString()
            if (n.isEmpty() || p.isEmpty() || pw.isEmpty()) { toast("Vui lòng nhập đầy đủ thông tin"); return@button }
            if (pw.length < 6) { toast("Mật khẩu tối thiểu 6 ký tự"); return@button }
            if (pw != pw2) { toast("Mật khẩu nhập lại không khớp"); return@button }
            register.isEnabled = false
            executor.execute {
                try {
                    val body = JSONObject(mapOf("name" to n, "phone" to p, "password" to pw, "password2" to pw2, "device" to "COM11H Android")).toString()
                    val r = api.request("register", "POST", body)
                    runOnUiThread {
                        register.isEnabled = true
                        if (r.optBoolean("ok")) {
                            api.saveToken(r.getJSONObject("data").getString("token"))
                            toast("Đăng ký thành công")
                            showHome()
                        } else toast(r.optString("message", "Đăng ký thất bại"))
                    }
                } catch (_: Exception) { runOnUiThread { register.isEnabled = true; toast("Không kết nối được máy chủ") } }
            }
        }
        root.addView(register)
        root.addView(button("← Quay lại") { showLogin() })
    }

    private fun showMenu() {
        setup("Thực đơn")
        val loading = TextView(this).apply { text = "Đang tải thực đơn..."; textSize = 17f }
        root.addView(loading)
        executor.execute {
            try {
                val r = api.request("menu")
                if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("message"))
                val data = r.getJSONObject("data")
                val foods = data.getJSONArray("foods")
                runOnUiThread {
                    root.removeView(loading)
                    if (foods.length() == 0) root.addView(TextView(this).apply { text = "Hiện chưa có món."; textSize = 17f })
                    for (i in 0 until foods.length()) {
                        val f = foods.getJSONObject(i)
                        foodCache[f.getInt("id")] = f
                        val stock = f.optInt("stock", 0)
                        val desc = f.optString("description")
                        val label = buildString {
                            append(f.optString("name")); append("\n")
                            append(money(f.optInt("price"))); append("  •  Kho: "); append(stock)
                            if (desc.isNotBlank()) append("\n").append(desc)
                        }
                        root.addView(button(label) {
                            if (stock <= 0) toast("Món này đã hết") else addFood(f)
                        })
                    }
                    root.addView(button("🛒  Giỏ hàng (${cart.values.sum()})") { showCart() })
                    root.addView(button("← Trang chủ") { showHome() })
                }
            } catch (e: Exception) { runOnUiThread { loading.text = "Không tải được thực đơn: ${e.message ?: "Vui lòng thử lại"}" } }
        }
    }

    private fun addFood(food: JSONObject) {
        val id = food.getInt("id")
        val stock = food.optInt("stock", 0)
        val current = cart[id] ?: 0
        if (current >= stock) { toast("Số lượng đã đạt tồn kho hiện tại"); return }
        cart[id] = current + 1
        foodCache[id] = food
        saveCart()
        toast("Đã thêm ${food.optString("name")}")
    }

    private fun showCart() {
        setup("Giỏ hàng")
        if (cart.isEmpty()) {
            root.addView(TextView(this).apply { text = "Giỏ hàng đang trống."; textSize = 18f })
        } else {
            var total = 0
            cart.toMap().forEach { (id, qty) ->
                val f = foodCache[id]
                if (f != null) {
                    val line = f.optInt("price") * qty; total += line
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(this).apply { text = "${f.optString("name")} × $qty\n${money(line)}"; textSize = 17f }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(button("−") { if ((cart[id] ?: 0) <= 1) cart.remove(id) else cart[id] = (cart[id] ?: 1) - 1; saveCart(); showCart() })
                    row.addView(button("+") {
                        val stock = f.optInt("stock", 0)
                        val current = cart[id] ?: 0
                        if (current >= stock) toast("Đã đạt tồn kho hiện tại") else { cart[id] = current + 1; saveCart(); showCart() }
                    })
                    root.addView(row)
                }
            }
            root.addView(TextView(this).apply { text = "Tạm tính: ${money(total)}\nGiá cuối cùng sẽ được máy chủ kiểm tra lại trước khi đặt."; textSize = 18f; setPadding(0, dp(16), 0, dp(16)) })
            if (api.token() == null) root.addView(button("🔐 Đăng nhập để đặt hàng") { showLogin() })
            else root.addView(button("📦 Tiến hành đặt hàng") { showCheckout() })
        }
        root.addView(button("← Thực đơn") { showMenu() })
    }

    private fun showCheckout() {
        if (api.token() == null) { showLogin(); return }
        if (cart.isEmpty()) { toast("Giỏ hàng đang trống"); showCart(); return }
        setup("Xác nhận đặt hàng")
        val address = input("Địa chỉ giao hàng *")
        val delivery = input("Thời gian giao (tuỳ chọn)")
        val note = input("Ghi chú")
        root.addView(address); root.addView(delivery); root.addView(note)

        lateinit var orderButton: Button
        orderButton = button("🔎 Kiểm tra đơn hàng") {
            val addr = address.text.toString().trim()
            if (addr.isEmpty()) { toast("Vui lòng nhập địa chỉ giao hàng"); return@button }
            orderButton.isEnabled = false
            val arr = cartJson()
            val body = JSONObject().apply { put("items", arr); put("address", addr); put("delivery_time", delivery.text.toString().trim()); put("note", note.text.toString().trim()) }
            executor.execute {
                try {
                    val r = api.request("order_preview", "POST", body.toString())
                    runOnUiThread {
                        orderButton.isEnabled = true
                        if (r.optBoolean("ok")) {
                            val d = r.getJSONObject("data")
                            val total = d.optInt("total")
                            AlertDialog.Builder(this)
                                .setTitle("Kiểm tra đơn hàng")
                                .setMessage("Tổng tiền máy chủ xác nhận: ${money(total)}\n\nĐịa chỉ: $addr\n\nBấm ĐẶT HÀNG để tạo đơn thật.")
                                .setNegativeButton("Sửa lại", null)
                                .setPositiveButton("ĐẶT HÀNG") { _, _ -> createOrder(body) }
                                .show()
                        } else toast(r.optString("message", "Không thể kiểm tra đơn hàng"))
                    }
                } catch (_: Exception) { runOnUiThread { orderButton.isEnabled = true; toast("Không kết nối được máy chủ") } }
            }
        }
        root.addView(orderButton)
        root.addView(button("← Giỏ hàng") { showCart() })
    }

    private fun cartJson(): JSONArray {
        val arr = JSONArray()
        cart.forEach { (id, qty) -> arr.put(JSONObject().put("food_id", id).put("qty", qty)) }
        return arr
    }

    private fun createOrder(body: JSONObject) {
        // Deterministic idempotency key: the same order payload gets the same key.
        // This prevents duplicate orders when the network times out and the user retries.
        val key = idempotencyKeyFor(body.toString())
        executor.execute {
            try {
                val r = api.request("create_order", "POST", body.toString(), idempotencyKey = key)
                runOnUiThread {
                    if (r.optBoolean("ok")) {
                        cart.clear(); saveCart()
                        showPayment(r.getJSONObject("data"))
                    } else toast(r.optString("message", "Không thể tạo đơn hàng"))
                }
            } catch (_: Exception) {
                runOnUiThread {
                    toast("Mạng chập chờn. Bạn có thể thử lại; hệ thống sẽ chống tạo trùng đơn.")
                }
            }
        }
    }

    private fun idempotencyKeyFor(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun showPayment(data: JSONObject) {
        paymentPolling = false
        mainHandler.removeCallbacksAndMessages(null)
        setup("Thanh toán đơn hàng")
        val order = data.getJSONObject("order")
        val p = data.getJSONObject("payment")
        val code = order.getString("code")
        val total = order.getInt("total")
        val statusView = TextView(this).apply {
            text = "⏳ Đang chờ xác nhận thanh toán...\nMã đơn: $code"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(statusView)
        root.addView(TextView(this).apply {
            text = "Mã đơn: $code\nSố tiền: ${money(total)}\nNgân hàng: ${p.optString("bank_display_name", p.optString("bank_name"))}\nSTK: ${p.optString("bank_account_no", p.optString("bank_account"))}\nChủ TK: ${p.optString("bank_account_name", p.optString("account_name"))}\nNội dung: ${p.optString("transfer_content")}"
            textSize = 17f
            setPadding(0, 0, 0, dp(14))
        })

        // Wrap the QR in its own container so it can be hidden as one unit
        // the moment payment is confirmed.
        val qrContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val qr = ImageView(this).apply { adjustViewBounds = true; minimumHeight = dp(220); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        qrContainer.addView(qr, LinearLayout.LayoutParams(-1, dp(260)))
        root.addView(qrContainer)
        loadImageInto(p.optString("qr_url"), qr)

        val openQrButton = button("📱 Mở QR bằng trình duyệt/app ngân hàng") { openUrl(p.optString("qr_url")) }
        root.addView(openQrButton)
        root.addView(button("🔄 Kiểm tra ngay") { showOrder(code) })
        root.addView(button("← Trang chủ") { showHome() })

        startPaymentPolling(code, statusView, qrContainer, openQrButton)
    }

    private fun startPaymentPolling(code: String, statusView: TextView, qrContainer: View, openQrButton: View) {
        paymentPolling = true
        val startedAt = System.currentTimeMillis()
        val maxDurationMs = 10 * 60 * 1000L

        fun poll() {
            if (!paymentPolling || isFinishing || isDestroyed) return
            if (System.currentTimeMillis() - startedAt >= maxDurationMs) {
                paymentPolling = false
                statusView.text = "⏳ Chưa nhận được xác nhận thanh toán. Bạn có thể bấm kiểm tra lại."
                return
            }
            executor.execute {
                var paid = false
                try {
                    val r = api.request("order", params = mapOf("code" to code))
                    val d = r.optJSONObject("data")
                    val o = d?.optJSONObject("order")
                    paid = o?.optString("payment_status") == "paid"
                } catch (_: Exception) {
                    // Keep polling silently; the user can still use "Kiểm tra ngay".
                }

                if (paid) {
                    // Stop polling on the background thread first, before touching the UI,
                    // so a second in-flight poll() can't race and schedule another round.
                    paymentPolling = false

                    // 1) Reload account info (points/profile) right away so it's fresh
                    // the next time the user opens "Tài khoản".
                    try { api.request("profile") } catch (_: Exception) { }

                    runOnUiThread {
                        // 2) Ẩn QR ngay lập tức.
                        qrContainer.visibility = View.GONE
                        openQrButton.visibility = View.GONE
                        // 3) Hiển thị "Đã thanh toán" kèm mã đơn.
                        statusView.text = "✅ Đã thanh toán\nMã đơn: $code"
                        toast("Thanh toán đơn $code đã được xác nhận")
                        // 4) Tải lại chi tiết đơn hàng từ máy chủ.
                        mainHandler.postDelayed({ showOrder(code) }, 900)
                    }
                } else {
                    runOnUiThread {
                        if (paymentPolling) statusView.text = "⏳ Đang chờ ngân hàng xác nhận...\nMã đơn: $code\nTự kiểm tra mỗi 5 giây"
                    }
                    if (paymentPolling) mainHandler.postDelayed({ poll() }, 5000L)
                }
            }
        }
        mainHandler.post { poll() }
    }

    private fun showOrders() {
        if (api.token() == null) { showLogin(); return }
        setup("Đơn hàng của tôi")
        val loading = TextView(this).apply { text = "Đang tải..."; textSize = 17f }; root.addView(loading)
        executor.execute {
            try {
                val r = api.request("orders")
                runOnUiThread {
                    if (!r.optBoolean("ok")) { loading.text = r.optString("message", "Không tải được đơn hàng"); return@runOnUiThread }
                    val a = r.getJSONObject("data").getJSONArray("orders")
                    root.removeView(loading)
                    if (a.length() == 0) root.addView(TextView(this).apply { text = "Chưa có đơn hàng."; textSize = 17f })
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        root.addView(button("${o.getString("code")}\n${o.getString("status")} • ${money(o.getInt("total"))}\nThanh toán: ${o.optString("payment_status")}") { showOrder(o.getString("code")) })
                    }
                    root.addView(button("🔄 Làm mới") { showOrders() })
                    root.addView(button("← Trang chủ") { showHome() })
                }
            } catch (_: Exception) { runOnUiThread { loading.text = "Không tải được đơn hàng. Vui lòng thử lại." } }
        }
    }

    private fun showOrder(code: String) {
        setup("Chi tiết đơn $code")
        val loading = TextView(this).apply { text = "Đang tải đơn..."; textSize = 17f }
        root.addView(loading)
        executor.execute {
            try {
                val r = api.request("order", params = mapOf("code" to code))
                runOnUiThread {
                    root.removeView(loading)
                    if (!r.optBoolean("ok")) { root.addView(TextView(this).apply { text = r.optString("message", "Không tải được đơn"); textSize = 17f }); return@runOnUiThread }
                    val d = r.getJSONObject("data")
                    val o = d.getJSONObject("order")
                    val items = d.getJSONArray("items")
                    val status = o.optString("status")
                    val paid = o.optString("payment_status") == "paid"
                    val confirmed = o.optInt("delivery_confirmed") == 1
                    val points = o.optInt("points_earned")
                    val lucky = o.optString("lucky_code", "")

                    root.addView(TextView(this).apply {
                        text = "Trạng thái: $status\nThanh toán: ${if (paid) "Đã thanh toán" else "Chưa thanh toán"}\nTổng: ${money(o.getInt("total"))}\nĐịa chỉ: ${o.optString("address")}\nGiao: ${o.optString("delivery_time")}" + if (o.optString("note").isNotBlank()) "\nGhi chú: ${o.optString("note")}" else ""
                        textSize = 17f
                    })

                    root.addView(TextView(this).apply { text = "\nTiến trình đơn hàng"; textSize = 19f; setTypeface(null, 1) })
                    listOf("Chờ xác nhận", "Đã xác nhận", "Đang nấu", "Đang giao", "Hoàn thành").forEach { s ->
                        root.addView(TextView(this).apply { text = if (statusRank(status) >= statusRank(s)) "✓ $s" else "○ $s"; textSize = 16f; setPadding(dp(8), dp(3), 0, dp(3)) })
                    }

                    root.addView(TextView(this).apply { text = "\nMón đã đặt"; textSize = 19f; setTypeface(null, 1) })
                    for (i in 0 until items.length()) {
                        val it = items.getJSONObject(i)
                        root.addView(TextView(this).apply { text = "• ${it.optString("name")} × ${it.optInt("qty")} = ${money(it.optInt("price") * it.optInt("qty"))}"; textSize = 16f; setPadding(0, dp(6), 0, dp(6)) })
                    }

                    if (lucky.isNotBlank()) root.addView(TextView(this).apply { text = "\n🎁 Mã dự thưởng: $lucky"; textSize = 20f; setTypeface(null, 1) })
                    if (confirmed) root.addView(TextView(this).apply { text = "\n✅ Bạn đã xác nhận nhận hàng\n⭐ Đã cộng: +$points điểm"; textSize = 18f })

                    val p = d.optJSONObject("payment")
                    if (p != null && !paid) {
                        root.addView(button("📱 Mở QR thanh toán") { openUrl(p.optString("qr_url")) })
                        val qr = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_INSIDE }
                        root.addView(qr, LinearLayout.LayoutParams(-1, dp(250)))
                        loadImageInto(p.optString("qr_url"), qr)
                    }

                    if (status == "Hoàn thành" && !confirmed) {
                        lateinit var confirm: Button
                        confirm = button("✅ Tôi đã nhận hàng") {
                            confirm.isEnabled = false
                            executor.execute {
                                try {
                                    val rr = api.request("confirm_delivery", "POST", JSONObject().put("code", code).toString())
                                    runOnUiThread {
                                        confirm.isEnabled = true
                                        if (rr.optBoolean("ok")) {
                                            toast(rr.optString("message", "Đã xác nhận nhận hàng"))
                                            showOrder(code)
                                        } else toast(rr.optString("message", "Chưa thể xác nhận nhận hàng"))
                                    }
                                } catch (_: Exception) { runOnUiThread { confirm.isEnabled = true; toast("Không kết nối được máy chủ") } }
                            }
                        }
                        root.addView(confirm)
                    }

                    root.addView(button("🔄 Làm mới") { showOrder(code) })
                    root.addView(button("← Đơn hàng") { showOrders() })
                    root.addView(button("← Trang chủ") { showHome() })
                }
            } catch (_: Exception) { runOnUiThread { loading.text = "Không tải được chi tiết đơn. Vui lòng thử lại." } }
        }
    }

    private fun statusRank(status: String): Int = when (status) {
        "Chờ xác nhận" -> 0
        "Đã xác nhận" -> 1
        "Đang nấu" -> 2
        "Đang giao" -> 3
        "Hoàn thành" -> 4
        else -> -1
    }

    private fun showProfile() {
        if (api.token() == null) { showLogin(); return }
        setup("Tài khoản")
        val loading = TextView(this).apply { text = "Đang tải tài khoản..."; textSize = 17f }
        root.addView(loading)
        executor.execute {
            try {
                val r = api.request("profile")
                runOnUiThread {
                    root.removeView(loading)
                    if (!r.optBoolean("ok")) { toast(r.optString("message", "Phiên đăng nhập hết hạn")); api.clearToken(); showLogin(); return@runOnUiThread }
                    val c = r.getJSONObject("data").getJSONObject("customer")
                    root.addView(TextView(this).apply { text = "Họ tên: ${c.optString("name")}\nSố điện thoại: ${c.optString("phone")}\nĐiểm tích luỹ: ${c.optInt("points")}"; textSize = 18f })
                    root.addView(button("📦 Đơn hàng") { showOrders() })
                    root.addView(button("🚪 Đăng xuất") { api.clearToken(); showHome() })
                    root.addView(button("← Trang chủ") { showHome() })
                }
            } catch (_: Exception) { runOnUiThread { toast("Không kết nối được máy chủ") } }
        }
    }

    private fun loadImageInto(url: String, imageView: ImageView) {
        if (url.isBlank()) return
        executor.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.doInput = true
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bitmap != null) runOnUiThread { imageView.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) { toast("Không có liên kết thanh toán"); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { toast("Thiết bị không có ứng dụng mở liên kết này") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
