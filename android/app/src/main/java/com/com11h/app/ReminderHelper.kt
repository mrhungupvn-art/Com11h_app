package com.com11h.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Nhắc khách tới giờ nhận cơm — dùng thẳng AlarmManager + NotificationManager
 * có sẵn trong Android (không server, không thư viện ngoài, không tốn phí,
 * hầu như không làm tăng dung lượng app).
 *
 * Dùng setAndAllowWhileIdle() — báo GẦN ĐÚNG giờ, có thể trễ vài phút nếu máy
 * đang ở chế độ tiết kiệm pin (Doze). Mức chính xác này là đủ cho việc nhắc
 * giờ ăn, và tránh phải xin quyền "báo thức chính xác" (SCHEDULE_EXACT_ALARM)
 * — quyền nhạy cảm mà Google Play xét duyệt khá chặt.
 */
object ReminderHelper {
    private const val CHANNEL_ID = "com11h_delivery_reminder"
    private const val REQUEST_CODE = 8811

    fun channelId() = CHANNEL_ID

    fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nhắc giờ nhận cơm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thông báo nhắc khách tới giờ nhận cơm đã đặt"
            }
        )
    }

    private fun pendingIntent(context: Context, message: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("message", message)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Đặt lịch nhắc tại thời điểm [triggerAtMillis]. Trả về false nếu chưa có quyền thông báo. */
    fun schedule(context: Context, triggerAtMillis: Long, message: String): Boolean {
        if (!hasNotificationPermission(context)) return false
        createChannelIfNeeded(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(context, message))
        return true
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, ""))
    }
}
