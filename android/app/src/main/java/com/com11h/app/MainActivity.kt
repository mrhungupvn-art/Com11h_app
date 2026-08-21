package com.com11h.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * COM11H app — toàn bộ dữ liệu thực đơn, đơn hàng, thanh toán, quay số may
 * mắn và tài khoản được đồng bộ TRỰC TIẾP với com11h.com qua api/index.php
 * (dùng chung logic nghiệp vụ với web qua core.php, xem AccountSync.kt).
 * Chỉ có giỏ hàng (trước khi đặt) là lưu tạm trên máy.
 */
class MainActivity : SessionActivity() {
    private lateinit var account: AccountSync
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val primary = Color.rgb(245, 81, 30)
    private val dark = Color.rgb(38, 38, 38)
    private val secondary = Color.rgb(107, 107, 107)
    private val bgColor = Color.rgb(255, 248, 245)
    private val danger = Color.rgb(198, 40, 40)
    private val ok = Color.rgb(46, 125, 50)

    // Giỏ hàng: food_id -> số lượng. Lưu tạm cục bộ cho tới khi đặt hàng thật qua API.
    private val cart = linkedMapOf<Int, Int>()
    // Cache thực đơn tải gần nhất từ server, dùng để hiển thị tên/giá/ảnh trong giỏ hàng.
    private var foodsCache: List<Food> = emptyList()
    private var pollRunnable: Runnable? = null
    // Badge số lượng trên icon 🛒 Giỏ hàng ở thanh điều hướng của màn hình đang mở.
    private var cartBadge: TextView? = null

