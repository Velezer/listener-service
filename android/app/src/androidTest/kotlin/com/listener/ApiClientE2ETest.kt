package com.listener

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiClientE2ETest {

    @Test
    fun fetchWsUrl_readsLiveHostedConfigOnAndroidRuntime() {
        val configUrl = "https://raw.githubusercontent.com/Velezer/listener-service/main/config.json"

        val result = ApiClient.fetchWsUrl(configUrl)

        assertTrue("Expected ws:// or wss:// URL, got: $result", result.startsWith("ws://") || result.startsWith("wss://"))
    }
}
