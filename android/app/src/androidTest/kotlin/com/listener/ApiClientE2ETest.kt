package com.listener

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiClientE2ETest {

    @Test
    fun fetchWsUrl_readsLiveHostedConfigOnAndroidRuntime() {
        val result = ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)

        assertTrue("Expected ws:// or wss:// URL, got: $result", result.startsWith("ws://") || result.startsWith("wss://"))
    }

    @Test
    fun fetchWsUrl_liveConfigContainsNonEmptyHostAndPath() {
        val result = ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)

        val uri = java.net.URI(result)
        assertTrue("Expected non-empty host in websocket URL: $result", !uri.host.isNullOrBlank())
        assertTrue("Expected websocket URL path to be present: $result", uri.path.isNotBlank())
    }

    @Test
    fun fetchWsUrl_fallbackResolverReadsFromAtLeastOneLiveEndpoint() {
        val result = ApiClient.fetchWsUrlFromAny(ConfigEndpoints.liveConfigUrls)
        assertTrue("Expected resolved URL to contain a host, got: $result", java.net.URI(result).host?.isNotBlank() == true)
    }

}
