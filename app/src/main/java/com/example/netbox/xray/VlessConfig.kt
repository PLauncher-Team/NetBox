package com.example.netbox.xray

import java.net.URLDecoder
import java.net.URI

data class VlessConfig(
    val rawUri: String,
    val uuid: String,
    val address: String,
    val port: Int,
    val params: Map<String, String>,
    val remark: String,
    var pingMs: Long = -1L
) {
    companion object {
        fun parse(uriString: String): VlessConfig? {
            val trimmed = uriString.trim()
            if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

            return try {
                val withoutProtocol = trimmed.substring(8)
                val hashIndex = withoutProtocol.indexOf('#')
                val remark = if (hashIndex != -1) {
                    val rawRemark = withoutProtocol.substring(hashIndex + 1)
                    try { URLDecoder.decode(rawRemark, "UTF-8") } catch (e: Exception) { rawRemark }
                } else {
                    "VLESS Server"
                }

                val mainPart = if (hashIndex != -1) withoutProtocol.substring(0, hashIndex) else withoutProtocol
                val queryIndex = mainPart.indexOf('?')
                val paramsMap = mutableMapOf<String, String>()

                val authorityPart = if (queryIndex != -1) {
                    val queryString = mainPart.substring(queryIndex + 1)
                    queryString.split('&').forEach { param ->
                        val keyValue = param.split('=', limit = 2)
                        if (keyValue.size == 2) {
                            paramsMap[keyValue[0].lowercase()] = URLDecoder.decode(keyValue[1], "UTF-8")
                        }
                    }
                    mainPart.substring(0, queryIndex)
                } else {
                    mainPart
                }

                val atIndex = authorityPart.indexOf('@')
                if (atIndex == -1) return null

                val uuid = authorityPart.substring(0, atIndex)
                val hostPort = authorityPart.substring(atIndex + 1)

                val colonIndex = hostPort.lastIndexOf(':')
                if (colonIndex == -1) return null

                val address = hostPort.substring(0, colonIndex)
                val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: 443

                VlessConfig(
                    rawUri = trimmed,
                    uuid = uuid,
                    address = address,
                    port = port,
                    params = paramsMap,
                    remark = remark
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
