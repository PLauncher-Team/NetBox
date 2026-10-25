package com.example.netbox.xray

enum class ProxyTransport(
    val key: String,
    val label: String,
    private val aliases: Set<String>
) {
    RAW("raw", "TCP / RAW", setOf("raw", "tcp")),
    XHTTP("xhttp", "XHTTP", setOf("xhttp", "splithttp")),
    WEBSOCKET("websocket", "WebSocket", setOf("websocket", "ws")),
    GRPC("grpc", "gRPC", setOf("grpc")),
    HTTP_UPGRADE("httpupgrade", "HTTPUpgrade", setOf("httpupgrade")),
    MKCP("mkcp", "mKCP", setOf("mkcp", "kcp"));

    companion object {
        val defaultAllowed: Set<String> = setOf(RAW.key, XHTTP.key)

        fun fromUriValue(value: String?): ProxyTransport? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return RAW
            return entries.firstOrNull { normalized in it.aliases }
        }

        fun allows(uriValue: String?, allowed: Set<String>): Boolean {
            val transport = fromUriValue(uriValue) ?: return false
            return transport.key in allowed
        }
    }
}
