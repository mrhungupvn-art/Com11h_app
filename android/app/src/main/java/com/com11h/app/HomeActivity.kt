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
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Trang chủ COM11H. "Món ăn phổ biến" lấy trực tiếp từ api?action=menu (cùng
 * dữ liệu với web) qua AccountSync — không còn danh sách món ăn giả lập.
 * Banner ở giữa trang và ô tìm kiếm cũng đồng bộ trực tiếp với server:
 *   - Banner: lấy từ api?action=banners, cùng dữ liệu Admin > Banner trang
 *     chủ đang quản lý cho web (admin/banners.php) — đổi banner trên Admin
 *     là app tự cập nhật theo, không cần sửa code app.
 *   - Ô tìm kiếm: có nút bấm 🔍 (và bấm "Tìm kiếm" trên bàn phím) để mở
 *     màn Thực đơn và lọc sẵn theo từ khoá đã nhập.
 */
class HomeActivity : SessionActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var account: AccountSync
    private val primary = Color.rgb(245, 81, 30)
    private val primaryDark = Color.rgb(208, 67, 21)
    private val accent = Color.rgb(255, 112, 64)
    private val bgColor = Color.rgb(255, 248, 245)
    private val text = Color.rgb(38, 38, 38)
    private val secondary = Color.rgb(107, 107, 107)
    // Badge số lượng trên icon 🛒 Giỏ hàng ở thanh điều hướng — cập nhật mỗi khi
    // dựng lại trang chủ và mỗi khi quay lại trang chủ từ màn hình khác (onResume).
    private var cartBadge: TextView? = null
    // Icon 👤 Tài khoản ở góc trên header — đổi màu nền + có chấm xanh khi khách
    // đã đăng nhập, để phân biệt rõ với lúc chưa đăng nhập.
    private var profileIcon: TextView? = null
    private var profileDot: View? = null
    // Khung chứa "Món ăn phổ biến" — giữ lại tham chiếu để có thể tải & xáo lại
    // danh sách món mỗi khi khách quay lại trang chủ (onResume), không chỉ lúc
    // dựng trang lần đầu.
    private var popularBox: LinearLayout? = null
    // "Menu Vip" — dải ảnh món ăn giá trên 40.000đ tự trôi từ phải qua trái.
    // vipRow chứa 2 bản sao danh sách món nối liền nhau để cuộn lặp vô tận
    // (mượt mà, không giật khi quay vòng); vipScrollView là khung cuộn cho
    // phép khách chạm để dừng và tự vuốt qua vuốt lại.
    private var vipScrollView: HorizontalScrollView? = null
    private var vipRow: LinearLayout? = null
    private var vipAutoScrollRunnable: Runnable? = null
    private var vipUserTouching = false
    private var vipSingleSetWidth = 0

    companion object { private const val SITE_URL = "https://com11h.com" }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); account = AccountSync(this); showSplash() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); executor.shutdownNow(); super.onDestroy() }
    // Khách có thể đã thêm/bớt món ở màn Thực đơn hoặc Giỏ hàng, hoặc vừa đăng
    // nhập/đăng xuất, rồi bấm Back quay lại đây (không tạo lại Activity) — cập
    // nhật badge giỏ hàng, icon tài khoản và xáo lại "Món ăn phổ biến" để mỗi
    // lần quay về trang chủ khách luôn thấy các món khác nhau.
    override fun onResume() {
        super.onResume(); refreshCartBadge(); refreshProfileIcon(); loadPopularFoods()
        vipAutoScrollRunnable?.let { handler.post(it) }
    }
    // Dừng dải "Menu Vip" tự trôi khi rời màn hình (đỡ tốn pin/CPU khi không hiển thị).
    override fun onPause() { vipAutoScrollRunnable?.let { handler.removeCallbacks(it) }; super.onPause() }
    // Phiên bị hết hạn NGAY trên Trang chủ (khách đứng yên quá lâu) -> icon 👤
    // đang hiện chấm xanh "đã đăng nhập" cần được cập nhật lại ngay lập tức.
    override fun onSessionExpired() { refreshProfileIcon() }

    /** Cập nhật số lượng (badge đỏ) trên icon 🛒 Giỏ hàng ở thanh điều hướng, đọc từ giỏ hàng cục bộ đã lưu. */
    private fun refreshCartBadge() {
        val n = CartStore.totalQty(this)
        cartBadge?.apply {
            text = if (n > 99) "99+" else n.toString()
            visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    /** Cập nhật màu nền + chấm trạng thái trên icon 👤 Tài khoản theo việc khách đã đăng nhập hay chưa. */
    private fun refreshProfileIcon() {
        val loggedIn = account.isLoggedIn()
        profileIcon?.background = bg(if (loggedIn) Color.rgb(224, 247, 233) else Color.rgb(255, 240, 234), 22)
        profileDot?.visibility = if (loggedIn) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showSplash() {
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(primary, accent, Color.rgb(255, 244, 232)))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(30), dp(28), dp(30)) }
        box.addView(ImageView(this).apply { setImageResource(R.drawable.com11h_logo); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(230), dp(230)))
        box.addView(TextView(this).apply { text = "Cơm 11h"; textSize = 48f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD_ITALIC); gravity = Gravity.CENTER })
        box.addView(TextView(this).apply { text = "xin chào quý khách ❤️"; textSize = 27f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(20)) })
        box.addView(TextView(this).apply { text = "Ngon mỗi ngày • Nóng hổi • Giao tận nơi"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        root.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        handler.postDelayed({ showHome() }, 3000)
    }

    private fun label(value: String, size: Float, color: Int = text, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD) }

    private fun shell(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bgColor) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(9), dp(14), dp(8)); setBackgroundColor(Color.WHITE) }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.com11h_logo); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("Cơm 11h", 20f, primary, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        val profileCell = FrameLayout(this)
        profileIcon = TextView(this).apply { text = "👤"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(primary); setOnClickListener { open("profile") } }
        profileCell.addView(profileIcon, FrameLayout.LayoutParams(dp(44), dp(44)))
        profileDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(46, 125, 50)); setStroke(dp(2), Color.WHITE) }
            visibility = android.view.View.GONE
        }
        profileCell.addView(profileDot, FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(3); rightMargin = dp(3) })
        refreshProfileIcon()
        header.addView(profileCell, LinearLayout.LayoutParams(dp(44), dp(44)))
        outer.addView(header)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(20)) }
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat() }
        listOf("⌂\nTrang chủ", "▦\nThực đơn", "🛒\nGiỏ hàng", "▤\nĐơn hàng", "♙\nTài khoản").forEachIndexed { i, name ->
            val cell = FrameLayout(this)
            cell.addView(TextView(this).apply { text = name; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(if (i == 0) primary else secondary); setTypeface(null, if (i == 0) Typeface.BOLD else Typeface.NORMAL); setPadding(0, dp(5), 0, dp(5)); setOnClickListener { when (i) { 0 -> showHome(); 1 -> open("menu"); 2 -> open("cart"); 3 -> open("orders"); 4 -> open("profile") } } }, FrameLayout.LayoutParams(-1, -1))
            if (i == 2) {
                cartBadge = TextView(this).apply {
                    textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                    background = bg(Color.rgb(220, 38, 38), 20)
                    visibility = android.view.View.GONE
                }
                cell.addView(cartBadge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); marginEnd = dp(14) })
            }
            nav.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        outer.addView(nav)
        refreshCartBadge()
        return outer
    }

    /** Mở màn Thực đơn, lọc sẵn theo từ khoá tìm kiếm đã nhập ở trang chủ. */
    private fun runSearch(keyword: String) {
        val q = keyword.trim()
        val i = Intent(this, MainActivity::class.java).putExtra("screen", "menu")
        if (q.isNotEmpty()) i.putExtra("query", q)
        startActivity(i)
    }

    private fun searchBox(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Tìm món ăn..."
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            background = bg(Color.WHITE, 14)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(v.text.toString()); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(TextView(this).apply {
            text = "🔍"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg(primary, 14)
            contentDescription = "Tìm kiếm"
            setOnClickListener { runSearch(input.text.toString()) }
        }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })
        return row
    }

    /** Banner tĩnh dự phòng khi Admin chưa tạo banner nào hoặc chưa tải được dữ liệu. */
    private fun staticBanner(): LinearLayout {
        val banner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), dp(16), dp(12), dp(16)); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(primaryDark, primary, accent)).apply { cornerRadius = dp(20).toFloat() } }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label("Cơm ngon\nmỗi ngày", 25f, Color.WHITE, true)); copy.addView(label("Ngon – Sạch – Nhanh", 13f, Color.WHITE).apply { setPadding(0, dp(5), 0, 0) })
        banner.addView(copy, LinearLayout.LayoutParams(0, -2, 1f)); banner.addView(ImageView(this).apply { setImageResource(R.drawable.com11h_logo) }, LinearLayout.LayoutParams(dp(100), dp(88)))
        return banner
    }

    /**
     * Bấm vào banner: xem ảnh PHÓNG TO ngay trong app (không nhảy sang link
     * đích/website nữa) — khách chụm/mở 2 ngón tay để phóng to, thu nhỏ, kéo
     * xem chi tiết nội dung trên ảnh. Vẫn âm thầm gọi banner_click.php ở nền
     * (không mở màn hình nào) để Admin tiếp tục nhận đúng số lượt click banner
     * như trước — chỉ khác là app không điều hướng khách ra khỏi màn hình nữa.
     */
    private fun openBanner(id: Int, imageUrl: String, title: String) {
        if (id > 0) executor.execute {
            try { (java.net.URL("$SITE_URL/banner_click.php?id=$id").openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET" }.inputStream.close() } catch (_: Exception) { }
        }
        startActivity(Intent(this, BannerViewActivity::class.java).putExtra("image", imageUrl).putExtra("title", title))
    }

    /**
     * Bấm vào ảnh món ăn (ở "Món ăn phổ biến"): xem ảnh PHÓNG TO ngay trong
     * app, dùng lại đúng màn hình zoom của banner (BannerViewActivity) —
     * khách chụm/mở 2 ngón tay để phóng to, thu nhỏ, kéo xem chi tiết ảnh.
     */
    private fun openFoodImage(imageUrl: String, title: String) {
        if (imageUrl.isBlank()) return
        startActivity(Intent(this, BannerViewActivity::class.java).putExtra("image", imageUrl).putExtra("title", title))
    }

    /** Tải banner trang chủ từ api?action=banners (đồng bộ Admin > Banner trang chủ) và hiển thị dạng slider tự chạy. */
    private fun loadBanners(container: FrameLayout) {
        executor.execute {
            val r = try { account.request("banners") } catch (_: Exception) { null }
            runOnUiThread {
                val arr = r?.optJSONObject("data")?.optJSONArray("banners") ?: JSONArray()
                if (r == null || !r.optBoolean("ok") || arr.length() == 0) {
                    container.removeAllViews(); container.addView(staticBanner(), FrameLayout.LayoutParams(-1, dp(120)))
                    return@runOnUiThread
                }

                val flipper = ViewFlipper(this).apply {
                    inAnimation = AnimationUtils.loadAnimation(this@HomeActivity, android.R.anim.fade_in)
                    outAnimation = AnimationUtils.loadAnimation(this@HomeActivity, android.R.anim.fade_out)
                    flipInterval = 4000
                }
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val id = b.optInt("id")
                    val title = b.optString("title")
                    val imageUrl = b.optString("image")
                    val slide = FrameLayout(this)
                    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    slide.addView(img, FrameLayout.LayoutParams(-1, -1))
                    if (title.isNotBlank()) {
                        slide.addView(TextView(this).apply {
                            text = title; textSize = 13f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                            setPadding(dp(14), dp(8), dp(14), dp(8))
                            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(160, 0, 0, 0)))
                        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    }
                    slide.clipToOutline = true
                    slide.background = bg(Color.rgb(255, 245, 240), 20)
                    slide.setOnClickListener { openBanner(id, imageUrl, title) }
                    flipper.addView(slide)
                    ImageLoader.load(img, b.optString("image"))
                }
                container.removeAllViews()
                container.addView(flipper, FrameLayout.LayoutParams(-1, dp(120)))
                if (arr.length() > 1) flipper.startFlipping()
            }
        }
    }

    /**
     * Tải "Món ăn phổ biến" từ api?action=menu và hiển thị NGẪU NHIÊN 6 món
     * (xáo trộn lại toàn bộ danh sách món đang bán mỗi lần gọi hàm này) — nhờ
     * vậy mỗi lần khách mở lại trang chủ (kể cả bấm Back từ Thực đơn/Giỏ hàng
     * quay về, xem onResume) sẽ thấy các món khác nhau, không cố định mãi
     * cùng vài món như trước (trước đây luôn lấy đúng 4 món đầu danh sách).
     */
    private fun loadPopularFoods() {
        val popularBox = this.popularBox ?: return
        popularBox.removeAllViews()
        val popularLoading = label("⏳ Đang tải món ăn...", 15f, secondary)
        popularBox.addView(popularLoading)
        executor.execute {
            val r = account.request("menu")
            runOnUiThread {
                if (popularBox != this.popularBox) return@runOnUiThread // màn hình đã đổi/hủy trong lúc chờ tải
                popularBox.removeView(popularLoading)
                val arr = r.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                if (arr.length() == 0) { popularBox.addView(label("Chưa có món nào đang bán.", 15f, secondary)); return@runOnUiThread }
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                list.shuffle()
                val count = minOf(6, list.size)
                for (i in 0 until count) {
                    val f = list[i]
                    val name = f.optString("name")
                    val imageUrl = f.optString("image")
                    val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); background = bg(Color.WHITE, 16); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
                    // Ảnh món ăn to hơn trước và có thể bấm vào để xem phóng to
                    // (chụm/mở 2 ngón tay để zoom), giống hệt cách xem banner.
                    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(255, 245, 240), 14); clipToOutline = true }
                    card.addView(img, LinearLayout.LayoutParams(dp(68), dp(68))); ImageLoader.load(img, imageUrl)
                    img.setOnClickListener { openFoodImage(imageUrl, name) }
                    val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
                    info.addView(label(name, 17f, text, true)); info.addView(label(String.format("%,d", f.optInt("price")).replace(',', '.') + "đ", 16f, primary, true)); info.addView(label("còn ${f.optInt("stock")} phần", 13f, secondary)); card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                    info.setOnClickListener { open("menu") }
                    popularBox.addView(card)
                }
            }
        }
    }

    /**
     * Tải danh sách món cho "Menu Vip" (chỉ lấy món giá trên 50.000đ) từ cùng
     * api?action=menu, rồi dựng dải ảnh nằm ngang tự trôi. Nối 2 bản sao danh
     * sách liền nhau trong vipRow để khi cuộn hết bản 1 thì lặp lại y hệt bản
     * 2 — tạo cảm giác trôi vô tận không bị giật/khựng lại.
     */
    private fun loadVipCarousel() {
        val row = vipRow ?: return
        row.removeAllViews()
        row.addView(label("⏳ Đang tải...", 14f, secondary).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
        executor.execute {
            val r = try { account.request("menu") } catch (_: Exception) { null }
            runOnUiThread {
                if (row != this.vipRow) return@runOnUiThread // màn hình đã đổi/hủy trong lúc chờ tải
                row.removeAllViews()
                val arr = r?.optJSONObject("data")?.optJSONArray("foods") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) { val f = arr.getJSONObject(i); if (f.optInt("price") > 40000) list.add(f) }
                if (list.isEmpty()) {
                    row.addView(label("Chưa có món Vip (trên 40.000đ).", 14f, secondary).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
                    return@runOnUiThread
                }
                list.shuffle()
                // Thêm đúng 2 lần cùng danh sách để cuộn lặp liền mạch.
                repeat(2) { list.forEach { f -> row.addView(vipCard(f)) } }
                row.post {
                    vipSingleSetWidth = row.width / 2
                    startVipAutoScroll()
                }
            }
        }
    }

    /** Một thẻ ảnh món trong dải "Menu Vip": ảnh + tên + giá, bấm vào để xem ảnh to và tự thêm vào giỏ. */
    private fun vipCard(f: JSONObject): View {
        val id = f.optInt("id"); val name = f.optString("name"); val imageUrl = f.optString("image")
        val price = f.optInt("price"); val stock = f.optInt("stock")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(148)).apply { marginEnd = dp(10) }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(Color.rgb(255, 245, 240), 14); clipToOutline = true }
        card.addView(img, LinearLayout.LayoutParams(dp(112), dp(100)))
        ImageLoader.load(img, imageUrl)
        card.addView(label(name, 12.5f, text, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0)
        })
        card.addView(label(String.format("%,d", price).replace(',', '.') + "đ", 12.5f, primary, true))
        card.setOnClickListener { onVipFoodTap(id, name, imageUrl, stock) }
        return card
    }

    /** Bấm vào 1 món trong "Menu Vip": tự thêm 1 phần vào giỏ hàng, rồi mở ảnh phóng to ngay sau đó. */
    private fun onVipFoodTap(id: Int, name: String, imageUrl: String, stock: Int) {
        addVipToCart(id, name, stock)
        openFoodImage(imageUrl, name)
    }

    /** Thêm 1 phần món vào giỏ hàng cục bộ (cùng định dạng/nơi lưu với MainActivity), rồi cập nhật badge 🛒. */
    private fun addVipToCart(id: Int, name: String, stock: Int) {
        val p = getSharedPreferences("com11h_local", MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("cart", "[]")) } catch (_: Exception) { JSONArray() }
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optInt("id") == id) {
                val q = o.optInt("qty")
                if (stock in 0 until q + 1) { toast("Chỉ còn $stock phần \"$name\""); return }
                o.put("qty", q + 1); found = true; break
            }
        }
        if (!found) {
            if (stock < 1) { toast("Món \"$name\" đã hết hàng"); return }
            arr.put(JSONObject().put("id", id).put("qty", 1))
        }
        p.edit().putString("cart", arr.toString()).apply()
        refreshCartBadge()
        toast("Đã thêm \"$name\" vào giỏ hàng")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * Tự trôi dải "Menu Vip" từ phải qua trái (tăng dần scrollX), lặp vô tận
     * nhờ 2 bản sao nội dung. Tạm dừng khi khách đang chạm (vipUserTouching)
     * để không "giật" ảnh khỏi tay khi họ đang vuốt xem.
     */
    private fun startVipAutoScroll() {
        vipAutoScrollRunnable?.let { handler.removeCallbacks(it) }
        val scroll = vipScrollView ?: return
        val runnable = object : Runnable {
            override fun run() {
                if (!vipUserTouching && vipSingleSetWidth > 0) {
                    val newX = scroll.scrollX + 2
                    if (newX >= vipSingleSetWidth) scroll.scrollTo(newX - vipSingleSetWidth, 0) else scroll.scrollTo(newX, 0)
                }
                handler.postDelayed(this, 30)
            }
        }
        vipAutoScrollRunnable = runnable
        handler.post(runnable)
    }

    private fun showHome() {
        val shell = shell()
        setContentView(shell)
        val scroll = shell.getChildAt(1) as ScrollView
        val content = scroll.getChildAt(0) as LinearLayout
        content.addView(searchBox(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val bannerContainer = FrameLayout(this)
        bannerContainer.addView(staticBanner(), FrameLayout.LayoutParams(-1, dp(120)))
        content.addView(bannerContainer, LinearLayout.LayoutParams(-1, dp(120)).apply { bottomMargin = dp(15) })
        loadBanners(bannerContainer)

        content.addView(label("Menu Vip", 21f, text, true).apply { setPadding(0, 0, 0, dp(9)) })
        val vipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val vipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vipScroll.addView(vipRow, LinearLayout.LayoutParams(-2, -2))
        // Chạm vào để tạm dừng tự trôi (vẫn vuốt qua vuốt lại bình thường), buông tay
        // ra một lúc thì tự trôi tiếp — không chặn sự kiện chạm nên ScrollView vẫn
        // xử lý vuốt/fling như bình thường.
        vipScroll.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> vipUserTouching = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    handler.postDelayed({ vipUserTouching = false }, 2500)
            }
            false
        }
        content.addView(vipScroll, LinearLayout.LayoutParams(-1, dp(152)).apply { bottomMargin = dp(16) })
        this.vipScrollView = vipScroll
        this.vipRow = vipRow
        loadVipCarousel()

        content.addView(label("Món ăn phổ biến", 21f, text, true).apply { setPadding(0, 0, 0, dp(8)) })
        val popularBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(popularBox)
        this.popularBox = popularBox
        loadPopularFoods()
    }

    private fun open(screen: String) { startActivity(Intent(this, MainActivity::class.java).putExtra("screen", screen)) }
}
