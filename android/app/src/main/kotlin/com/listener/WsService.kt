package com.listener

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString

class WsService : Service() {

    companion object {
        private const val CHANNEL_ID = "ws_service_channel"
        private const val FOREGROUND_ID = 1
    }

    private lateinit var notificationManager: NotificationManager
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()

    private enum class WsEvent(
        val title: String,
        val icon: Int,
        val foregroundStatus: String,
        val notifyUser: Boolean = false
    ) {
        STARTED("Started", android.R.drawable.ic_media_play, "Starting listener"),
        CONNECTING("Connecting", android.R.drawable.ic_popup_sync, "Connecting…"),
        CONNECTED("Connected", android.R.drawable.ic_dialog_info, "Connected"),
        MESSAGE("Message", android.R.drawable.ic_dialog_email, "Connected", true),
        DISCONNECTED("Disconnected", android.R.drawable.ic_dialog_alert, "Disconnected", true),
        ERROR("Error", android.R.drawable.ic_delete, "Connection error", true),
        STOPPED("Stopped", android.R.drawable.ic_media_pause, "Stopped", true)
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()

        // Start foreground ONCE
        startForeground(
            FOREGROUND_ID,
            buildForegroundNotification("Starting listener")
        )

        handleEvent(WsEvent.STARTED)
        connectWebSocket()
    }

    private fun connectWebSocket() {
        handleEvent(WsEvent.CONNECTING)

        var wssUrl = ApiClient.fetchWsUrl("https://raw.githubusercontent.com/Velezer/listener-service/refs/heads/main/config.json")

        val request = Request.Builder()
            .url(wssUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                handleEvent(WsEvent.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(WsEvent.MESSAGE, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleEvent(WsEvent.MESSAGE, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                handleEvent(WsEvent.DISCONNECTED, reason)
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleEvent(WsEvent.ERROR, t.message ?: "Unknown error")
            }
        })
    }

    private fun handleEvent(event: WsEvent, message: String? = null) {

        // Update foreground notification cleanly
        notificationManager.notify(
            FOREGROUND_ID,
            buildForegroundNotification(event.foregroundStatus)
        )

        // Post user notification if needed
        if (event.notifyUser) {
            postEventNotification(event, message)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebSocket Service")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun postEventNotification(event: WsEvent, message: String?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(event.title)
            .setContentText(message ?: event.foregroundStatus)
            .setSmallIcon(event.icon)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onDestroy() {
        webSocket?.close(1000, "Service destroyed")
        handleEvent(WsEvent.STOPPED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WebSocket Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
