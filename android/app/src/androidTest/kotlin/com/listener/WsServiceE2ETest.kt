package com.listener

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class WsServiceE2ETest {

    @Test
    fun liveConfiguredWebSocket_acceptsConnectionHandshake() {
        val wsUrl = ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)

        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var failureMessage = ""

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failureMessage = "${t::class.java.simpleName}: ${t.message.orEmpty()}"
                    failed.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // No-op: this test validates handshake/open only.
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // No-op: this test validates handshake/open only.
                }
            }
        )

        val didOpen = opened.await(25, TimeUnit.SECONDS)
        val didFail = failed.await(0, TimeUnit.SECONDS)

        socket.close(1000, "test complete")
        client.dispatcher.executorService.shutdown()

        assertTrue("Expected websocket handshake to open for $wsUrl. Failure: $failureMessage", didOpen && !didFail)
    }

    @Test
    fun liveConfiguredWebSocket_receivesMessagesWithoutImmediateDrop() {
        val wsUrl = ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)

        val opened = CountDownLatch(1)
        val firstMessage = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val hasOpened = AtomicBoolean(false)
        var failureMessage = ""

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val socket = client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    hasOpened.set(true)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.isNotBlank()) firstMessage.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.utf8().isNotBlank()) firstMessage.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failureMessage = "${t::class.java.simpleName}: ${t.message.orEmpty()}"
                    failed.countDown()
                }
            }
        )

        val didOpen = opened.await(25, TimeUnit.SECONDS)
        val didReceiveMessage = firstMessage.await(45, TimeUnit.SECONDS)
        val didFail = failed.await(0, TimeUnit.SECONDS)

        socket.close(1000, "test complete")
        client.dispatcher.executorService.shutdown()

        assertTrue(
            "Expected $wsUrl to stay live and stream at least one message. Opened=${hasOpened.get()} receivedMessage=$didReceiveMessage failure=$failureMessage",
            didOpen && didReceiveMessage && !didFail
        )
    }
}
