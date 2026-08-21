package com.com11h.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Xem ảnh banner trang chủ PHÓNG TO ngay trong app — không còn điều hướng
 * sang link đích/website nữa. Khách có thể chụm/mở 2 ngón tay để phóng to,
 * thu nhỏ, kéo xem chi tiết ảnh (xem ZoomableImageView), và double-tap để
 * zoom nhanh. Đóng bằng nút ✕ ở góc trên hoặc nút Back của máy.
 *
 * Nhận vào qua Intent extras:
 *   - "image": URL ảnh banner (bắt buộc)
 *   - "title": tiêu đề banner (tuỳ chọn, hiển thị đè phía dưới ảnh)
 */
class BannerViewActivity : SessionActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUrl = intent.getStringExtra("image") ?: ""
        val title = intent.getStringExtra("title") ?: ""

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val imageView = ZoomableImageView(this)
        root.addView(imageView, FrameLayout.LayoutParams(-1, -1))

        val loadingLabel = TextView(this).apply {
            text = "Đang tải ảnh..."
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        root.addView(loadingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        if (imageUrl.isBlank()) {
            loadingLabel.text = "Không có ảnh để hiển thị."
        } else {
            ImageLoader.load(imageView, imageUrl) { loadingLabel.visibility = View.GONE }
        }

        if (title.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setBackgroundColor(Color.argb(140, 0, 0, 0))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        root.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(14) })

        setContentView(root)
    }
}
