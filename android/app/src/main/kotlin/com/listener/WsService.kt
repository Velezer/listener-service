package com.listener

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class WsService : Service() {

    companion object {
        private const val CHANNEL_ID = "ws_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val MESSAGE_NOTIFICATION_ID = 2

        // Weak reference so JNI can call back without preventing GC
        @Volatile
        private var instance: WeakReference<WsService>? = null

        init {
            System.loadLibrary("rust_core")
        }

        external fun startWs(url: String)
        external fun stopWs()

        /**
         * Called from Rust JNI to post a notification with the WS message.
         * Must be static so Rust can invoke it via call_static_method.
         */
        @JvmStatic
        fun onWsMessage(msg: String) {
            instance?.get()?.showMessageNotification(msg)
        }
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var foregroundBuilder: NotificationCompat.Builder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)

        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        foregroundBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WS Listener")
            .setContentText("Service starting...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundBuilder.build())
        updateForegroundNotification("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundNotification("Fetching WS URL...")

        serviceScope.launch {
            try {
                val apiUrl =
                    "https://context-service-production-722e.up.railway.app/context"
                val wsUrl = ApiClient.fetchWsUrl(apiUrl)

                if (wsUrl != null) {
                    withContext(Dispatchers.Main) {
                        updateForegroundNotification("Connecting to WS...")
                    }
                    startWs(wsUrl)
                    withContext(Dispatchers.Main) {
                        updateForegroundNotification("WS disconnected")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateForegroundNotification("Failed to get WS URL")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateForegroundNotification("Error: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WS Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Updates the persistent foreground notification (status text). */
    private fun updateForegroundNotification(msg: String) {
        foregroundBuilder.setContentText(msg)
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, foregroundBuilder.build())
    }

    /** Posts a separate notification for each incoming WS message. */
    private fun showMessageNotification(msg: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WS Message")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        updateForegroundNotification("Service destroyed")
        stopWs()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}