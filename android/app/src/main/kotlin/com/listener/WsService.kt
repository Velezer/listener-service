package com.listener

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class WsService : Service() {

    companion object {
        private const val API_URL = "https://context-service-production-722e.up.railway.app/context"
        private const val CHANNEL_ID = "ws_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val EVENT_NOTIFICATION_ID = 2

        @Volatile
        private var instance: WeakReference<WsService>? = null

        init {
            System.loadLibrary("rust_core")
        }

        @JvmStatic
        external fun startWs(url: String)

        @JvmStatic
        external fun stopWs()

        private enum class WsEvent(
            val title: String,
            val icon: Int,
            val foregroundStatus: String,
            val notifyUser: Boolean
        ) {
            STARTED("Started", android.R.drawable.ic_media_play, "Starting listener", false),
            CONNECTING("Connecting", android.R.drawable.ic_popup_sync, "Connecting…", false),
            CONNECTED("Connected", android.R.drawable.ic_dialog_info, "Connected", false),
            MESSAGE("Message", android.R.drawable.ic_dialog_email, "Connected", true),
            DISCONNECTED("Disconnected", android.R.drawable.ic_dialog_alert, "Disconnected", true),
            ERROR("Error", android.R.drawable.ic_delete, "Connection error", true),
            STOPPED("Stopped", android.R.drawable.ic_media_pause, "Stopped", true)
        }

        // --- JNI callbacks (called from Rust, must be @JvmStatic) ---

        private fun dispatchEvent(event: WsEvent, msg: String) {
            instance?.get()?.handleEvent(event, msg)
        }

        @JvmStatic
        fun onWsStarted(msg: String) {
            dispatchEvent(WsEvent.STARTED, msg)
        }

        @JvmStatic
        fun onWsConnecting(msg: String) {
            dispatchEvent(WsEvent.CONNECTING, msg)
        }

        @JvmStatic
        fun onWsConnected(msg: String) {
            dispatchEvent(WsEvent.CONNECTED, msg)
        }

        @JvmStatic
        fun onWsMessage(msg: String) {
            dispatchEvent(WsEvent.MESSAGE, msg)
        }

        @JvmStatic
        fun onWsDisconnected(msg: String) {
            dispatchEvent(WsEvent.DISCONNECTED, msg)
        }

        @JvmStatic
        fun onWsError(msg: String) {
            dispatchEvent(WsEvent.ERROR, msg)
        }

        @JvmStatic
        fun onWsStopped(msg: String) {
            dispatchEvent(WsEvent.STOPPED, msg)
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
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForeground("Fetching WS URL...")

        serviceScope.launch {
            withContext(Dispatchers.Main) {
                updateForeground("Starting listener")
            }

            try {
                val wsUrl = ApiClient.fetchWsUrl(API_URL)

                if (wsUrl != null) {
                    withContext(Dispatchers.Main) {
                        updateForeground("Starting WebSocket listener")
                    }

                    // Rust loop handles connect/reconnect; lifecycle events
                    // are pushed back via the static JNI callbacks above.
                    startWs(wsUrl)

                    withContext(Dispatchers.Main) {
                        postEventNotification(
                            title = "Stopped",
                            detail = "WebSocket listener exited",
                            icon = android.R.drawable.ic_media_pause
                        )
                        updateForeground("Stopped")
                    }
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
     * Keeps the foreground notification concise and posts a separate
     * notification only for user-relevant events.
     */
    private fun handleEvent(event: WsEvent, detail: String) {
        updateForeground(event.foregroundStatus)

        if (event.notifyUser) {
            postEventNotification(event.title, detail, event.icon)
        }
    }

    private fun updateForeground(msg: String) {
        foregroundBuilder.setContentText(msg)
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, foregroundBuilder.build())
    }

    private fun postEventNotification(title: String, detail: String, icon: Int) {
        val preview = detail.take(120)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WS $title")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
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
