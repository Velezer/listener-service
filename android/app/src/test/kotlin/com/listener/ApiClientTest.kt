package com.listener

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: ApiClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        apiClient = ApiClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun fetchWsUrl_returnsWebsocketUrlFromContextPayload() {
        val expectedWsUrl = "wss://example.test/feed"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"wssFeederServiceAggTrade\":\"$expectedWsUrl\"}")
        )

        val apiUrl = mockWebServer.url("/context").toString()

        val result = apiClient.fetchWsUrl(apiUrl)

        assertEquals(expectedWsUrl, result)
    }

    @Test
    fun fetchWsUrl_throwsIOExceptionWhenBodyIsNotJson() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
        )

        val apiUrl = mockWebServer.url("/context").toString()

        val error = assertThrows(IOException::class.java) {
            apiClient.fetchWsUrl(apiUrl)
        }

        assertEquals(true, error.message?.contains("Invalid JSON payload") == true)
    }

    @Test
    fun fetchWsUrl_supportsLegacyWsKey() {
        val expectedWsUrl = "wss://example.test/legacy"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"WS_FEEDER_SERVICE\":\"$expectedWsUrl\"}")
        )

        val apiUrl = mockWebServer.url("/context").toString()

        val result = apiClient.fetchWsUrl(apiUrl)

        assertEquals(expectedWsUrl, result)
    }

    @Test
    fun fetchWsUrl_throwsIOExceptionOnNon2xxResponse() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"internal\"}")
        )

        val apiUrl = mockWebServer.url("/context").toString()

        assertThrows(IOException::class.java) {
            apiClient.fetchWsUrl(apiUrl)
        }
    }
}
