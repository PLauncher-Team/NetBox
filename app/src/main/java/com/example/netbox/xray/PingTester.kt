package com.example.netbox.xray

import android.content.Context
import java.io.File
import libXray.LibXray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

object PingTester {
    private const val TAG = "NetBoxLog-Ping"
    private const val DEFAULT_PING_URL = "https://www.gstatic.com/generate_204"

    suspend fun testActiveProxy(
        pingUrl: String,
        timeoutMs: Int,
        method: String = "HEAD",
        socksPort: Int = 10808,
        socksUsername: String? = null,
        socksPassword: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceAtLeast(1000)
        val httpMethod = if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "GET"

        try {
            val uri = URI(pingUrl.ifBlank { DEFAULT_PING_URL })
            val scheme = uri.scheme?.lowercase() ?: "https"
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("Only HTTP and HTTPS probe URLs are supported")
            }

            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Probe URL has no valid host")
            val port = when {
                uri.port > 0 -> uri.port
                scheme == "http" -> 80
                else -> 443
            }
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            if (hostBytes.isEmpty() || hostBytes.size > 255) {
                throw IllegalArgumentException("Invalid SOCKS5 target hostname")
            }

            val requestPath = (uri.rawPath?.takeIf { it.isNotBlank() } ?: "/") +
                (uri.rawQuery?.let { "?" + it } ?: "")
            val hostHeader = if ((scheme == "https" && port != 443) ||
                (scheme == "http" && port != 80)
            ) {
                host + ":" + port
            } else {
                host
            }

            val elapsed = measureTimeMillis {
                Socket().use { proxySocket ->
                    proxySocket.connect(InetSocketAddress("127.0.0.1", socksPort), effectiveTimeout)
                    proxySocket.soTimeout = effectiveTimeout
                    val socksInput = proxySocket.getInputStream()
                    val socksOutput = proxySocket.getOutputStream()

                    val hasCredentials = !socksUsername.isNullOrBlank() && !socksPassword.isNullOrBlank()
                    socksOutput.write(
                        if (hasCredentials) byteArrayOf(0x05, 0x01, 0x02)
                        else byteArrayOf(0x05, 0x01, 0x00)
                    )
                    socksOutput.flush()
                    val greeting = readExactly(socksInput, 2)
                    if ((greeting[0].toInt() and 0xff) != 0x05) {
                        throw IllegalStateException("Invalid SOCKS5 greeting")
                    }
                    val authMethod = greeting[1].toInt() and 0xff
                    if (hasCredentials && authMethod == 0x02) {
                        val usernameBytes = socksUsername!!.toByteArray(Charsets.UTF_8)
                        val passwordBytes = socksPassword!!.toByteArray(Charsets.UTF_8)
                        require(usernameBytes.size in 1..255 && passwordBytes.size in 1..255) {
                            "SOCKS5 credentials have invalid length"
                        }
                        val authRequest = ByteArray(3 + usernameBytes.size + passwordBytes.size)
                        authRequest[0] = 0x01
                        authRequest[1] = usernameBytes.size.toByte()
                        System.arraycopy(usernameBytes, 0, authRequest, 2, usernameBytes.size)
                        authRequest[2 + usernameBytes.size] = passwordBytes.size.toByte()
                        System.arraycopy(passwordBytes, 0, authRequest, 3 + usernameBytes.size, passwordBytes.size)
                        socksOutput.write(authRequest)
                        socksOutput.flush()
                        val authResponse = readExactly(socksInput, 2)
                        if ((authResponse[0].toInt() and 0xff) != 0x01 ||
                            (authResponse[1].toInt() and 0xff) != 0x00
                        ) {
                            throw IllegalStateException("SOCKS5 username/password authentication failed")
                        }
                    } else if (authMethod != 0x00 || hasCredentials) {
                        throw IllegalStateException("SOCKS5 server selected unsupported authentication method")
                    }

                    val request = ByteArray(7 + hostBytes.size)
                    request[0] = 0x05
                    request[1] = 0x01
                    request[2] = 0x00
                    request[3] = 0x03
                    request[4] = hostBytes.size.toByte()
                    System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
                    request[5 + hostBytes.size] = (port shr 8).toByte()
                    request[6 + hostBytes.size] = port.toByte()
                    socksOutput.write(request)
                    socksOutput.flush()

                    val socksReply = readExactly(socksInput, 4)
                    val replyCode = socksReply[1].toInt() and 0xff
                    if (replyCode != 0) {
                        throw IllegalStateException("SOCKS5 CONNECT failed with reply " + replyCode)
                    }
                    when (socksReply[3].toInt() and 0xff) {
                        0x01 -> readExactly(socksInput, 6)
                        0x03 -> {
                            val length = socksInput.read()
                            if (length < 0) throw IllegalStateException("Incomplete SOCKS5 reply")
                            readExactly(socksInput, length + 2)
                        }
                        0x04 -> readExactly(socksInput, 18)
                        else -> throw IllegalStateException("Unknown SOCKS5 address type")
                    }

                    val connection: Socket = if (scheme == "https") {
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        (factory.createSocket(proxySocket, host, port, true) as SSLSocket).apply {
                            soTimeout = effectiveTimeout
                            sslParameters = sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                            startHandshake()
                        }
                    } else {
                        proxySocket
                    }

                    try {
                        connection.soTimeout = effectiveTimeout
                        val output = connection.getOutputStream()
                        val input = connection.getInputStream().buffered()
                        val requestText = httpMethod + " " + requestPath + " HTTP/1.1\r\n" +
                            "Host: " + hostHeader + "\r\n" +
                            "User-Agent: NetBox-Ping/1.0\r\n" +
                            "Accept: */*\r\n" +
                            "Accept-Encoding: identity\r\n" +
                            "Connection: close\r\n\r\n"
                        output.write(requestText.toByteArray(Charsets.US_ASCII))
                        output.flush()

                        var statusCode: Int
                        var responseHeaders: Map<String, String>
                        do {
                            val statusLine = readHttpLine(input)
                                ?: throw IllegalStateException("No HTTP status line received")
                            val statusParts = statusLine.trim().split(Regex("\\s+"))
                            statusCode = statusParts.getOrNull(1)?.toIntOrNull()
                                ?: throw IllegalStateException("Invalid HTTP status line")

                            val headers = linkedMapOf<String, String>()
                            var headerBytes = statusLine.length
                            while (true) {
                                val line = readHttpLine(input)
                                    ?: throw IllegalStateException("Incomplete HTTP response headers")
                                headerBytes += line.length + 2
                                if (headerBytes > 65536) {
                                    throw IllegalStateException("HTTP response headers are too large")
                                }
                                if (line.isEmpty()) break

                                val separator = line.indexOf(':')
                                if (separator <= 0) continue
                                val name = line.substring(0, separator).trim().lowercase()
                                val value = line.substring(separator + 1).trim()
                                headers[name] = if (headers.containsKey(name)) {
                                    headers.getValue(name) + ", " + value
                                } else {
                                    value
                                }
                            }
                            responseHeaders = headers
                        } while (statusCode in 100..199 && statusCode != 101)

                        if (httpMethod == "GET" && statusCode !in 100..199 &&
                            statusCode != 204 && statusCode != 304
                        ) {
                            consumeHttpResponseBody(input, responseHeaders)
                        }
                    } finally {
                        if (connection !== proxySocket) {
                            connection.close()
                        }
                    }
                }
            }
            elapsed
        } catch (e: Throwable) {
            AppLogger.d(TAG, "Active proxy HTTP " + httpMethod + " check failed: " + e.message)
            -1L
        }
    }

    suspend fun testActiveProxyWithRetry(
        pingUrl: String,
        timeoutMs: Int,
        method: String = "HEAD",
        socksPort: Int = 10808,
        attempts: Int = 2,
        socksUsername: String? = null,
        socksPassword: String? = null
    ): Long {
        val maxAttempts = attempts.coerceIn(1, 3)
        for (attempt in 1..maxAttempts) {
            val result = testActiveProxy(
                pingUrl, timeoutMs, method, socksPort, socksUsername, socksPassword
            )
            if (result >= 0L) return result
            if (attempt < maxAttempts) {
                AppLogger.d(TAG, "Probe failed; retrying (${attempt + 1}/$maxAttempts)")
                delay(150L)
            }
        }
        return -1L
    }

    private fun consumeHttpResponseBody(
        input: java.io.InputStream,
        headers: Map<String, String>
    ) {
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        if (transferEncoding.split(',').any { it.trim().equals("chunked", ignoreCase = true) }) {
            while (true) {
                val chunkLine = readHttpLine(input)
                    ?: throw IllegalStateException("Incomplete chunked HTTP response")
                val chunkSize = chunkLine.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw IllegalStateException("Invalid HTTP chunk size")
                if (chunkSize == 0L) {
                    while (true) {
                        val trailer = readHttpLine(input)
                            ?: throw IllegalStateException("Incomplete HTTP trailers")
                        if (trailer.isEmpty()) return
                    }
                }
                consumeExactly(input, chunkSize)
                val delimiter = readExactly(input, 2)
                if (delimiter[0] != 13.toByte() || delimiter[1] != 10.toByte()) {
                    throw IllegalStateException("Invalid HTTP chunk delimiter")
                }
            }
        }

        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength != null) {
            if (contentLength < 0L) throw IllegalStateException("Invalid HTTP content length")
            consumeExactly(input, contentLength)
            return
        }

        val buffer = ByteArray(8192)
        while (input.read(buffer) >= 0) {

        }
    }

    private fun consumeExactly(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("HTTP response body ended prematurely")
            remaining -= read.toLong()
        }
    }

    private fun readHttpLine(input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.size() == 0) return null
                return String(bytes.toByteArray(), Charsets.ISO_8859_1)
            }
            if (next == '\n'.code) {
                val line = bytes.toByteArray()
                val length = if (line.isNotEmpty() && line.last() == '\r'.code.toByte()) {
                    line.size - 1
                } else {
                    line.size
                }
                return String(line, 0, length, Charsets.ISO_8859_1)
            }
            bytes.write(next)
            if (bytes.size() > 16384) {
                throw IllegalStateException("HTTP response line is too long")
            }
        }
    }

    private fun readExactly(input: java.io.InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw IllegalStateException("Unexpected end of SOCKS5/HTTP response")
            offset += read
        }
        return bytes
    }

    suspend fun testAllThroughTemporaryXray(
        context: Context,
        configs: List<VlessConfig>,
        pingUrl: String,
        parallelCount: Int,
        timeoutMs: Int,
        method: String,
        attempts: Int = 2,
        onProgress: (completed: Int, total: Int, bestConfig: VlessConfig?) -> Unit
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext emptyList()

        val configFile = File(context.applicationContext.filesDir, "xray_candidate_probe.json")
        var started = false
        try {
            val credentials = LocalProxyAuth.credentials(context)
            val jsonConfig = XrayConfigBuilder.buildJsonConfig(
                config = configs.first(),
                proxyPool = configs,
                probeUrl = pingUrl,
                probeTimeoutSeconds = timeoutMs.coerceAtLeast(1000) / 1000,
                probeMethod = method,
                includeCandidateProbeInbounds = true,
                socksUsername = credentials.username,
                socksPassword = credentials.password
            )
            configFile.writeText(jsonConfig)

            val request = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "runXray")
                put("payload", JSONObject().put("configPath", configFile.absolutePath))
            }.toString()
            val response = JSONObject(LibXray.invoke(request))
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException(
                    response.optString("error").ifBlank { "Xray candidate-probe core failed to start" }
                )
            }
            started = true

            val portMap = configs.distinctBy { it.rawUri }.mapIndexed { index, config ->
                config.rawUri to (XrayConfigBuilder.CANDIDATE_PROBE_PORT_BASE + index)
            }.toMap()

            testCandidatesOnPorts(
                configs = configs,
                portByRawUri = portMap,
                pingUrl = pingUrl,
                timeoutMs = timeoutMs,
                method = method,
                parallelCount = parallelCount,
                attempts = attempts,
                socksUsername = credentials.username,
                socksPassword = credentials.password,
                onProgress = onProgress
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Tunneled candidate scan failed: ${e.message}", e)
            configs.forEach { it.pingMs = -1L }
            configs
        } finally {
            if (started) {
                try {
                    val stopRequest = JSONObject().apply {
                        put("apiVersion", 1)
                        put("method", "stopXray")
                        put("payload", JSONObject())
                    }.toString()
                    LibXray.invoke(stopRequest)
                } catch (e: Throwable) {
                    AppLogger.w(TAG, "Could not stop temporary candidate-probe Xray: ${e.message}")
                }
            }
        }
    }

    suspend fun testCandidatesOnPorts(
        configs: List<VlessConfig>,
        portByRawUri: Map<String, Int>,
        pingUrl: String,
        timeoutMs: Int,
        method: String,
        parallelCount: Int,
        attempts: Int = 2,
        stopAfterFirstHealthy: Boolean = false,
        socksUsername: String? = null,
        socksPassword: String? = null,
        onProgress: (completed: Int, total: Int, bestConfig: VlessConfig?) -> Unit
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext emptyList()
        val effectiveTimeout = timeoutMs.coerceAtLeast(1000)
        val httpMethod = if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "GET"

        waitForProbePorts(portByRawUri.values.toList(), 4000)
        var completedCount = 0
        var bestConfigSoFar: VlessConfig? = null
        val tested = mutableListOf<VlessConfig>()

        if (stopAfterFirstHealthy) {
            for (config in configs) {
                coroutineContext.ensureActive()
                val port = portByRawUri[config.rawUri]
                config.pingMs = if (port == null) {
                    -1L
                } else {
                    testActiveProxyWithRetry(
                        pingUrl, effectiveTimeout, httpMethod, port,
                        attempts = attempts,
                        socksUsername = socksUsername,
                        socksPassword = socksPassword
                    )
                }
                tested += config
                completedCount++
                if (config.pingMs >= 0L &&
                    (bestConfigSoFar == null || config.pingMs < (bestConfigSoFar?.pingMs ?: Long.MAX_VALUE))
                ) {
                    bestConfigSoFar = config
                }
                onProgress(completedCount, configs.size, bestConfigSoFar)
                AppLogger.d(
                    TAG,
                    "Tunneled probe for [${config.remark}] via SOCKS ${port ?: "missing"}: ${config.pingMs} ms"
                )

                if (config.pingMs >= 0L) break
            }
            return@withContext tested
        }

        val semaphore = Semaphore(parallelCount.coerceAtLeast(1))
        val deferreds = configs.map { config ->
            async {
                semaphore.withPermit {
                    coroutineContext.ensureActive()
                    val port = portByRawUri[config.rawUri]
                    config.pingMs = if (port == null) {
                        -1L
                    } else {
                        testActiveProxyWithRetry(
                            pingUrl, effectiveTimeout, httpMethod, port,
                            attempts = attempts,
                            socksUsername = socksUsername,
                            socksPassword = socksPassword
                        )
                    }

                    synchronized(this@PingTester) {
                        completedCount++
                        if (config.pingMs >= 0L &&
                            (bestConfigSoFar == null || config.pingMs < (bestConfigSoFar?.pingMs ?: Long.MAX_VALUE))
                        ) {
                            bestConfigSoFar = config
                        }
                        onProgress(completedCount, configs.size, bestConfigSoFar)
                    }
                    AppLogger.d(
                        TAG,
                        "Tunneled probe for [${config.remark}] via SOCKS ${port ?: "missing"}: ${config.pingMs} ms"
                    )
                    config
                }
            }
        }
        deferreds.awaitAll().sortedWith(
            compareBy<VlessConfig> { if (it.pingMs >= 0) 0 else 1 }
                .thenBy { if (it.pingMs >= 0) it.pingMs else Long.MAX_VALUE }
        )
    }

    private suspend fun waitForProbePorts(ports: List<Int>, waitTimeoutMs: Long) {
        val deadline = System.currentTimeMillis() + waitTimeoutMs
        val pending = ports.toMutableSet()
        while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val port = iterator.next()
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 100)
                    }
                    iterator.remove()
                } catch (_: Throwable) {

                }
            }
            if (pending.isNotEmpty()) kotlinx.coroutines.delay(50)
        }
        if (pending.isNotEmpty()) {
            AppLogger.w(TAG, "Some candidate SOCKS listeners did not become ready: ${pending.joinToString()}")
        }
    }

}
