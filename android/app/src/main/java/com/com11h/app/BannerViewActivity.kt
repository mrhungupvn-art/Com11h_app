package com.com11h.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Xem ảnh (banner hoặc ảnh món ăn) PHÓNG TO ngay trong app, và cho phép VUỐT
 * SANG TRÁI/PHẢI để xem tiếp ảnh kế tiếp/trước đó trong cùng danh sách (dải
 * banner đang chạy, "Món ăn phổ biến", "Menu Vip", hay danh sách Thực đơn) —
 * không cần thoát ra rồi bấm lại từng ảnh một. Khách vẫn chụm/mở 2 ngón tay
 * để phóng to/thu nhỏ, kéo xem chi tiết (ZoomableImageView) như trước; vuốt
 * ngang để chuyển ảnh CHỈ hoạt động khi ảnh đang ở trạng thái CHƯA phóng to,
 * giống thao tác vuốt ảnh quen thuộc trên các app khác (không nhầm với kéo
 * ảnh lúc đang xem chi tiết).
 *
 * Nhận vào qua Intent extras:
 *   - "items": chuỗi JSON mảng các ảnh, mỗi phần tử {"image","title" (tuỳ
 *     chọn), "id" (tuỳ chọn, id banner để báo lượt click)}.
 *   - "index": vị trí ảnh hiển thị đầu tiên trong "items" (mặc định 0).
 *   - "ping": true nếu cần âm thầm gọi banner_click.php mỗi khi hiển thị 1
 *     ảnh có "id" > 0 (chỉ dùng cho banner, không dùng cho ảnh món ăn).
 *   - Tương thích cũ: nếu không có "items", dùng "image"/"title" đơn lẻ
 *     (không vuốt được, vì chỉ có đúng 1 ảnh).
 */
class BannerViewActivity : SessionActivity() {
    companion object { private const val SITE_URL = "https://com11h.com" }
    private val executor = Executors.newSingleThreadExecutor()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var items: List<JSONObject> = emptyList()
    private var index = 0
    private var ping = false

    private lateinit var imageView: ZoomableImageView
    private lateinit var titleView: TextView
    private lateinit var counterView: TextView
    private lateinit var loadingLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemsExtra = intent.getStringExtra("items")
        items = if (!itemsExtra.isNullOrBlank()) {
            try {
                val arr = JSONArray(itemsExtra)
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (_: Exception) { emptyList() }
        } else emptyList()
        if (items.isEmpty()) {
            val single = JSONObject().put("image", intent.getStringExtra("image") ?: "").put("title", intent.getStringExtra("title") ?: "")
            items = listOf(single)
        }
        index = intent.getIntExtra("index", 0).coerceIn(0, items.size - 1)
        ping = intent.getBooleanExtra("ping", false)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        imageView = ZoomableImageView(this)
        val swipeContainer = SwipeContainer(this).apply {
            addView(imageView, FrameLayout.LayoutParams(-1, -1))
            onSwipeLeft = { showAt(index + 1) }
            onSwipeRight = { showAt(index - 1) }
        }
        root.addView(swipeContainer, FrameLayout.LayoutParams(-1, -1))

        loadingLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
        root.addView(loadingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // Chỉ báo số thứ tự ("2 / 6") khi có nhiều hơn 1 ảnh, để khách biết
        // còn ảnh khác và có thể vuốt xem tiếp.
        counterView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12.5f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply { setColor(Color.argb(140, 0, 0, 0)); cornerRadius = dp(20).toFloat() }
            visibility = if (items.size > 1) View.VISIBLE else View.GONE
        }
        root.addView(counterView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })

        // Mũi tên trái/phải khi có nhiều ảnh — vừa để bấm chuyển ảnh ngay
        // (không bắt buộc vuốt), vừa gợi ý cho khách biết có thể vuốt.
        if (items.size > 1) {
            root.addView(arrowButton("‹") { showAt(index - 1) }, FrameLayout.LayoutParams(dp(46), dp(64), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(6) })
            root.addView(arrowButton("›") { showAt(index + 1) }, FrameLayout.LayoutParams(dp(46), dp(64), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(6) })
        }

        root.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(14) })

        setContentView(root)
        showAt(index)
    }

    private fun arrowButton(symbol: String, click: () -> Unit) = TextView(this).apply {
        text = symbol; textSize = 30f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.argb(90, 0, 0, 0)); cornerRadius = dp(32).toFloat() }
        setOnClickListener { click() }
    }

    /** Hiển thị ảnh tại vị trí [i] trong danh sách (kẹp trong khoảng hợp lệ — vuốt hết ảnh cuối/đầu thì dừng, không lặp vòng). */
    private fun showAt(i: Int) {
        if (items.isEmpty()) return
        index = i.coerceIn(0, items.size - 1)
        val item = items[index]
        val imageUrl = item.optString("image")
        val title = item.optString("title")

        imageView.resetZoom()
        loadingLabel.text = "Đang tải ảnh..."
        loadingLabel.visibility = View.VISIBLE
        if (imageUrl.isBlank()) {
            loadingLabel.text = "Không có ảnh để hiển thị."
        } else {
            ImageLoader.load(imageView, imageUrl) { loadingLabel.visibility = View.GONE }
        }

        titleView.text = title
        titleView.visibility = if (title.isNotBlank()) View.VISIBLE else View.GONE
        counterView.text = "${index + 1} / ${items.size}"

        if (ping) {
            val id = item.optInt("id")
            if (id > 0) executor.execute {
                try { (java.net.URL("$SITE_URL/banner_click.php?id=$id").openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET" }.inputStream.close() } catch (_: Exception) { }
            }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    /**
     * FrameLayout bắt vuốt ngang để chuyển ảnh kế tiếp/trước đó. Chỉ chiếm
     * quyền xử lý cử chỉ khi ZoomableImageView bên trong đang ở trạng thái
     * CHƯA phóng to — ZoomableImageView tự "nhường" sự kiện chạm cho View
     * cha lúc đó (requestDisallowInterceptTouchEvent(false) khi scale == 1),
     * nên khi khách đã zoom ảnh để xem chi tiết, kéo ảnh để xem các góc vẫn
     * hoạt động bình thường, không bị nhầm thành vuốt chuyển ảnh.
     */
    private class SwipeContainer(context: Context) : FrameLayout(context) {
        var onSwipeLeft: (() -> Unit)? = null
        var onSwipeRight: (() -> Unit)? = null
        private var downX = 0f
        private var downY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val swipeThreshold = 80 * context.resources.displayMetrics.density
        private var tracking = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; tracking = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (!tracking && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) tracking = true
                    if (tracking) return true
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val dx = event.x - downX
                if (dx <= -swipeThreshold) onSwipeLeft?.invoke() else if (dx >= swipeThreshold) onSwipeRight?.invoke()
                tracking = false
            }
            return true
        }
    }
}
