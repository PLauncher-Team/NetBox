package com.example.netbox.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.netbox.SettingsStore
import com.example.netbox.xray.ProxyTransport

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionSettingsTab(
    settingsStore: SettingsStore,
    pingUrl: String,
    onPingUrlChange: (String) -> Unit,
    parallelPingsText: String,
    onParallelPingsChange: (String) -> Unit,
    pingTimeoutText: String,
    onPingTimeoutChange: (String) -> Unit,
    pingAttemptsText: String,
    onPingAttemptsChange: (String) -> Unit,
    observatoryIntervalText: String,
    onObservatoryIntervalChange: (String) -> Unit,
    subscriptionRefreshText: String,
    onSubscriptionRefreshChange: (String) -> Unit,
    isHttpPing: Boolean,
    onHttpPingChange: (Boolean) -> Unit,
    allowedTransports: Set<String>,
    onAllowedTransportsChange: (Set<String>) -> Unit
) {
    Text(
        text = "Url пинга",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    OutlinedTextField(
        value = pingUrl,
        onValueChange = {
            onPingUrlChange(it)
            settingsStore.pingUrl = it
        },
        placeholder = {
            Text(
                text = "https://www.gstatic.com/generate_204",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Количество параллельных пингов",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    OutlinedTextField(
        value = parallelPingsText,
        onValueChange = { input ->
            if (input.all { it.isDigit() }) {
                onParallelPingsChange(input)
                input.toIntOrNull()?.let { settingsStore.parallelPings = it }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Таймаут пинга (сек)",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    OutlinedTextField(
        value = pingTimeoutText,
        onValueChange = { input ->
            if (input.all { it.isDigit() }) {
                onPingTimeoutChange(input)
                input.toIntOrNull()?.let { settingsStore.pingTimeoutSeconds = it }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Количество попыток проверки (1–5)",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )
    OutlinedTextField(
        value = pingAttemptsText,
        onValueChange = { input ->
            if (input.all { it.isDigit() }) {
                onPingAttemptsChange(input)
                input.toIntOrNull()?.takeIf { it in 1..5 }?.let { settingsStore.pingAttempts = it }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Интервал проверки текущего прокси (сек, минимум 10)",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    OutlinedTextField(
        value = observatoryIntervalText,
        onValueChange = { input ->
            if (input.all { it.isDigit() }) {
                onObservatoryIntervalChange(input)
                input.toIntOrNull()?.takeIf { it in 10..86400 }?.let {
                    settingsStore.observatoryIntervalSeconds = it
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Интервал обновления подписки (мин)",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    OutlinedTextField(
        value = subscriptionRefreshText,
        onValueChange = { input ->
            if (input.all { it.isDigit() }) {
                onSubscriptionRefreshChange(input)
                input.toIntOrNull()?.takeIf { it in 1..10080 }?.let {
                    settingsStore.subscriptionRefreshMinutes = it
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Тип пинга",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = isHttpPing,
            onClick = {
                onHttpPingChange(true)
                settingsStore.isHttpPing = true
            },
            label = { Text("Полный ответ (GET)") }
        )
        FilterChip(
            selected = !isHttpPing,
            onClick = {
                onHttpPingChange(false)
                settingsStore.isHttpPing = false
            },
            label = { Text("Быстрый (HEAD)") }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Разрешённые транспорты",
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ProxyTransport.entries.forEach { transport ->
            FilterChip(
                selected = transport.key in allowedTransports,
                onClick = {
                    val updated = if (transport.key in allowedTransports) {
                        allowedTransports - transport.key
                    } else {
                        allowedTransports + transport.key
                    }
                    if (updated.isNotEmpty()) {
                        onAllowedTransportsChange(updated)
                        settingsStore.allowedTransports = updated
                    }
                },
                label = { Text(transport.label) }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
}