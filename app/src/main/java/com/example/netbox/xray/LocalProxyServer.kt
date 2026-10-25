package com.example.netbox.xray

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalProxyServer(
    private val httpPort: Int = 10809,
    private val xraySocksPort: Int = 10808
) {
    private val TAG = "NetBoxLog-Proxy"

    @Volatile
    private var isRunning = false
    private var httpServerSocket: ServerSocket? = null

    fun start() {
        isRunning = true
        thread(name = "NetBox-HttpProxy") {
            try {
                httpServerSocket = ServerSocket(httpPort, 50, InetAddress.getByName("127.0.0.1"))
                AppLogger.d(TAG, "LocalProxyServer listening on 127.0.0.1:$httpPort")
                while (isRunning) {
                    val clientSocket = try {
                        httpServerSocket?.accept()
                    } catch (_: Exception) {
                        break
                    } ?: break

                    thread {
                        handleHttpClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "LocalProxyServer accept loop error: ${e.message}", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try { httpServerSocket?.close() } catch (_: Exception) {}
        httpServerSocket = null
        AppLogger.d(TAG, "LocalProxyServer stopped")
    }

    private fun handleHttpClient(clientSocket: Socket) {
        clientSocket.use { client ->
            try {
                client.soTimeout = 30000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val uri = parts[1]

                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }

                val targetHost: String
                val targetPort: Int

                if (method == "CONNECT") {
                    val hostPort = uri.split(":")
                    targetHost = hostPort[0]
                    targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                    connectToXraySocks(targetHost, targetPort).use { xraySocket ->
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                        pipeSockets(client, xraySocket)
                    }
                } else {
                    var cleanUri = uri
                    if (cleanUri.startsWith("http://", ignoreCase = true)) {
                        cleanUri = cleanUri.substring(7)
                    }
                    val slashIdx = cleanUri.indexOf('/')
                    val hostPart = if (slashIdx != -1) cleanUri.substring(0, slashIdx) else cleanUri
                    val hostPort = hostPart.split(":")
                    targetHost = hostPort[0]
                    targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 80

                    connectToXraySocks(targetHost, targetPort).use { xraySocket ->
                        val pathPart = if (slashIdx != -1) cleanUri.substring(slashIdx) else "/"
                        val modifiedRequestLine = "$method $pathPart HTTP/1.1"
                        xraySocket.getOutputStream().write("$modifiedRequestLine\r\n\r\n".toByteArray(Charsets.UTF_8))
                        xraySocket.getOutputStream().flush()
                        pipeSockets(client, xraySocket)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "HTTP client handling error: ${e.message}", e)
            }
        }
    }

    private fun connectToXraySocks(targetHost: String, targetPort: Int): Socket {
        val socket = Socket("127.0.0.1", xraySocksPort)
        socket.soTimeout = 30000
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        // SOCKS5 auth: noauth
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()

        val ver = inp.read()
        val method = inp.read()
        if (ver != 5 || method != 0) {
            socket.close()
            throw IllegalStateException("SOCKS5 auth failed")
        }

        // SOCKS5 connect request
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05 // SOCKS5
        req[1] = 0x01 // CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // Domain type
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        val portOffset = 5 + hostBytes.size
        req[portOffset] = ((targetPort shr 8) and 0xFF).toByte()
        req[portOffset + 1] = (targetPort and 0xFF).toByte()

        out.write(req)
        out.flush()

        // Read SOCKS5 reply (10 bytes)
        val reply = ByteArray(10)
        var read = 0
        while (read < 10) {
            val r = inp.read(reply, read, 10 - read)
            if (r == -1) break
            read += r
        }

        if (reply[1] != 0x00.toByte()) {
            socket.close()
            throw IllegalStateException("SOCKS5 connect failed: reply code ${reply[1]}")
        }

        return socket
    }

    private fun pipeSockets(s1: Socket, s2: Socket) {
        val t1 = thread(name = "NetBox-Pipe1") {
            try {
                s1.getInputStream().use { in1 ->
                    s2.getOutputStream().use { out2 ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val r = in1.read(buf)
                            if (r <= 0) break
                            out2.write(buf, 0, r)
                            out2.flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val t2 = thread(name = "NetBox-Pipe2") {
            try {
                s2.getInputStream().use { in2 ->
                    s1.getOutputStream().use { out1 ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val r = in2.read(buf)
                            if (r <= 0) break
                            out1.write(buf, 0, r)
                            out1.flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        t1.join()
        t2.join()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            val c = b.toChar()
            if (c == '\n') break
            if (c != '\r') sb.append(c)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
