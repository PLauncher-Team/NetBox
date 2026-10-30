package com.example.netbox.xray

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ProxyCacheSnapshot(
    val subscriptionUrl: String,
    val savedAtMillis: Long,
    val configs: List<VlessConfig>
)

object ProxyCacheStore {
    private const val PREFS = "netbox_proxy_cache"
    private const val KEY_URL = "subscription_url"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_CONFIGS = "tested_configs"

    fun save(context: Context, subscriptionUrl: String, configs: List<VlessConfig>) {
        if (subscriptionUrl.isBlank() || configs.isEmpty()) return
        val unique = configs.distinctBy { it.rawUri }
        val array = JSONArray()
        unique.forEach { config ->
            array.put(JSONObject().apply {
                put("uri", config.rawUri)
                put("ping", config.pingMs)
            })
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, subscriptionUrl)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putString(KEY_CONFIGS, array.toString())
            .apply()
    }

    fun load(context: Context): ProxyCacheSnapshot? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return null
        return try {
            val array = JSONArray(raw)
            val configs = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri").takeIf { it.startsWith("vless://", true) } ?: continue
                    val config = VlessConfig.parse(uri) ?: continue
                    config.pingMs = item.optLong("ping", -1L)
                    add(config)
                }
            }.distinctBy { it.rawUri }
            if (configs.isEmpty()) null else ProxyCacheSnapshot(url, savedAt, configs)
        } catch (_: Exception) {
            null
        }
    }

    fun loadFresh(
        context: Context,
        subscriptionUrl: String,
        refreshIntervalMinutes: Int
    ): ProxyCacheSnapshot? {
        val snapshot = load(context) ?: return null
        if (snapshot.subscriptionUrl != subscriptionUrl) return null
        val maxAge = refreshIntervalMinutes.coerceAtLeast(1) * 60_000L
        if (System.currentTimeMillis() - snapshot.savedAtMillis >= maxAge) return null
        val healthy = snapshot.configs.filter { it.pingMs >= 0L }.sortedBy { it.pingMs }
        return snapshot.copy(configs = healthy).takeIf { it.configs.isNotEmpty() }
    }
}
