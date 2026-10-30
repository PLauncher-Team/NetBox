package com.example.netbox

import android.content.Context
import com.example.netbox.xray.AppLogger
import com.example.netbox.xray.ProxyTransport
import org.json.JSONArray
import org.json.JSONObject

data class SubscriptionEntry(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("netbox_settings", Context.MODE_PRIVATE)

    var subscriptionUrl: String
        get() = prefs.getString("sub_url", "") ?: ""
        set(value) = prefs.edit().putString("sub_url", value).apply()

    var subscriptions: List<SubscriptionEntry>
        get() {
            val saved = prefs.getString("subscriptions_json", null)
            if (saved != null) {
                return try {
                    val array = JSONArray(saved)
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val id = item.optString("id").takeIf { it.isNotBlank() } ?: "subscription-$index"
                            val name = item.optString("name").ifBlank { "Подписка ${index + 1}" }
                            val url = item.optString("url").trim()
                            if (url.isNotBlank()) {
                                add(SubscriptionEntry(id, name, url, item.optBoolean("enabled", true)))
                            }
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            // One-time migration from the former single-URL setting.
            val legacyUrl = subscriptionUrl.trim()
            val migrated = if (legacyUrl.isNotBlank()) {
                listOf(SubscriptionEntry("legacy-${legacyUrl.hashCode()}", "Подписка 1", legacyUrl, true))
            } else {
                emptyList()
            }
            subscriptions = migrated
            return migrated
        }
        set(value) {
            val array = JSONArray()
            value.forEach { subscription ->
                array.put(JSONObject().apply {
                    put("id", subscription.id)
                    put("name", subscription.name)
                    put("url", subscription.url)
                    put("enabled", subscription.enabled)
                })
            }
            prefs.edit()
                .putString("subscriptions_json", array.toString())
                .remove("sub_url")
                .apply()
        }

    var selectedCountryCode: String
        get() = prefs.getString("selected_country_code", "auto") ?: "auto"
        set(value) = prefs.edit().putString("selected_country_code", value).apply()

    var pingUrl: String
        get() = prefs.getString("ping_url", null).let { saved ->
            val normalized = saved?.trim()?.trimEnd('/')
            if (normalized.isNullOrBlank() || normalized in setOf(
                    "https://web.telegram.org",
                    "https://one.one.one.one",
                    "https://youtube.com",
                    "https://www.youtube.com"
                )
            ) {
                "https://www.gstatic.com/generate_204"
            } else {
                saved ?: "https://www.gstatic.com/generate_204"
            }
        }
        set(value) = prefs.edit().putString("ping_url", value).apply()

    var parallelPings: Int
        get() = prefs.getInt("parallel_pings", 15)
        set(value) = prefs.edit().putInt("parallel_pings", value).apply()

    var pingTimeoutSeconds: Int
        get() = prefs.getInt("ping_timeout_sec", 3)
        set(value) = prefs.edit().putInt("ping_timeout_sec", value).apply()

    var pingAttempts: Int
        get() = prefs.getInt("ping_attempts", 2).coerceIn(1, 5)
        set(value) = prefs.edit().putInt("ping_attempts", value.coerceIn(1, 5)).apply()

    var observatoryIntervalSeconds: Int
        get() = prefs.getInt("observatory_interval_sec", 30).coerceIn(10, 86400)
        set(value) = prefs.edit().putInt("observatory_interval_sec", value.coerceIn(10, 86400)).apply()

    var subscriptionRefreshMinutes: Int
        get() = prefs.getInt("subscription_refresh_min", 60).coerceIn(1, 10080)
        set(value) = prefs.edit().putInt("subscription_refresh_min", value.coerceIn(1, 10080)).apply()

    var allowedTransports: Set<String>
        get() = prefs.getStringSet("allowed_transports", ProxyTransport.defaultAllowed.toMutableSet())
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: ProxyTransport.defaultAllowed
        set(value) {
            if (value.isNotEmpty()) {
                prefs.edit().putStringSet("allowed_transports", value.toSet()).apply()
            }
        }

    var isHttpPing: Boolean
        get() = prefs.getBoolean("is_http_ping", false)
        set(value) = prefs.edit().putBoolean("is_http_ping", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var colorScheme: String
        get() = prefs.getString("color_scheme", "DEFAULT") ?: "DEFAULT"
        set(value) = prefs.edit().putString("color_scheme", value).apply()

    var useSystemTheme: Boolean
        get() = prefs.getBoolean("use_system_theme", true)
        set(value) = prefs.edit().putBoolean("use_system_theme", value).apply()

    var customColorArgb: Int
        get() = prefs.getInt("custom_color", 0xFF6650a4.toInt())
        set(value) = prefs.edit().putInt("custom_color", value).apply()

    var enableDebugLogs: Boolean
        get() = prefs.getBoolean("enable_debug_logs", false)
        set(value) {
            prefs.edit().putBoolean("enable_debug_logs", value).apply()
            AppLogger.isEnabled = value
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.edit().putBoolean("notifications_enabled", value).apply()

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notification_permission_asked", false)
        set(value) = prefs.edit().putBoolean("notification_permission_asked", value).apply()
}