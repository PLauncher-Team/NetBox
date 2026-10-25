package com.example.netbox.xray

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class SubscriptionFetchResult(
    val configs: List<VlessConfig>,
    /** True when the result came from the local cache rather than a successful download. */
    val loadedFromCache: Boolean
)

object SubscriptionFetcher {
    private const val TAG = "NetBoxLog-SubFetcher"
    private const val PREFS_NAME = "netbox_subscription_cache"
    private const val KEY_URL = "subscription_url"
    private const val KEY_CONTENT = "subscription_content"

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun fetchVlessConfigs(context: Context, urlStr: String): SubscriptionFetchResult =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            AppLogger.d(TAG, "Fetching subscription from URL: $urlStr")

            var downloadedContent: String? = null
            var connection: HttpURLConnection? = null
            try {
                connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "v2rayNG/1.8.5")

                val responseCode = connection.responseCode
                AppLogger.d(TAG, "HTTP Response Code: $responseCode")
                if (responseCode in 200..299) {
                    downloadedContent = BufferedReader(
                        InputStreamReader(connection.inputStream, Charsets.UTF_8)
                    ).use { it.readText() }.takeIf { it.isNotBlank() }
                } else {
                    AppLogger.w(TAG, "Subscription server returned HTTP $responseCode")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Subscription download failed: ${e.message}")
            } finally {
                connection?.disconnect()
            }

            if (!downloadedContent.isNullOrBlank()) {
                val downloadedConfigs = parseVlessConfigs(downloadedContent)
                if (downloadedConfigs.isNotEmpty()) {
                    prefs.edit()
                        .putString(KEY_URL, urlStr)
                        .putString(KEY_CONTENT, downloadedContent)
                        .apply()
                    AppLogger.d(TAG, "Loaded ${downloadedConfigs.size} configs from the network and updated the cache")
                    return@withContext SubscriptionFetchResult(downloadedConfigs, loadedFromCache = false)
                }
                AppLogger.w(TAG, "Downloaded subscription contained no valid VLESS entries; trying cache")
            }

            val cachedContent = if (prefs.getString(KEY_URL, null) == urlStr) {
                prefs.getString(KEY_CONTENT, null)
            } else {
                null
            }
            val cachedConfigs = cachedContent?.let(::parseVlessConfigs).orEmpty()
            if (cachedConfigs.isNotEmpty()) {
                AppLogger.d(TAG, "Using cached subscription with ${cachedConfigs.size} configs")
                return@withContext SubscriptionFetchResult(cachedConfigs, loadedFromCache = true)
            }

            AppLogger.e(TAG, "Subscription download failed and no valid cache is available")
            SubscriptionFetchResult(emptyList(), loadedFromCache = true)
        }

    @OptIn(ExperimentalEncodingApi::class)
    private fun parseVlessConfigs(rawContent: String): List<VlessConfig> {
        var decoded = rawContent.trim()
        try {
            val clean = decoded.replace("\n", "").replace("\r", "").replace(" ", "")
            decoded = String(Base64.decode(clean), Charsets.UTF_8)
            AppLogger.d(TAG, "Decoded Base64 subscription; decoded length: ${decoded.length} chars")
        } catch (_: Exception) {
            AppLogger.d(TAG, "Subscription is not Base64; parsing as plain text")
        }

        return decoded.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { line ->
                VlessConfig.parse(line).also { config ->
                    if (config == null) AppLogger.w(TAG, "Failed to parse VLESS line: $line")
                }
            }
            .toList()
            .also { AppLogger.d(TAG, "Parsed ${it.size} VLESS configs from subscription") }
    }
}
