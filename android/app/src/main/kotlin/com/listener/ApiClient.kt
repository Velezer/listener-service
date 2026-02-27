package com.listener

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

class ApiClient(
    private val callFactory: Call.Factory = OkHttpClient()
) {

    private val supportedConfigKeys = listOf(
        "wssFeederServiceAggTrade",
        "WS_FEEDER_SERVICE"
    )

    fun fetchWsUrl(apiUrl: String): String {
        val request = Request.Builder()
            .url(apiUrl)
            .get()
            .build()

        callFactory.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)

            val wsUrl = supportedConfigKeys
                .asSequence()
                .map { key -> json.optString(key).trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: throw IOException("Missing websocket URL in config. Supported keys: $supportedConfigKeys")

            val parsedUrl = wsUrl.toHttpUrlOrNull()
                ?: throw IOException("Invalid websocket URL in config: $wsUrl")

            if (parsedUrl.scheme != "ws" && parsedUrl.scheme != "wss") {
                throw IOException("Websocket URL must use ws or wss scheme: $wsUrl")
            }

            return wsUrl
        }
    }

    companion object {
        private val defaultClient = ApiClient()

        fun fetchWsUrl(apiUrl: String): String = defaultClient.fetchWsUrl(apiUrl)
    }
}
