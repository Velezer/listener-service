package com.listener

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class WsService : Service() {

    companion object {
        private const val CHANNEL_ID = "ws_service_channel"
        private const val FOREGROUND_ID = 1
        private const val KEEPALIVE_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 0L
    }

    private lateinit var notificationManager: NotificationManager
    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connectWebSocket() }
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (isServiceStopping) return

            val activeSocket = webSocket
            if (activeSocket != null) {
                val sent = activeSocket.send("ping")
                if (sent) {
                    handleEvent(WsEvent.KEEPALIVE, "ping")
                }
            }

            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }
    private var reconnectAttempt = 0
    @Volatile
    private var isServiceStopping = false
    private val client = OkHttpClient.Builder()
        .pingInterval(KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private enum class WsEvent(
        val title: String,
        val icon: Int,
        val foregroundStatus: String,
        val notifyUser: Boolean = false
    ) {
        STARTED("Started", android.R.drawable.ic_media_play, "Starting listener"),
        CONNECTING("Connecting", android.R.drawable.ic_popup_sync, "Connecting…", true),
        CONNECTED("Connected", android.R.drawable.ic_dialog_info, "Connected"),
        MESSAGE("Message", android.R.drawable.ic_dialog_email, "Connected", true),
        KEEPALIVE("Keepalive", android.R.drawable.ic_popup_sync, "Connected"),
        DISCONNECTED("Disconnected", android.R.drawable.ic_dialog_alert, "Disconnected", true),
        ERROR("Error", android.R.drawable.ic_delete, "Connection error", true),
        STOPPED("Stopped", android.R.drawable.ic_media_pause, "Stopped", true)
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()

        startForeground(
            FOREGROUND_ID,
            buildForegroundNotification("Starting listener")
        )

        handleEvent(WsEvent.STARTED)
        connectWebSocket()
    }

    private fun connectWebSocket() {
        if (isServiceStopping) return

        handleEvent(WsEvent.CONNECTING)

        runInBackground {
            val wssUrl = try {
                ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)
            } catch (t: Throwable) {
                handleEvent(WsEvent.ERROR, "Failed to load websocket config: ${formatThrowable(t)}")
                mainHandler.post { stopSelf() }
                return@Thread
            }

            handleEvent(WsEvent.CONNECTING, "WSS URL: $wssUrl")

            val request = Request.Builder()
                .url(wssUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempt = 0
                    handleEvent(WsEvent.CONNECTED)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleEvent(WsEvent.MESSAGE, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    handleEvent(WsEvent.DISCONNECTED, reason)
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleEvent(WsEvent.DISCONNECTED, "code=$code reason=$reason")
                    scheduleReconnect("closed")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handleEvent(WsEvent.ERROR, formatThrowable(t))
                    scheduleReconnect("failure")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val message = bytes.utf8()
                    if (message.equals("pong", ignoreCase = true)) {
                        handleEvent(WsEvent.KEEPALIVE, "pong")
                    } else {
                        handleEvent(WsEvent.MESSAGE, message)
                    }
                }
            })

            startKeepaliveLoop()
        }
    }

    private fun startKeepaliveLoop() {
        mainHandler.removeCallbacks(keepaliveRunnable)
        mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    private fun scheduleReconnect(trigger: String) {
        if (isServiceStopping) return

        reconnectAttempt += 1
        handleEvent(
            WsEvent.CONNECTING,
            "Reconnect #$reconnectAttempt now ($trigger)"
        )

        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun handleEvent(event: WsEvent, message: String? = null) {
        val foregroundMessage = when {
            message.isNullOrBlank() -> event.foregroundStatus
            else -> "${event.foregroundStatus}: $message"
        }

        notificationManager.notify(
            FOREGROUND_ID,
            buildForegroundNotification(foregroundMessage)
        )

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
        val detailedMessage = message ?: event.foregroundStatus
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(event.title)
            .setContentText(detailedMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailedMessage))
            .setSmallIcon(event.icon)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(nextNotificationId(), notification)
    }

    override fun onDestroy() {
        isServiceStopping = true
        mainHandler.removeCallbacksAndMessages(null)
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

    private fun formatThrowable(t: Throwable): String {
        return generateSequence(t) { it.cause }
            .take(3)
            .joinToString(" -> ") { throwable ->
                val className = throwable::class.java.simpleName
                val message = throwable.message?.trim().orEmpty()
                if (message.isNotEmpty()) "$className: $message" else className
            }
    }

    private fun runInBackground(block: () -> Unit) {
        Thread(block, "ws-service-worker").start()
    }

    private fun nextNotificationId(): Int {
        return (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
    }

}
