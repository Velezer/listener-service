package com.listener

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

object ApiClient {

    private val client = OkHttpClient()

    fun fetchWsUrl(apiUrl: String): String? {
        val request = Request.Builder()
            .url(apiUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            val json = JSONObject(body)

            return json.getString("WS_FEEDER_SERVICE")
        }
    }
}
