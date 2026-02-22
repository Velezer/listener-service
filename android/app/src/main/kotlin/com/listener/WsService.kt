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

        external fun startWs(url: String)
        external fun stopWs()

        private enum class WsEvent(
            val title: String,
            val icon: Int
        ) {
            STARTED("Started", android.R.drawable.ic_media_play),
            CONNECTING("Connecting", android.R.drawable.ic_popup_sync),
            CONNECTED("Connected", android.R.drawable.ic_dialog_info),
            MESSAGE("Message", android.R.drawable.ic_dialog_email),
            DISCONNECTED("Disconnected", android.R.drawable.ic_dialog_alert),
            ERROR("Error", android.R.drawable.ic_delete),
            STOPPED("Stopped", android.R.drawable.ic_media_pause)
        }

        // --- JNI callbacks (called from Rust, must be @JvmStatic) ---

        private fun dispatchEvent(event: WsEvent, msg: String) {
            instance?.get()?.handleEvent(event.title, msg, event.icon)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)

        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForeground("Fetching WS URL...")

        serviceScope.launch {
            withContext(Dispatchers.Main) {
                handleEvent(
                    title = "Running",
                    detail = "Reached serviceScope.launch",
                    icon = android.R.drawable.ic_popup_sync
                )
            }

            try {
                val wsUrl = ApiClient.fetchWsUrl(API_URL)

                if (wsUrl != null) {
                    withContext(Dispatchers.Main) {
                        handleEvent(
                            title = "Running",
                            detail = "Starting WebSocket listener",
                            icon = android.R.drawable.ic_popup_sync
                        )
                    }

                    // Rust loop handles connect/reconnect; lifecycle events
                    // are pushed back via the static JNI callbacks above.
                    startWs(wsUrl)

                    withContext(Dispatchers.Main) {
                        handleEvent(
                            title = "Stopped",
                            detail = "WebSocket listener exited",
                            icon = android.R.drawable.ic_media_pause
                        )
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
