package com.listener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WsService : Service() {

    companion object {
        init { System.loadLibrary("rust_core") }
        external fun startWs(url: String)
        external fun stopWs()
    }

    private var notificationManager: NotificationManager? = null
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "ws_channel",
                "WS Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(chan)
        }

        // Single persistent notification
        notificationBuilder = NotificationCompat.Builder(this, "ws_channel")
            .setContentTitle("WS Listener")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        startForeground(1, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWs("wss://yourserver/ws")
        return START_STICKY
    }

    /**
     * Immediately update notification with Rust message
     * No StringBuilder, message ≤64 chars
     */
    fun notifyMessage(msg: String) {
        notificationBuilder.setContentText(msg)
        notificationManager?.notify(1, notificationBuilder.build())
    }

    override fun onDestroy() {
        stopWs()
        super.onDestroy()
    }
}
