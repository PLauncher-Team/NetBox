package com.example.netbox.xray

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object VlessClient {

    private const val TAG = "NetBoxVless"

    fun connect(
        vlessConfig: VlessConfig,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 10000
    ): Socket {
        Log.d(TAG, "Connecting to VLESS server: ${vlessConfig.address}:${vlessConfig.port} for target $targetHost:$targetPort")

        val rawSocket = Socket().apply { XrayVpnService.instance?.protect(this) }
        rawSocket.connect(InetSocketAddress(vlessConfig.address, vlessConfig.port), connectTimeoutMs)
        Log.d(TAG, "TCP connected to proxy node ${vlessConfig.address}:${vlessConfig.port}")

        val security = vlessConfig.params["security"]?.lowercase() ?: "none"
        val sniHost = vlessConfig.params["sni"] ?: vlessConfig.address

        val socket = if (security == "tls" || security == "reality") {
            try {
                Log.d(TAG, "REALITY/TLS handshake with SNI: $sniHost")
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(rawSocket, vlessConfig.address, vlessConfig.port, true) as SSLSocket

                val params = SSLParameters().apply {
                    serverNames = if (sniHost.isNotBlank()) listOf(SNIHostName(sniHost)) else emptyList()
                }
                sslSocket.sslParameters = params
                sslSocket.startHandshake()
                Log.d(TAG, "TLS handshake SUCCESS")
                sslSocket
            } catch (e: Exception) {
                Log.e(TAG, "TLS handshake failed: ${e.message}. Using raw socket (REALITY may not work yet)", e)
                rawSocket
            }
        } else {
            rawSocket
        }

        // === VLESS handshake (header) ===
        try {
            val header = buildVlessHeader(vlessConfig.uuid, targetHost, targetPort)
            val out = socket.getOutputStream()
            out.write(header)
            out.flush()

            val input = socket.getInputStream()
            val version = input.read()
            val addonsLen = input.read()
            if (addonsLen > 0) {
                val buffer = ByteArray(addonsLen)
                var read = 0
                while (read < addonsLen) read += input.read(buffer, read, addonsLen - read)
            }
            Log.d(TAG, "VLESS response received: version=$version, addonsLen=$addonsLen")
        } catch (e: Exception) {
            Log.e(TAG, "VLESS header failed: ${e.message}", e)
            try { socket.close() } catch (_: Exception) {}
            throw e
        }

        return socket
    }

    private fun buildVlessHeader(uuidStr: String, targetHost: String, targetPort: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // VLESS version 0
        out.write(uuidToBytes(uuidStr)) // 16 bytes UUID
        out.write(0) // Addons length 0
        out.write(1) // Command: 1 = TCP

        // Port (2 bytes BigEndian)
        out.write((targetPort shr 8) and 0xFF)
        out.write(targetPort and 0xFF)

        // Address type & Address
        val ipv4 = parseIpv4(targetHost)
        if (ipv4 != null) {
            out.write(1) // IPv4
            out.write(ipv4)
        } else {
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            out.write(2) // Domain
            out.write(hostBytes.size and 0xFF)
            out.write(hostBytes)
        }

        return out.toByteArray()
    }

    private fun uuidToBytes(uuidStr: String): ByteArray {
        val clean = uuidStr.replace("-", "").trim()
        val bytes = ByteArray(16)
        if (clean.length < 32) return bytes
        for (i in 0 until 16) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val b = parts[i].toIntOrNull() ?: return null
            if (b !in 0..255) return null
            bytes[i] = b.toByte()
        }
        return bytes
    }
}
