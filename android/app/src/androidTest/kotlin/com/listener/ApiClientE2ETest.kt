package com.listener

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONException

@RunWith(AndroidJUnit4::class)
class ApiClientE2ETest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
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
                .setBody("{\"WS_FEEDER_SERVICE\":\"$expectedWsUrl\"}")
        )

        val apiUrl = mockWebServer.url("/context").toString()

        val result = ApiClient.fetchWsUrl(apiUrl)

        assertEquals(expectedWsUrl, result)
    }

    @Test
    fun fetchWsUrl_throwsJSONExceptionWhenBodyIsEmpty() {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
        )

        val apiUrl = mockWebServer.url("/context").toString()

        assertThrows(JSONException::class.java) {
            ApiClient.fetchWsUrl(apiUrl)
        }
    }
}
