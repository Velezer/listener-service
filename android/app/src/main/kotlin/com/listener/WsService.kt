package com.listener

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class WsService : Service() {

    companion object {
        init {
            System.loadLibrary("rust_core")
        }

        external fun startWs(url: String)
        external fun stopWs()
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    // 👇 Lifecycle-aware coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        notificationManager =
            getSystemService(NotificationManager::class.java)

        createNotificationChannel()

        notificationBuilder =
            NotificationCompat.Builder(this, "ws_channel")
                .setContentTitle("WS Listener")
                .setContentText("Service starting...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

        startForeground(1, notificationBuilder.build())

        notifyMessage("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        notifyMessage("Fetching WS URL...")

        serviceScope.launch {
            try {
                val apiUrl =
                    "https://context-service-production-722e.up.railway.app/context"

                val wsUrl = ApiClient.fetchWsUrl(apiUrl)

                if (wsUrl != null) {
                    withContext(Dispatchers.Main) {
                        notifyMessage("Connecting to WS...")
                    }

                    startWs(wsUrl)

                    withContext(Dispatchers.Main) {
                        notifyMessage("WS started")
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        notifyMessage("Failed to get WS URL")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notifyMessage("Error: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "ws_channel",
                "WS Notifications",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(chan)
        }
    }

    fun notifyMessage(msg: String) {
        notificationBuilder.setContentText(msg)
        notificationManager.notify(1, notificationBuilder.build())
    }

    override fun onDestroy() {
        notifyMessage("Service destroyed")
        stopWs()
        serviceScope.cancel()   // 👈 Proper coroutine cleanup
        super.onDestroy()
    }
}