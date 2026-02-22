package com.listener

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class ApiClient(
    private val callFactory: Call.Factory = OkHttpClient()
) {

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

            return json.getString("WS_FEEDER_SERVICE")
        }
    }

    companion object {
        private val defaultClient = ApiClient()

        fun fetchWsUrl(apiUrl: String): String = defaultClient.fetchWsUrl(apiUrl)
    }
}