    data class Food(
        val id: Int, val name: String, val price: Int, val stock: Int,
        val category: String, val description: String, val image: String
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun money(v: Int) = String.format("%,d", v).replace(',', '.') + "đ"
    private fun bg(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun outline(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), color); cornerRadius = dp(radius).toFloat() }
    private fun label(v: String, size: Float = 17f, color: Int = dark, bold: Boolean = false) = TextView(this).apply { text = v; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4)) }
    private fun button(v: String, click: () -> Unit) = TextView(this).apply { text = v; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); background = bg(primary, 13); setPadding(dp(12), dp(13), dp(12), dp(13)); setOnClickListener { click() } }
    private fun ghostButton(v: String, click: () -> Unit) = TextView(this).apply { text = v; textSize = 15f; gravity = Gravity.CENTER; setTextColor(primary); background = outline(primary, 13); setPadding(dp(12), dp(11), dp(12), dp(11)); setOnClickListener { click() } }

    /**
     * Bấm vào ảnh 1 món ăn (ở Thực đơn): xem ảnh PHÓNG TO ngay trong app, dùng
     * lại đúng màn hình zoom của banner (BannerViewActivity), kèm TOÀN BỘ danh
     * sách món đang hiển thị (đã lọc theo danh mục/từ khoá) để khách vuốt sang
     * trái/phải xem tiếp ảnh các món khác — khách chụm/mở 2 ngón tay để phóng
     * to, thu nhỏ, kéo xem chi tiết ảnh như trước.
     */
    private fun openFoodGallery(items: JSONArray, index: Int) {
        startActivity(Intent(this, BannerViewActivity::class.java).putExtra("items", items.toString()).putExtra("index", index))
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Icon 👤 Tài khoản ở header — đổi màu nền + có chấm xanh khi khách đã đăng nhập. */
    private fun profileIconCell(): View {
        val loggedIn = account.isLoggedIn()
        val cell = FrameLayout(this)
        cell.addView(TextView(this).apply {
            text = "👤"; textSize = 19f; gravity = Gravity.CENTER
            background = bg(if (loggedIn) Color.rgb(224, 247, 233) else Color.rgb(255, 240, 234), 22)
            setOnClickListener { showProfile() }
        }, FrameLayout.LayoutParams(dp(44), dp(44)))
        if (loggedIn) {
            cell.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ok); setStroke(dp(2), Color.WHITE) }
            }, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(3); rightMargin = dp(3) })
        }
        return cell
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); account = AccountSync(this); loadLocalCart()
        val code = intent.getStringExtra("code")
        when (intent.getStringExtra("screen")) {
            "menu" -> showMenu()
            "cart" -> showCart()
            "checkout" -> showCheckout()
            "orders" -> showOrders()
            "order_detail" -> if (code != null) showOrderDetail(code) else showOrders()
            "lucky" -> showLucky(code)
            "daily" -> showDaily()
            "loyalty" -> showLoyalty()
            "profile" -> showProfile()
            else -> { startActivity(Intent(this, HomeActivity::class.java)); finish() }
        }
    }
    override fun onDestroy() { stopPolling(); saveLocalCart(); executor.shutdownNow(); super.onDestroy() }

    private fun stopPolling() { pollRunnable?.let { handler.removeCallbacks(it) }; pollRunnable = null }

    private fun loadLocalCart() {
        val p = getSharedPreferences("com11h_local", MODE_PRIVATE); cart.clear()
        try {
            val a = JSONArray(p.getString("cart", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); cart[o.optInt("id")] = o.optInt("qty") }
        } catch (_: Exception) { }
    }
    private fun saveLocalCart() {
        val p = getSharedPreferences("com11h_local", MODE_PRIVATE)
        val a = JSONArray(); cart.forEach { (id, q) -> a.put(JSONObject().put("id", id).put("qty", q)) }
        p.edit().putString("cart", a.toString()).apply()
    }
    private fun lastAddress(): String = getSharedPreferences("com11h_local", MODE_PRIVATE).getString("last_address", "") ?: ""
    private fun saveLastAddress(v: String) = getSharedPreferences("com11h_local", MODE_PRIVATE).edit().putString("last_address", v).apply()

    private fun shell(title: String, selected: Int): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE) }
        header.addView(TextView(this).apply { text = "‹"; textSize = 34f; setTextColor(primary); gravity = Gravity.CENTER; setOnClickListener { startActivity(Intent(this@MainActivity, HomeActivity::class.java)); finish() } }, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(label(title, 20f, primary, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        header.addView(profileIconCell(), LinearLayout.LayoutParams(dp(44), dp(44))); outer.addView(header)
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(18)) }; scroll.addView(content); outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat() }
        listOf("⌂\nTrang chủ", "▦\nThực đơn", "🛒\nGiỏ hàng", "▤\nĐơn hàng", "♙\nTài khoản").forEachIndexed { i, name ->
            val cell = FrameLayout(this)
            cell.addView(TextView(this).apply { text = name; textSize = 10f; gravity = Gravity.CENTER; setTextColor(if (i == selected) primary else secondary); setTypeface(null, if (i == selected) Typeface.BOLD else Typeface.NORMAL); setOnClickListener { when (i) { 0 -> { startActivity(Intent(this@MainActivity, HomeActivity::class.java)); finish() }; 1 -> showMenu(); 2 -> showCart(); 3 -> showOrders(); 4 -> showProfile() } } }, FrameLayout.LayoutParams(-1, -1))
            if (i == 2) {
                cartBadge = TextView(this).apply {
                    textSize = 9.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                    background = bg(Color.rgb(220, 38, 38), 20)
                    visibility = android.view.View.GONE
                }
                cell.addView(cartBadge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); marginEnd = dp(14) })
            }
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        outer.addView(nav); refreshCartBadge(); return outer
    }

    /** Cập nhật số lượng (badge đỏ) trên icon 🛒 Giỏ hàng ở thanh điều hướng theo giỏ hàng hiện tại. */
    private fun refreshCartBadge() {
        val n = cart.values.sum()
        cartBadge?.apply {
            text = if (n > 99) "99+" else n.toString()
            visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
    private fun contentOf(s: LinearLayout) = (s.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout

    private fun loading(c: LinearLayout, msg: String = "Đang tải dữ liệu..."): TextView {
        val t = label("⏳ $msg", 15f, secondary); c.addView(t); return t
    }

    // =========================================================================
    // THỰC ĐƠN — lấy TOÀN BỘ dữ liệu món ăn từ server (api?action=menu), không
    // còn danh sách cứng trong app. Ảnh, giá, tồn kho luôn khớp với web.
    // =========================================================================
    private fun showMenu() {
        val s = shell("Thực đơn", 1); setContentView(s); val c = contentOf(s)
        val head = label("Món ăn ngon mỗi ngày", 22f, dark, true); c.addView(head)
        c.addView(label("Đồng bộ trực tiếp từ com11h.com", 14f, secondary))

        // Ô tìm kiếm ngay trong màn Thực đơn — nhận sẵn từ khoá được truyền từ
        // trang chủ (extra "query") khi khách bấm nút 🔍 hoặc "Tìm kiếm" trên
        // bàn phím ở ô tìm kiếm trang chủ, đồng thời cho phép gõ lại tại đây.
        val initialQuery = intent.getStringExtra("query") ?: ""
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val searchInput = EditText(this).apply {
            hint = "Tìm món ăn..."; textSize = 15f; isSingleLine = true
            setText(initialQuery)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0); background = outline(primary, 14)
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        val searchBtn = TextView(this).apply { text = "🔍"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = bg(primary, 14) }
        searchRow.addView(searchBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        c.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipsScroll = HorizontalScrollView(this).apply { addView(chipsRow) }
        c.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(4) })
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(listBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val loadingView = loading(listBox)

        executor.execute {
            try {
                val r = account.request("menu")
                runOnUiThread {
                    listBox.removeView(loadingView)
                    if (!r.optBoolean("ok")) { listBox.addView(label("Không tải được thực đơn. ${r.optString("message")}", 14f, danger)); return@runOnUiThread }
                    val arr = r.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                    val foods = mutableListOf<Food>()
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        foods.add(Food(f.optInt("id"), f.optString("name"), f.optInt("price"), f.optInt("stock"), f.optString("category"), f.optString("description"), f.optString("image")))
                    }
                    foodsCache = foods
                    if (foods.isEmpty()) { listBox.addView(label("Hiện chưa có món nào đang bán.", 14f, secondary)); return@runOnUiThread }

                    val categories = listOf("Tất cả") + foods.map { it.category }.filter { it.isNotBlank() }.distinct()
                    var selectedCategory = "Tất cả"
                    fun renderList(cat: String) {
                        listBox.removeAllViews()
                        val keyword = searchInput.text.toString().trim()
                        var filtered = if (cat == "Tất cả") foods else foods.filter { it.category == cat }
                        if (keyword.isNotEmpty()) {
                            filtered = filtered.filter { it.name.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true) }
                        }
                        if (filtered.isEmpty()) {
                            listBox.addView(label(if (keyword.isNotEmpty()) "Không tìm thấy món nào khớp với \"$keyword\"." else "Không có món nào trong danh mục này.", 14f, secondary))
                            return
                        }
                        // Danh sách ảnh của đúng các món đang hiển thị ở đây (sau
                        // khi lọc theo danh mục/từ khoá) — để khách vuốt sang
                        // trái/phải trong màn xem ảnh phóng to là xem tiếp các
                        // món khác NGAY TRONG danh sách đang lọc này.
                        val itemsJson = JSONArray().apply { filtered.forEach { ff -> put(JSONObject().put("image", ff.image).put("title", ff.name)) } }
                        filtered.forEachIndexed { idx, f -> listBox.addView(foodCard(f, itemsJson, idx)) }
                    }
                    fun renderChips() {
                        chipsRow.removeAllViews()
                        categories.forEach { cat ->
                            chipsRow.addView(TextView(this@MainActivity).apply {
                                text = cat; textSize = 14f; gravity = Gravity.CENTER
                                setPadding(dp(14), dp(8), dp(14), dp(8))
                                setTextColor(if (cat == selectedCategory) Color.WHITE else primary)
                                background = if (cat == selectedCategory) bg(primary, 16) else outline(primary, 16)
                                setOnClickListener { selectedCategory = cat; renderChips(); renderList(cat) }
                            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                        }
                    }
                    searchBtn.setOnClickListener { renderList(selectedCategory) }
                    searchInput.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { renderList(selectedCategory); true } else false
                    }
                    renderChips(); renderList(selectedCategory)
                }
            } catch (e: Exception) {
                runOnUiThread { listBox.removeView(loadingView); listBox.addView(label("Lỗi kết nối máy chủ. Kiểm tra mạng rồi thử lại.", 14f, danger)) }
            }
        }
    }

    private fun foodCard(f: Food, galleryItems: JSONArray, galleryIndex: Int): LinearLayout {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(Color.WHITE, 16); setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) } }
        // Ảnh món ăn to hơn trước và có thể bấm vào để xem phóng to (chụm/mở
        // 2 ngón tay để zoom, kéo xem chi tiết), giống hệt cách xem banner.
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(255, 245, 240), 14); clipToOutline = true }
        card.addView(img, LinearLayout.LayoutParams(dp(86), dp(86)))
        ImageLoader.load(img, f.image)
        img.setOnClickListener { openFoodGallery(galleryItems, galleryIndex) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(6), 0) }
        info.addView(label(f.name, 17f, dark, true))
        if (f.description.isNotBlank()) info.addView(label(f.description, 13.5f, secondary))
        info.addView(label("${money(f.price)}   •   còn ${f.stock} phần", 15f, primary, true))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val addBtn = TextView(this).apply {
            text = if (f.stock <= 0) "Hết" else "+"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(if (f.stock <= 0) secondary else primary, 22)
            setOnClickListener {
                if (f.stock <= 0) { toast("Món này đã hết hàng"); return@setOnClickListener }
                val current = cart[f.id] ?: 0
                if (current + 1 > f.stock) { toast("Chỉ còn ${f.stock} phần \"${f.name}\""); return@setOnClickListener }
                cart[f.id] = current + 1; saveLocalCart(); refreshCartBadge(); toast("Đã thêm \"${f.name}\" vào giỏ hàng")
            }
        }
        card.addView(addBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
        return card
    }

    // =========================================================================
    // GIỎ HÀNG (cục bộ) -> ĐẶT HÀNG THẬT qua API (order_preview + create_order)
    // =========================================================================
    private fun showCart() {
        val s = shell("Giỏ hàng", 2); setContentView(s); val c = contentOf(s)
        if (cart.isEmpty()) { c.addView(label("🛒 Giỏ hàng đang trống", 20f, dark, true)); c.addView(button("Xem thực đơn") { showMenu() }); return }

        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(listBox)
        val loadingView = loading(listBox, "Đang cập nhật giỏ hàng...")

        fun renderCart(foods: List<Food>) {
            listBox.removeAllViews()
            val map = foods.associateBy { it.id }
            var total = 0
            cart.toMap().forEach { (id, qty) ->
                val f = map[id] ?: run { cart.remove(id); return@forEach }
                total += f.price * qty
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(Color.WHITE, 14); setPadding(dp(10), dp(9), dp(10), dp(9)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(255, 245, 240), 12) }
                row.addView(img, LinearLayout.LayoutParams(dp(52), dp(52))); ImageLoader.load(img, f.image)
                row.addView(label("${f.name}\n${money(f.price)}", 15f, dark, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                val stepper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                stepper.addView(TextView(this).apply { text = "−"; textSize = 18f; setTextColor(primary); setTypeface(null, Typeface.BOLD); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { if (qty <= 1) cart.remove(id) else cart[id] = qty - 1; saveLocalCart(); refreshCartBadge(); renderCart(foods) } })
                stepper.addView(label("$qty", 15f, dark, true))
                stepper.addView(TextView(this).apply { text = "+"; textSize = 18f; setTextColor(primary); setTypeface(null, Typeface.BOLD); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { if (qty + 1 > f.stock) { toast("Chỉ còn ${f.stock} phần"); return@setOnClickListener }; cart[id] = qty + 1; saveLocalCart(); refreshCartBadge(); renderCart(foods) } })
                row.addView(stepper); listBox.addView(row)
            }
            if (cart.isEmpty()) { listBox.addView(label("🛒 Giỏ hàng đang trống", 18f, dark, true)); listBox.addView(button("Xem thực đơn") { showMenu() }); return }
            listBox.addView(label("Tổng cộng: ${money(total)}", 20f, primary, true).apply { gravity = Gravity.END; setPadding(0, dp(12), 0, dp(12)) })
            listBox.addView(button("Đặt hàng — ${money(total)}") { showCheckout() })
        }

        executor.execute {
            val r = if (foodsCache.isNotEmpty()) null else account.request("menu")
            runOnUiThread {
                listBox.removeView(loadingView)
                val foods = if (foodsCache.isNotEmpty()) foodsCache else {
                    val arr = r?.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                    val list = mutableListOf<Food>()
                    for (i in 0 until arr.length()) { val f = arr.getJSONObject(i); list.add(Food(f.optInt("id"), f.optString("name"), f.optInt("price"), f.optInt("stock"), f.optString("category"), f.optString("description"), f.optString("image"))) }
                    foodsCache = list; list
                }
                renderCart(foods)
            }
        }
    }

    // Đặt hàng: nhập địa chỉ -> order_preview (server tính lại giá/tồn kho, kiểm
    // tra khoảng cách giao hàng nếu có) -> create_order (kèm Idempotency-Key).
    private fun showCheckout() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập để đặt hàng"); showLogin(); return }
        val s = shell("Xác nhận đặt hàng", 2); setContentView(s); val c = contentOf(s)
        c.addView(label("Thông tin giao hàng", 18f, dark, true))

        // Thông báo về khoảng cách giao hàng — nhắc khách ghi rõ, đầy đủ địa chỉ
        // (số nhà, đường, phường/xã, quận/huyện...) để hệ thống xác định đúng
        // khoảng cách. Nếu địa chỉ vượt quá phạm vi giao hàng của quán, hệ
        // thống sẽ báo rõ lý do ở bước "Xem lại tổng tiền" bên dưới và không
        // cho đặt hàng tiếp.
        val noticeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.rgb(255, 244, 230), 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        noticeBox.addView(label("🚴 Lưu ý khoảng cách giao hàng", 14f, Color.rgb(180, 95, 6), true))
        noticeBox.addView(label("Vui lòng ghi rõ, đầy đủ địa chỉ (số nhà, đường, phường/xã, tỉnh/thành) để hệ thống xác nhận chính xác khoảng cách giao hàng. Quán chỉ giao hàng trong bán kính ${String.format("%.0f", DistanceHelper.MAX_DELIVERY_KM)}km từ ${DistanceHelper.STORE_ADDRESS.substringBefore(",")} — nếu địa chỉ vượt quá phạm vi này, đơn hàng sẽ không đặt được.", 12.5f, Color.rgb(120, 76, 20)).apply { setPadding(0, dp(3), 0, 0) })
        c.addView(noticeBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })

        val address = EditText(this).apply { hint = "Địa chỉ giao hàng (số nhà, đường, phường/xã...)"; setText(lastAddress()); textSize = 15f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12) }
        val time = EditText(this).apply { hint = "Giờ giao hàng mong muốn (bắt buộc)"; textSize = 15f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12) }
        val note = EditText(this).apply { hint = "Ghi chú cho quán (không bắt buộc)"; textSize = 15f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12) }
        listOf(address, time, note).forEach { c.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }

        val summaryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(summaryBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        fun itemsJson(): JSONArray {
            val arr = JSONArray()
            cart.forEach { (id, qty) -> arr.put(JSONObject().put("food_id", id).put("qty", qty)) }
            return arr
        }

        val previewBtn = button("Xem lại tổng tiền") {
            if (address.text.toString().trim().isBlank()) { toast("Vui lòng nhập địa chỉ giao hàng"); return@button }
            // Giờ giao hàng là bắt buộc: chặn ngay tại bước xem lại tổng tiền
            // nếu khách chưa nhập, không để trống mặc định "giao ngay" nữa.
            if (time.text.toString().trim().isBlank()) { toast("Vui lòng nhập giờ giao hàng"); return@button }
            summaryBox.removeAllViews(); val loadingView = loading(summaryBox, "Đang kiểm tra đơn hàng & khoảng cách giao hàng...")
            executor.execute {
                val body = JSONObject().put("address", address.text.toString().trim()).put("items", itemsJson()).toString()
                val r = account.request("order_preview", "POST", body)
                val data0 = r.optJSONObject("data") ?: JSONObject()
                // Nếu server chưa tự tính khoảng cách (chưa có distance_km),
                // app tự geocode địa chỉ quán + địa chỉ khách để tính khoảng
                // cách dự phòng, áp giới hạn giao hàng DistanceHelper.MAX_DELIVERY_KM.
                var clientDistance: Double? = null
                var clientDistanceFailed = false
                if (r.optBoolean("ok") && !data0.has("distance_km")) {
                    clientDistance = DistanceHelper.distanceFromStoreKm(address.text.toString().trim())
                    clientDistanceFailed = clientDistance == null
                }
                runOnUiThread {
                    summaryBox.removeView(loadingView)
                    if (!r.optBoolean("ok")) { summaryBox.addView(label(r.optString("message", "Không thể tính đơn hàng."), 14f, danger)); return@runOnUiThread }
                    val data = data0
                    val total = data.optInt("total")
                    // Ưu tiên khoảng cách do server tính (distance_km /
                    // max_distance_km) nếu có; nếu không thì dùng khoảng cách
                    // ước lượng tính ngay trên app (DistanceHelper) so với
                    // giới hạn giao hàng tối đa 15km.
                    var outOfRange = false
                    if (data.has("distance_km")) {
                        val distance = data.optDouble("distance_km")
                        val maxDistance = if (data.has("max_distance_km")) data.optDouble("max_distance_km") else DistanceHelper.MAX_DELIVERY_KM
                        outOfRange = distance > maxDistance
                        val distText = "📍 Khoảng cách giao hàng: ${String.format("%.1f", distance)} km (tối đa ${String.format("%.1f", maxDistance)} km)"
                        summaryBox.addView(label(distText, 13.5f, if (outOfRange) danger else ok, true))
                    } else if (clientDistance != null) {
                        val distance = clientDistance
                        outOfRange = distance > DistanceHelper.MAX_DELIVERY_KM
                        val distText = "📍 Khoảng cách giao hàng (ước tính): ${String.format("%.1f", distance)} km (tối đa ${String.format("%.1f", DistanceHelper.MAX_DELIVERY_KM)} km)"
                        summaryBox.addView(label(distText, 13.5f, if (outOfRange) danger else ok, true))
                    } else if (clientDistanceFailed) {
                        summaryBox.addView(label("⚠️ Không thể xác định chính xác khoảng cách từ địa chỉ này. Vui lòng ghi rõ số nhà, tên đường, phường/xã, tỉnh/thành để hệ thống xác nhận đúng khoảng cách giao hàng.", 12.5f, Color.rgb(180, 95, 6)))
                    }
                    if (outOfRange) {
                        summaryBox.addView(label("❌ Địa chỉ này cách quán quá ${String.format("%.0f", DistanceHelper.MAX_DELIVERY_KM)}km, nằm ngoài phạm vi giao hàng. Vui lòng kiểm tra lại địa chỉ hoặc chọn địa chỉ gần hơn.", 13.5f, danger).apply { setPadding(0, dp(4), 0, dp(4)) })
                        return@runOnUiThread
                    }
                    summaryBox.addView(label("Tổng tiền: ${money(total)}", 19f, primary, true))
                    summaryBox.addView(button("✅ Đặt hàng ngay") {
                        if (time.text.toString().trim().isBlank()) { toast("Vui lòng nhập giờ giao hàng"); return@button }
                        summaryBox.addView(label("⏳ Đang tạo đơn hàng...", 14f, secondary))
                        val idem = UUID.randomUUID().toString()
                        val orderBody = JSONObject().put("address", address.text.toString().trim())
                            .put("delivery_time", time.text.toString().trim()).put("note", note.text.toString().trim())
                            .put("items", itemsJson()).toString()
                        executor.execute {
                            val cr = account.request("create_order", "POST", orderBody, mapOf("X-Idempotency-Key" to idem))
                            runOnUiThread {
                                if (!cr.optBoolean("ok")) { toast(cr.optString("message", "Đặt hàng thất bại")); return@runOnUiThread }
                                saveLastAddress(address.text.toString().trim())
                                cart.clear(); saveLocalCart()
                                val code = cr.optJSONObject("data")?.optJSONObject("order")?.optString("code") ?: ""
                                toast("Đặt hàng thành công!")
                                showOrderDetail(code)
                            }
                        }
                    })
                }
            }
        }
        c.addView(previewBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addDeliveryReminderFab { time.text.toString() }
    }

    // =========================================================================
    // ĐƠN HÀNG — danh sách & chi tiết lấy từ server (không lưu cục bộ).
    // =========================================================================
    private fun showOrders() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập để xem đơn hàng"); showLogin(); return }
        val s = shell("Đơn hàng", 3); setContentView(s); val c = contentOf(s)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(listBox)
        val loadingView = loading(listBox)
        executor.execute {
            val r = account.request("orders")
            runOnUiThread {
                listBox.removeView(loadingView)
                if (!r.optBoolean("ok")) { listBox.addView(label(r.optString("message", "Không tải được đơn hàng."), 14f, danger)); return@runOnUiThread }
                val arr = r.optJSONObject("data")?.optJSONArray("orders") ?: JSONArray()
                if (arr.length() == 0) { listBox.addView(label("📦 Chưa có đơn hàng nào", 20f, dark, true)); listBox.addView(label("Đơn hàng được đồng bộ trực tiếp với tài khoản trên website.", 13f, secondary)); listBox.addView(button("Đặt món ngay") { showMenu() }); return@runOnUiThread }
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val payStatus = o.optString("payment_status", "pending")
                    val card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 15); setPadding(dp(14), dp(12), dp(14), dp(12)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) } }
                    card.addView(label("#${o.optString("code")}", 16f, dark, true))
                    card.addView(label("${money(o.optInt("total"))} • ${o.optString("created_at")}", 14f, secondary))
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(label("Trạng thái: ${o.optString("status")}", 14f, primary, true), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label(if (payStatus == "paid") "✅ Đã thanh toán" else "💳 Chờ thanh toán", 13f, if (payStatus == "paid") ok else Color.rgb(198, 130, 8)))
                    card.addView(row)
                    card.setOnClickListener { showOrderDetail(o.optString("code")) }
                    listBox.addView(card)
                }
            }
        }
    }

    private fun showOrderDetail(code: String) {
        stopPolling()
        val s = shell("Đơn $code", 3); setContentView(s); val c = contentOf(s)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(box)
        loading(box)
        renderOrderDetail(box, code)
    }

    private fun renderOrderDetail(box: LinearLayout, code: String) {
        executor.execute {
            val r = account.request("order", "GET", null, emptyMap(), mapOf("code" to code))
            runOnUiThread {
                box.removeAllViews()
                if (!r.optBoolean("ok")) { box.addView(label(r.optString("message", "Không tải được đơn hàng."), 14f, danger)); return@runOnUiThread }
                val data = r.optJSONObject("data") ?: JSONObject()
                val o = data.optJSONObject("order") ?: JSONObject()
                val items = data.optJSONArray("items") ?: JSONArray()
                val payment = data.optJSONObject("payment")
                val status = o.optString("status")
                val payStatus = o.optString("payment_status", "pending")
                val confirmed = status in listOf("Đã xác nhận", "Đang nấu", "Đang giao", "Hoàn thành")

                box.addView(label("Mã đơn: $code", 16f, dark, true))
                box.addView(label("Trạng thái: $status", 15f, primary, true))
                box.addView(label("Thanh toán: " + if (payStatus == "paid") "✅ Đã thanh toán" else "💳 Chờ thanh toán", 14f, if (payStatus == "paid") ok else Color.rgb(198, 130, 8)))
                box.addView(label("Địa chỉ: ${o.optString("address")}", 13f, secondary))
                if (o.optString("delivery_time").isNotBlank()) box.addView(label("Giờ giao: ${o.optString("delivery_time")}", 13f, secondary))
                if (o.optString("note").isNotBlank()) box.addView(label("Ghi chú: ${o.optString("note")}", 13f, secondary))

                box.addView(label("Món đã đặt", 16f, dark, true).apply { setPadding(0, dp(14), 0, dp(6)) })
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    box.addView(label("• ${it.optString("name")} x${it.optInt("qty")} — ${money(it.optInt("price") * it.optInt("qty"))}", 14f, dark))
                }
                box.addView(label("Tổng cộng: ${money(o.optInt("total"))}", 18f, primary, true).apply { setPadding(0, dp(10), 0, dp(4)) })

                // ---- THANH TOÁN QR (VietQR chuyển khoản ngân hàng) ----
                if (payStatus != "paid" && payment != null) {
                    val payBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(Color.WHITE, 16); setPadding(dp(14), dp(14), dp(14), dp(14)) }
                    payBox.addView(label("💳 Quét mã để thanh toán", 16f, dark, true))
                    val qr = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    payBox.addView(qr, LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(8) })
                    ImageLoader.load(qr, payment.optString("qr_url"))
                    payBox.addView(label("Ngân hàng: ${payment.optString("bank_display_name")}", 13f, secondary))
                    payBox.addView(label("Số TK: ${payment.optString("bank_account_no")}", 13f, secondary))
                    payBox.addView(label("Chủ TK: ${payment.optString("bank_account_name")}", 13f, secondary))
                    payBox.addView(label("Nội dung CK: ${payment.optString("transfer_content")}", 13f, dark, true))
                    payBox.addView(label("Số tiền: ${money(payment.optInt("amount"))}", 15f, primary, true))
                    payBox.addView(label("Hệ thống tự động xác nhận sau khi nhận được chuyển khoản.", 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
                    box.addView(payBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

                    // Tự động dò trạng thái thanh toán mỗi 4 giây, giống order.php trên web.
                    stopPolling()
                    val runnable = object : Runnable {
                        override fun run() { renderOrderDetail(box, code); handler.postDelayed(this, 4000) }
                    }
                    pollRunnable = runnable
                    handler.postDelayed(runnable, 4000)
                } else stopPolling()

                // ---- XÁC NHẬN ĐÃ NHẬN HÀNG ----
                if ((o.optInt("delivery_confirmed") == 1)) {
                    box.addView(label("✅ Đã nhận hàng • +${o.optInt("points_earned")} điểm", 14f, ok, true).apply { setPadding(0, dp(10), 0, 0) })
                } else if (status == "Hoàn thành") {
                    box.addView(button("📦 Tôi đã nhận hàng") {
                        executor.execute {
                            val cr = account.request("confirm_delivery", "POST", JSONObject().put("code", code).toString())
                            runOnUiThread { toast(cr.optString("message", if (cr.optBoolean("ok")) "Đã xác nhận" else "Có lỗi xảy ra")); renderOrderDetail(box, code) }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
                }

                // ---- MÃ QUAY THƯỞNG ----
                val luckyCode = o.optString("lucky_code", "")
                if (confirmed && luckyCode.isNotBlank() && luckyCode != "null") {
                    box.addView(button("🎁 Dùng mã quay thưởng: $luckyCode") { showLucky(luckyCode) }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
                }
            }
        }
    }

    // =========================================================================
    // QUAY SỐ MAY MẮN — quay thưởng theo mã đơn (dùng 1 lần).
    // =========================================================================
    private fun showLucky(prefillCode: String?) {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập"); showLogin(); return }
        val s = shell("Quay số trúng thưởng", 4); setContentView(s); val c = contentOf(s)
        c.addView(label("🎁 Bốc thăm trúng thưởng", 20f, dark, true))
        c.addView(label("Mỗi đơn hàng đã xác nhận tặng 1 mã quay thưởng, dùng được đúng 1 lần.", 13f, secondary))
        val codeInput = EditText(this).apply { hint = "VD: LK-7K9QRX"; setText(prefillCode ?: ""); textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = bg(Color.WHITE, 12) }
        c.addView(codeInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(10) })
        val resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(resultBox)
        c.addView(button("🎁 Quay ngay") {
            val code = codeInput.text.toString().trim().uppercase()
            if (code.isBlank()) { toast("Vui lòng nhập mã quay thưởng"); return@button }
            resultBox.removeAllViews(); val lv = loading(resultBox, "Đang quay số...")
            executor.execute {
                val r = account.request("lucky_draw", "POST", JSONObject().put("code", code).toString())
                runOnUiThread {
                    resultBox.removeView(lv)
                    if (!r.optBoolean("ok")) { resultBox.addView(label(r.optString("message", "Có lỗi xảy ra."), 14f, danger)); return@runOnUiThread }
                    val data = r.optJSONObject("data") ?: JSONObject()
                    val already = data.optBoolean("already")
                    val prizeBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(Color.WHITE, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
                    prizeBox.addView(label(if (already) "Mã này đã được sử dụng rồi!" else "🎉 Chúc mừng bạn!", 17f, dark, true))
                    prizeBox.addView(label("🎁 ${data.optString("prize_name")}", 20f, primary, true).apply { setPadding(0, dp(8), 0, dp(8)) })
                    prizeBox.addView(label(if (already) "Mỗi mã chỉ quay được 1 lần. Đặt thêm đơn để nhận mã mới nhé!" else "Vui lòng liên hệ quán để nhận thưởng.", 13f, secondary))
                    resultBox.addView(prizeBox)
                }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        c.addView(ghostButton("🔢 Số may mắn hằng ngày") { showDaily() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) } })
    }

    // =========================================================================
    // SỐ MAY MẮN HẰNG NGÀY — chương trình tự động, đối 4 số cuối mã quay thưởng
    // với số công bố lúc 16:15 giờ VN hằng ngày.
    // =========================================================================
    private fun showDaily() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập"); showLogin(); return }
        val s = shell("Số may mắn hằng ngày", 4); setContentView(s); val c = contentOf(s)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(box)
        val lv = loading(box)
        executor.execute {
            val r = account.request("daily_number")
            runOnUiThread {
                box.removeView(lv)
                if (!r.optBoolean("ok")) { box.addView(label(r.optString("message", "Không tải được dữ liệu."), 14f, danger)); return@runOnUiThread }
                val d = r.optJSONObject("data") ?: JSONObject()
                val prizeBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(Color.WHITE, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
                prizeBox.addView(label("Số dự thưởng kỳ ${d.optString("period")}", 15f, dark, true))
                if (d.optBoolean("published")) {
                    prizeBox.addView(label(d.optString("number"), 30f, primary, true))
                    prizeBox.addView(label("✅ Số của kỳ này đã được công bố.", 13f, secondary))
                } else if (d.optBoolean("draw_due")) {
                    prizeBox.addView(label("⏳ Chưa công bố", 22f, secondary))
                    prizeBox.addView(label("Đã đến giờ quay nhưng số chưa được duyệt & công bố.", 13f, secondary))
                } else {
                    prizeBox.addView(label("⏳ Chưa quay", 22f, secondary))
                    prizeBox.addView(label("Hệ thống tự bốc số lúc ${d.optString("draw_time")} hôm nay.", 13f, secondary))
                }
                box.addView(prizeBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

                box.addView(label("Thể lệ (đối 4 số cuối mã quay thưởng, từ phải sang):", 14f, dark, true))
                val rules = d.optJSONArray("rules") ?: JSONArray()
                for (i in 0 until rules.length()) { val ru = rules.getJSONObject(i); box.addView(label("• ${ru.optString("label")} — ${ru.optString("prize")}", 13f, secondary)) }

                val history = d.optJSONArray("history") ?: JSONArray()
                if (history.length() > 0) {
                    box.addView(label("Lịch sử mã dự thưởng", 16f, dark, true).apply { setPadding(0, dp(14), 0, dp(6)) })
                    for (i in 0 until history.length()) {
                        val h = history.getJSONObject(i)
                        val matched = h.optBoolean("matched")
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 12); setPadding(dp(10), dp(9), dp(10), dp(9)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) } }
                        row.addView(label("#${h.optString("order_code")} • kỳ ${h.optString("week_key")} • số cuối ${h.optString("digits")}", 13f, dark))
                        row.addView(label(if (matched) "🎉 Trúng ${h.optString("tier_label")} — ${h.optString("prize_name")}" else "❌ Không trúng", 13f, if (matched) ok else secondary, matched))
                        box.addView(row)
                    }
                }
            }
        }
    }

    // =========================================================================
    // ĐIỂM TÍCH LUỸ / HẠNG THÀNH VIÊN
    // =========================================================================
    private fun showLoyalty() {
        if (!account.isLoggedIn()) { toast("Vui lòng đăng nhập"); showLogin(); return }
        val s = shell("Điểm tích luỹ", 4); setContentView(s); val c = contentOf(s)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; c.addView(box)
        val lv = loading(box)
        executor.execute {
            val r = account.request("loyalty")
            runOnUiThread {
                box.removeView(lv)
                if (!r.optBoolean("ok")) { box.addView(label(r.optString("message", "Không tải được dữ liệu."), 14f, danger)); return@runOnUiThread }
                val d = r.optJSONObject("data") ?: JSONObject()
                val points = d.optInt("points")
                val prizeBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE, 16); setPadding(dp(16), dp(16), dp(16), dp(16)) }
                prizeBox.addView(label("★ ${String.format("%,d", points)} điểm", 24f, primary, true))
                val next = d.optJSONObject("next_tier")
                if (next != null) prizeBox.addView(label("Còn ${next.optInt("points_needed")} điểm nữa để đổi \"${next.optString("reward_name")}\".", 13f, secondary))
                else prizeBox.addView(label("Bạn đã đạt mốc quà cao nhất hiện có.", 13f, secondary))
                prizeBox.addView(label("Điểm được cộng sau khi bạn xác nhận đã nhận hàng. Mỗi 50.000đ = 1 điểm.", 12f, secondary).apply { setPadding(0, dp(6), 0, 0) })
                box.addView(prizeBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

                box.addView(label("Mốc quà", 16f, dark, true))
                val tiers = d.optJSONArray("tiers") ?: JSONArray()
                for (i in 0 until tiers.length()) {
                    val t = tiers.getJSONObject(i); val eligible = t.optBoolean("eligible")
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(Color.WHITE, 12); setPadding(dp(10), dp(9), dp(10), dp(9)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) } }
                    row.addView(label("${t.optInt("points_required")} điểm — ${t.optString("reward_name")}", 13.5f, dark), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label(if (eligible) "✓ Đủ điều kiện" else "Chưa đủ", 12.5f, if (eligible) ok else secondary))
                    box.addView(row)
                }

                val redemptions = d.optJSONArray("redemptions") ?: JSONArray()
                if (redemptions.length() > 0) {
                    box.addView(label("Lịch sử đổi quà", 16f, dark, true).apply { setPadding(0, dp(14), 0, dp(6)) })
                    for (i in 0 until redemptions.length()) {
                        val red = redemptions.getJSONObject(i)
                        box.addView(label("• ${red.optString("reward_name")} — ${red.optInt("points_used")} điểm (${red.optString("created_at")})", 13f, secondary))
                    }
                }
            }
        }
    }

    // =========================================================================
    // TÀI KHOẢN — thông tin đầy đủ, đồng bộ trực tiếp với tài khoản trên web.
    // =========================================================================
    private fun showProfile() {
        val s = shell("Tài khoản", 4); setContentView(s); val c = contentOf(s)
        if (!account.isLoggedIn()) { c.addView(label("👤 Tài khoản khách hàng", 22f, dark, true)); c.addView(label("Đăng nhập để đồng bộ tài khoản với tài khoản COM11H đang dùng trên website.")); c.addView(button("🔐 Đăng nhập / Đăng ký") { showLogin() }); return }
        c.addView(label("👤 Tài khoản khách hàng", 22f, dark, true)); val loadingView = label("Đang đồng bộ thông tin tài khoản...", 15f, secondary); c.addView(loadingView)
        executor.execute {
            try {
                val r = account.request("profile")
                runOnUiThread {
                    c.removeView(loadingView)
                    if (r.optBoolean("ok")) {
                        val customer = r.optJSONObject("data")?.optJSONObject("customer") ?: r.optJSONObject("data") ?: JSONObject()
                        c.addView(label("Họ tên: ${customer.optString("name", "Chưa cập nhật")}", 17f, dark, true))
                        c.addView(label("Số điện thoại: ${customer.optString("phone", "")}"))
                        c.addView(label("Điểm tích lũy: ${customer.optInt("points", 0)} điểm", 18f, primary, true))
                        c.addView(label("Toàn bộ đơn hàng, thanh toán và mã quay thưởng của bạn được đồng bộ trực tiếp với tài khoản trên website.", 12.5f, secondary))

                        val grid = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                        c.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                        grid.addView(ghostButton("▤ Đơn hàng của tôi") { showOrders() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("⭐ Điểm tích luỹ & hạng thành viên") { showLoyalty() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("🎁 Quay số trúng thưởng") { showLucky(null) }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })
                        grid.addView(ghostButton("🔢 Số may mắn hằng ngày") { showDaily() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } })

                        c.addView(button("🔄 Đồng bộ lại") { showProfile() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } })
                        c.addView(button("🚪 Đăng xuất") { account.logout(); showProfile() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } })
                    } else {
                        account.logout(); c.addView(label(r.optString("message", "Phiên đăng nhập đã hết hạn."))); c.addView(button("Đăng nhập lại") { showLogin() })
                    }
                }
            } catch (_: Exception) { runOnUiThread { loadingView.text = "Không thể đồng bộ tài khoản. Kiểm tra mạng rồi thử lại."; c.addView(button("Thử lại") { showProfile() }) } }
        }
    }

    // =========================================================================
    // NHẮC GIỜ NHẬN CƠM — nút 🔔 nổi ở góc dưới-phải màn hình (chỉ ở bước đặt
    // hàng), không chiếm diện tích nội dung. Khách chọn giờ muốn được nhắc,
    // app đặt 1 báo thức cục bộ (AlarmManager) — không cần server, không tốn
    // phí, không thêm thư viện ngoài.
    // =========================================================================
    private var pendingReminderTime = 0L
    private var pendingReminderMsg = ""

    /** Thêm nút 🔔 nổi vào GÓC DƯỚI-PHẢI của toàn màn hình (không phải chỉ trong nội dung cuộn), nổi phía trên thanh điều hướng dưới cùng, kèm chú thích "Đặt hẹn lấy hàng" ngay bên dưới để khách biết công dụng của nút. */
    private fun addDeliveryReminderFab(prefillTimeText: () -> String) {
        val root = findViewById<FrameLayout>(android.R.id.content)
        val fab = TextView(this).apply {
            text = "⏰"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(primary) }
            elevation = dp(6).toFloat()
            setOnClickListener { showReminderTimePicker(prefillTimeText()) }
        }
        val caption = TextView(this).apply {
            text = "Đặt hẹn lấy cơm"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(primary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        }
        val fabGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(fab, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(caption, LinearLayout.LayoutParams(dp(90), -2))
        }
        root.addView(fabGroup, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16); bottomMargin = dp(16) + dp(58) // dp(58) = chiều cao thanh điều hướng, tránh đè lên
        })
    }

    /** Dò giờ dạng "18:30" / "18h30" / "6h" trong chuỗi khách đã gõ, dùng làm giờ mặc định mở time picker (không bắt buộc phải đúng, khách vẫn chỉnh lại được). */
    private fun parseHourMinute(text: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2})\s*[:h]\s*(\d{1,2})?""").find(text) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun showReminderTimePicker(prefillText: String) {
        val now = java.util.Calendar.getInstance()
        val (defHour, defMinute) = parseHourMinute(prefillText) ?: (now.get(java.util.Calendar.HOUR_OF_DAY) to now.get(java.util.Calendar.MINUTE))
        android.app.TimePickerDialog(this, { _, hour, minute ->
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute); set(java.util.Calendar.SECOND, 0)
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            scheduleReminderWithPermission(cal.timeInMillis, String.format("Đã %02d:%02d — tới giờ nhận cơm bạn đặt rồi, ra nhận nhé!", hour, minute))
        }, defHour, defMinute, true).show()
    }

    private fun scheduleReminderWithPermission(triggerAt: Long, message: String) {
        if (!ReminderHelper.hasNotificationPermission(this)) {
            pendingReminderTime = triggerAt; pendingReminderMsg = message
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001)
            return
        }
        if (ReminderHelper.schedule(this, triggerAt, message)) toast("🔔 Đã đặt nhắc giờ nhận cơm") else toast("Không đặt được nhắc giờ")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 9001) return
        if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED && pendingReminderTime > 0) {
            if (ReminderHelper.schedule(this, pendingReminderTime, pendingReminderMsg)) toast("🔔 Đã đặt nhắc giờ nhận cơm")
            pendingReminderTime = 0L
        } else {
            toast("Cần cho phép thông báo để dùng tính năng nhắc giờ")
        }
    }

    private fun input(hint: String, password: Boolean = false) = EditText(this).apply { this.hint = hint; textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10)); if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
    private fun showLogin() {
        val s = shell("Đăng nhập", 4); setContentView(s); val c = contentOf(s); val phone = input("Số điện thoại"); val pass = input("Mật khẩu", true); c.addView(phone); c.addView(pass)
        c.addView(button("Đăng nhập") { val p = phone.text.toString().trim(); val pw = pass.text.toString(); if (p.isBlank() || pw.isBlank()) { toast("Vui lòng nhập đầy đủ thông tin"); return@button }; executor.execute { try { val r = account.request("login", "POST", JSONObject(mapOf("phone" to p, "password" to pw, "device" to "COM11H Android")).toString()); runOnUiThread { if (r.optBoolean("ok")) { account.saveToken(r.optJSONObject("data")?.optString("token", "") ?: ""); toast("Đăng nhập thành công"); showProfile() } else toast(r.optString("message", "Đăng nhập thất bại")) } } catch (_: Exception) { runOnUiThread { toast("Không kết nối được máy chủ tài khoản") } } } })
        c.addView(button("Đăng ký tài khoản mới") { showRegister() })
    }
    private fun showRegister() {
        val s = shell("Đăng ký tài khoản", 4); setContentView(s); val c = contentOf(s); val name = input("Họ tên"); val phone = input("Số điện thoại"); val pass = input("Mật khẩu", true); val pass2 = input("Nhập lại mật khẩu", true); c.addView(name); c.addView(phone); c.addView(pass); c.addView(pass2)
        c.addView(button("Tạo tài khoản") { if (name.text.isBlank() || phone.text.isBlank() || pass.text.length < 6 || pass.text.toString() != pass2.text.toString()) { toast("Kiểm tra lại thông tin đăng ký"); return@button }; executor.execute { try { val body = JSONObject(mapOf("name" to name.text.toString().trim(), "phone" to phone.text.toString().trim(), "password" to pass.text.toString(), "password2" to pass2.text.toString(), "device" to "COM11H Android")).toString(); val r = account.request("register", "POST", body); runOnUiThread { if (r.optBoolean("ok")) { account.saveToken(r.optJSONObject("data")?.optString("token", "") ?: ""); toast("Đăng ký thành công"); showProfile() } else toast(r.optString("message", "Đăng ký thất bại")) } } catch (_: Exception) { runOnUiThread { toast("Không kết nối được máy chủ tài khoản") } } } })
        c.addView(button("Đăng nhập") { showLogin() })
    }
}
