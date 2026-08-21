package com.com11h.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Nhận báo thức đã đặt ở [ReminderHelper] và hiển thị thông báo nhắc khách
 * tới giờ nhận cơm — nhận được kể cả khi app đang đóng hoàn toàn.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderHelper.createChannelIfNeeded(context)
        val message = intent.getStringExtra("message") ?: "Đến giờ nhận cơm bạn đã đặt rồi!"

        val openIntent = Intent(context, HomeActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, 8812, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ReminderHelper.channelId())
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle("🍚 COM11H")
            .setContentText(message)
            .setSmallIcon(R.drawable.com11h_logo)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(8813, notification)
    }
}
