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
import java.io.EOFException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class WsService : Service() {

    companion object {
        private const val CHANNEL_ID = "ws_service_channel"
        private const val FOREGROUND_ID = 1
        private const val KEEPALIVE_INTERVAL_MS = 5_000L
        private const val KEEPALIVE_TIMEOUT_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 0L
    }

    private lateinit var notificationManager: NotificationManager
    private var webSocket: WebSocket? = null
    private val connectionGeneration = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connectWebSocket() }
    @Volatile
    private var lastSocketActivityAtMs: Long = 0L
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (isServiceStopping) return

            val activeSocket = webSocket
            if (activeSocket != null) {
                val idleMs = System.currentTimeMillis() - lastSocketActivityAtMs
                if (idleMs >= KEEPALIVE_TIMEOUT_MS) {
                    handleEvent(WsEvent.DISCONNECTED, "keepalive timeout after ${idleMs}ms")
                    closeCurrentSocket("keepalive timeout")
                    scheduleReconnect("keepalive-timeout")
                    return
                }

                val sent = activeSocket.send("ping")
                if (sent) {
                    handleEvent(WsEvent.KEEPALIVE, "ping")
                } else {
                    handleEvent(WsEvent.DISCONNECTED, "keepalive ping send failed")
                    closeCurrentSocket("keepalive send failed")
                    scheduleReconnect("keepalive-send-failed")
                    return
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

        val generation = connectionGeneration.incrementAndGet()
        closeCurrentSocket("replacing stale socket")
        stopKeepaliveLoop()

        handleEvent(WsEvent.CONNECTING)

        runInBackground {
            val wssUrl = try {
                ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)
            } catch (t: Throwable) {
                handleEvent(WsEvent.ERROR, "Failed to load websocket config: ${formatThrowable(t)}")
                mainHandler.post { stopSelf() }
                return@runInBackground
            }

            handleEvent(WsEvent.CONNECTING, "WSS URL: $wssUrl")

            val request = Request.Builder()
                .url(wssUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {

                private fun isCurrentConnection(): Boolean {
                    return generation == connectionGeneration.get()
                }

                private fun shouldSuppressFailure(t: Throwable): Boolean {
                    val message = t.message.orEmpty()
                    return (t is SocketException || t is EOFException) &&
                        (message.contains("Socket closed", ignoreCase = true) ||
                            message.contains("Connection reset", ignoreCase = true) ||
                            message.contains("unexpected end", ignoreCase = true))
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrentConnection()) {
                        webSocket.close(1000, "superseded connection")
                        return
                    }
                    reconnectAttempt = 0
                    markSocketActivity()
                    startKeepaliveLoop()
                    handleEvent(WsEvent.CONNECTED)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrentConnection()) return
                    markSocketActivity()
                    handleEvent(WsEvent.MESSAGE, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentConnection()) {
                        webSocket.close(code, reason)
                        return
                    }
                    stopKeepaliveLoop()
                    handleEvent(WsEvent.DISCONNECTED, reason)
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!isCurrentConnection()) return
                    stopKeepaliveLoop()
                    handleEvent(WsEvent.DISCONNECTED, "code=$code reason=$reason")
                    scheduleReconnect("closed")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!isCurrentConnection()) return
                    stopKeepaliveLoop()
                    if (isServiceStopping) {
                        handleEvent(WsEvent.DISCONNECTED, formatThrowable(t))
                        return
                    }

                    if (shouldSuppressFailure(t)) {
                        handleEvent(WsEvent.DISCONNECTED, formatThrowable(t))
                        scheduleReconnect("expected-socket-reset")
                        return
                    }
                    handleEvent(WsEvent.ERROR, formatThrowable(t))
                    scheduleReconnect("failure")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!isCurrentConnection()) return
                    markSocketActivity()
                    val message = bytes.utf8()
                    if (message.equals("pong", ignoreCase = true)) {
                        handleEvent(WsEvent.KEEPALIVE, "pong")
                    } else {
                        handleEvent(WsEvent.MESSAGE, message)
                    }
                }
            })

        }
    }

    private fun startKeepaliveLoop() {
        markSocketActivity()
        mainHandler.removeCallbacks(keepaliveRunnable)
        mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    private fun stopKeepaliveLoop() {
        mainHandler.removeCallbacks(keepaliveRunnable)
    }

    private fun markSocketActivity() {
        lastSocketActivityAtMs = System.currentTimeMillis()
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
        connectionGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        closeCurrentSocket("Service destroyed")
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

    private fun closeCurrentSocket(reason: String) {
        val socket = webSocket
        webSocket = null
        socket?.close(1000, reason)
    }

}
