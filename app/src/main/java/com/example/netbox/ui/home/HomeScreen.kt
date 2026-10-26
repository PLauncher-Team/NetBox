package com.example.netbox.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netbox.xray.CountryInfo
import com.example.netbox.xray.VlessConfig
import com.example.netbox.xray.VpnState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vpnState: VpnState,
    connectedServer: VlessConfig?,
    activeProxyPool: List<VlessConfig>,
    statusMessage: String,
    isProcessing: Boolean,
    forceCurrentServerRecovery: Boolean,
    pingUrl: String,
    availableCountries: List<CountryInfo>,
    selectedCountryCode: String,
    countryServerCounts: Map<String, Int>,
    countrySelectionEnabled: Boolean,
    onCountrySelected: (String) -> Unit,
    onToggleConnection: () -> Unit,
    onForceRemoveCurrentServer: () -> Unit
) {
    var countryMenuExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val selectedCountry = availableCountries.firstOrNull { it.code == selectedCountryCode }
    val selectedCountryLabel = when {
        selectedCountryCode == CountryInfo.AUTO_CODE -> "🌐  Автоматически"
        selectedCountry != null -> "${selectedCountry.flag}  ${selectedCountry.russianName}"
        else -> "Страна не найдена в подписке"
    }
    val isConnected = vpnState == VpnState.CONNECTED
    val isActiveOrProcessing = isConnected || isProcessing || vpnState == VpnState.CONNECTING
    val displayServer = connectedServer ?: activeProxyPool.firstOrNull()
    val selectedTransport = displayServer?.params?.get("type")
        ?.takeIf { it.isNotBlank() }
        ?: displayServer?.params?.get("network")?.takeIf { it.isNotBlank() }
        ?: "tcp"
    val statusLabel = when {
        isProcessing -> "Подготовка подключения"
        vpnState == VpnState.CONNECTING -> "Подключение к прокси"
        isConnected -> "Прокси активен"
        else -> "Прокси отключён"
    }
    val statusColor = when {
        isConnected -> Color(0xFF2E9D68)
        isActiveOrProcessing -> Color(0xFFE5A23B)
        else -> MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isConnected) {
                            "Туннель активен · пул прокси: " + activeProxyPool.size
                        } else {
                            statusMessage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = when {
                        isConnected -> "ONLINE"
                        isActiveOrProcessing -> "WAIT"
                        else -> "OFFLINE"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor
                )
            }
        }

        Button(
            onClick = onToggleConnection,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActiveOrProcessing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (isActiveOrProcessing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.size(10.dp))
            }
            Text(
                text = when {
                    isProcessing -> "ПОДГОТОВКА…"
                    isActiveOrProcessing -> "ОСТАНОВИТЬ ПРОКСИ"
                    else -> "ПОДКЛЮЧИТЬСЯ"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "СТРАНА ПОДКЛЮЧЕНИЯ",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { countryMenuExpanded = true },
                    enabled = countrySelectionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = selectedCountryLabel,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("▾", style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(
                    expanded = countryMenuExpanded,
                    onDismissRequest = { countryMenuExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.92f)
                ) {
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("🌐  Автоматически")
                                Text(
                                    text = "Любая страна · лучший доступный сервер",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            countryMenuExpanded = false
                            onCountrySelected(CountryInfo.AUTO_CODE)
                        }
                    )
                    availableCountries.forEach { country ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(country.flag, style = MaterialTheme.typography.titleLarge)
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(country.russianName)
                                        Text(
                                            text = country.englishName + " · " +
                                                (countryServerCounts[country.code] ?: 0) + " серверов",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                countryMenuExpanded = false
                                onCountrySelected(country.code)
                            }
                        )
                    }
                }
            }
            if (availableCountries.isEmpty()) {
                Text(
                    text = "Страны появятся после загрузки подписки; распознаются по названиям серверов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("ТЕКУЩИЙ СЕРВЕР", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayServer?.remark ?: "Сервер не выбран",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = displayServer?.let { it.address + ":" + it.port } ?: "Подключитесь, чтобы увидеть сервер",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isConnected && connectedServer != null) {
                        IconButton(
                            onClick = onForceRemoveCurrentServer,
                            enabled = !forceCurrentServerRecovery,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Text(
                                text = "−",
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (forceCurrentServerRecovery) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }
        }

        Text("ПАРАМЕТРЫ СОЕДИНЕНИЯ", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                Triple("Протокол", "VLESS", "Тип прокси"),
                Triple("Транспорт", selectedTransport.uppercase(), "Сетевой канал"),
                Triple("Пинг", displayServer?.pingMs?.takeIf { it >= 0 }?.let { it.toString() + " ms" } ?: "—", "Задержка сервера"),
                Triple("Пул прокси", activeProxyPool.size.toString(), "Прокси в пуле"),
            ).forEach { (label, value, description) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(0.48f),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }

        Text(
            text = "Проверка: " + pingUrl.ifBlank { "URL не задан" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}