package com.example.netbox.xray

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object PingTester {
    private const val TAG = "NetBoxLog-Ping"

    suspend fun testAll(
        configs: List<VlessConfig>,
        pingUrl: String,
        parallelCount: Int,
        timeoutMs: Int = 3000,
        isHttpFull: Boolean,
        onProgress: (completed: Int, total: Int, bestConfig: VlessConfig?) -> Unit
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) {
            AppLogger.e(TAG, "testAll aborted: configs list is empty")
            return@withContext emptyList()
        }

        val effectiveTimeout = timeoutMs.coerceAtLeast(1000)
        AppLogger.d(TAG, "Starting ping test for ${configs.size} configs (parallelCount: $parallelCount, timeoutMs: ${effectiveTimeout}ms)...")

        val semaphore = Semaphore(parallelCount.coerceAtLeast(1))
        var completedCount = 0
        var bestConfigSoFar: VlessConfig? = null

        val deferreds = configs.map { config ->
            async {
                semaphore.withPermit {
                    coroutineContext.ensureActive()

                    val delayMs = pingTcp(config, effectiveTimeout)

                    coroutineContext.ensureActive()
                    config.pingMs = delayMs

                    synchronized(this@PingTester) {
                        completedCount++
                        if (delayMs >= 0 && (bestConfigSoFar == null || delayMs < (bestConfigSoFar?.pingMs ?: Long.MAX_VALUE))) {
                            bestConfigSoFar = config
                        }
                        onProgress(completedCount, configs.size, bestConfigSoFar)
                    }

                    AppLogger.d(TAG, "Ping result for [${config.remark}] (${config.address}:${config.port}): $delayMs ms")
                    config
                }
            }
        }

        val testedConfigs = deferreds.awaitAll()
        val sorted = testedConfigs.sortedWith(
            compareBy<VlessConfig> { if (it.pingMs >= 0) 0 else 1 }
                .thenBy { if (it.pingMs >= 0) it.pingMs else Long.MAX_VALUE }
        )

        AppLogger.d(TAG, "Ping testing completed. Total tested: ${sorted.size}, Reachable: ${sorted.count { it.pingMs >= 0 }}")
        val best = sorted.firstOrNull { it.pingMs >= 0 }
        if (best != null) {
            AppLogger.d(TAG, "Best server selected: [${best.remark}] (${best.address}:${best.port}) with ${best.pingMs} ms")
        } else {
            AppLogger.e(TAG, "All servers returned -1 ping! No reachable server found.")
        }

        sorted
    }

    private fun pingTcp(config: VlessConfig, timeoutMs: Int): Long {
        return try {
            val time = measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(config.address, config.port), timeoutMs)
                }
            }
            time
        } catch (_: Throwable) {
            -1L
        }
    }
}
