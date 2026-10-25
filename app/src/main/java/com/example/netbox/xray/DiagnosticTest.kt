package com.example.netbox.xray

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object DiagnosticTest {
    suspend fun runDiagnostics(
        context: Context,
        subscriptionUrl: String,
        pingUrl: String
    ): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        report.appendLine("=== NetBox Диагностика ===")
        report.appendLine("Время: ${java.util.Date()}")
        report.appendLine("Android SDK: ${android.os.Build.VERSION.SDK_INT}")
        report.appendLine()

        report.appendLine("[1/4] Проверка интернет-соединения...")
        try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 53), 3000)
            }
            report.appendLine("-> ОК (Доступ к 1.1.1.1:53 занял ${System.currentTimeMillis() - start} ms)")
        } catch (e: Exception) {
            report.appendLine("-> ОШИБКА: Нет доступа к сети (1.1.1.1:53): ${e.message}")
        }
        report.appendLine()

        report.appendLine("[2/4] Проверка загрузки подписки...")
        if (subscriptionUrl.isBlank()) {
            report.appendLine("-> ОШИБКА: URL подписки не указан в настройках")
        } else {
            val configs = SubscriptionFetcher.fetchVlessConfigs(context, subscriptionUrl).configs
            if (configs.isEmpty()) {
                report.appendLine("-> ОШИБКА: Подписка не загружена или не содержит VLESS ссылок")
            } else {
                report.appendLine("-> ОК (Найдено VLESS серверов: ${configs.size})")
                configs.take(3).forEachIndexed { i, c ->
                    report.appendLine("   #${i + 1}: ${c.remark} (${c.address}:${c.port})")
                }

                report.appendLine()
                report.appendLine("[3/4] Проверка подключения к серверам...")
                val tested = XrayVpnService.testCandidatesThroughTunnel(
                    context = context,
                    configs = configs,
                    pingUrl = pingUrl,
                    parallelCount = 5,
                    timeoutMs = 2000,
                    method = "HEAD"
                ) { _, _, _ -> }
                val valid = tested.filter { it.pingMs > 0 }
                report.appendLine("-> Доступных серверов: ${valid.size} из ${configs.size}")
                tested.take(5).forEach { c ->
                    val status = if (c.pingMs > 0) "${c.pingMs} ms" else "Недоступен"
                    report.appendLine("   • ${c.remark}: $status")
                }

                report.appendLine()
                report.appendLine("[4/4] Тест VLESS рукопожатия...")
                val best = valid.firstOrNull()
                if (best == null) {
                    report.appendLine("-> ОШИБКА: Нет доступных серверов для рукопожатия")
                } else {
                    report.appendLine("   Лучший сервер: ${best.remark} (${best.address}:${best.port})")
                    try {
                        val vlessTime = measureTimeMillis {
                            val socket = VlessClient.connect(best, "1.1.1.1", 80, 5000)
                            socket.close()
                        }
                        report.appendLine("-> ОК: Рукопожатие VLESS прошло успешно за $vlessTime ms!")
                    } catch (e: Exception) {
                        report.appendLine("-> ОШИБКА VLESS рукопожатия: ${e.localizedMessage ?: e.javaClass.simpleName}")
                    }
                }
            }
        }

        report.appendLine()
        report.appendLine("=== Конец отчета ===")
        report.toString()
    }
}
