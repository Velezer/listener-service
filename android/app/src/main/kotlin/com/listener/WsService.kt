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
        private const val EVENT_NOTIFICATION_ID = 2

        @Volatile
        private var instance: WeakReference<WsService>? = null

        init {
            System.loadLibrary("rust_core")
        }

        external fun startWs(url: String)
        external fun stopWs()

        // --- JNI callbacks (called from Rust, must be @JvmStatic) ---

        @JvmStatic
        fun onWsConnecting(msg: String) {
            instance?.get()?.handleEvent("Connecting", msg, android.R.drawable.ic_popup_sync)
        }

        @JvmStatic
        fun onWsConnected(msg: String) {
            instance?.get()?.handleEvent("Connected", msg, android.R.drawable.ic_dialog_info)
        }

        @JvmStatic
        fun onWsMessage(msg: String) {
            instance?.get()?.handleEvent("Message", msg, android.R.drawable.ic_dialog_email)
        }

        @JvmStatic
        fun onWsDisconnected(msg: String) {
            instance?.get()?.handleEvent("Disconnected", msg, android.R.drawable.ic_dialog_alert)
        }

        @JvmStatic
        fun onWsError(msg: String) {
            instance?.get()?.handleEvent("Error", msg, android.R.drawable.ic_delete)
        }

        @JvmStatic
        fun onWsStopped(msg: String) {
            instance?.get()?.handleEvent("Stopped", msg, android.R.drawable.ic_media_pause)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForeground("Fetching WS URL...")

        serviceScope.launch {
            try {
                val apiUrl =
                    "https://context-service-production-722e.up.railway.app/context"
                val wsUrl = ApiClient.fetchWsUrl(apiUrl)

                if (wsUrl != null) {
                    // Rust loop handles connect/reconnect; lifecycle events
                    // are pushed back via the static JNI callbacks above.
                    startWs(wsUrl)
                } else {
                    withContext(Dispatchers.Main) {
                        updateForeground("Failed to get WS URL")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateForeground("Error: ${e.message}")
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

    /**
     * Central handler for all WS lifecycle events.
     * Updates the foreground notification status and posts a separate
     * heads-up notification so the user sees every event.
     */
    private fun handleEvent(title: String, detail: String, icon: Int) {
        updateForeground("$title: $detail")
        postEventNotification(title, detail, icon)
    }

    private fun updateForeground(msg: String) {
        foregroundBuilder.setContentText(msg)
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, foregroundBuilder.build())
    }

    private fun postEventNotification(title: String, detail: String, icon: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WS $title")
            .setContentText(detail)
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(EVENT_NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        updateForeground("Service destroyed")
        stopWs()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }
}