package com.example.netbox.xray

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object SubscriptionFetcher {
    private const val TAG = "NetBoxLog-SubFetcher"

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun fetchVlessConfigs(urlStr: String): List<VlessConfig> = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Fetching subscription from URL: $urlStr")
        val rawContent = try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5")

            val responseCode = conn.responseCode
            AppLogger.d(TAG, "HTTP Response Code: $responseCode")
            if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                AppLogger.e(TAG, "HTTP error fetching subscription: $responseCode")
                ""
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Exception during subscription fetch: ${e.message}", e)
            ""
        }

        if (rawContent.isBlank()) {
            AppLogger.e(TAG, "Fetched rawContent is blank!")
            return@withContext emptyList()
        }

        AppLogger.d(TAG, "Raw content fetched length: ${rawContent.length} chars")

        var decoded = rawContent.trim()
        try {
            val clean = decoded.replace("\n", "").replace("\r", "").replace(" ", "")
            val bytes = Base64.decode(clean)
            decoded = String(bytes, Charsets.UTF_8)
            AppLogger.d(TAG, "Successfully decoded Base64 subscription! Decoded length: ${decoded.length} chars")
        } catch (_: Exception) {
            AppLogger.d(TAG, "Raw content is not Base64 or plain text VLESS list, parsing as-is")
        }

        val lines = decoded.lines()
        val configs = mutableListOf<VlessConfig>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("vless://", ignoreCase = true)) {
                val cfg = VlessConfig.parse(trimmed)
                if (cfg != null) {
                    configs.add(cfg)
                } else {
                    AppLogger.w(TAG, "Failed to parse VLESS line: $trimmed")
                }
            }
        }

        AppLogger.d(TAG, "Successfully parsed ${configs.size} VLESS configs from subscription!")
        configs
    }
}
