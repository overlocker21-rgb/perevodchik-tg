package com.translator.telegram.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.translator.telegram.MainActivity
import com.translator.telegram.R
import com.translator.telegram.overlay.OverlayWindowManager

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "translator_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        val overlay = OverlayWindowManager.getInstance(this)
        overlay.onBubbleClickListener = {
            val accessibilityService = TelegramAccessibilityService.instance
            if (accessibilityService != null) {
                accessibilityService.toggleAutoTranslate()
            } else {
                Toast.makeText(
                    this,
                    "Включите «TG Translator Overlay» в спец. возможностях Android",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                try {
                    startForeground(NOTIFICATION_ID, createNotification())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                OverlayWindowManager.getInstance(this).showBubble()
                isRunning = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val overlay = OverlayWindowManager.getInstance(this)
        overlay.hideBubble()
        overlay.hideGlassPill()
        overlay.updateBubbleState(false)
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Переводчик Telegram",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление активного оверлея перевода"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Плавающий переводчик активен")
            .setContentText("Нажмите на значок для включения/выключения автоперевода")
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
