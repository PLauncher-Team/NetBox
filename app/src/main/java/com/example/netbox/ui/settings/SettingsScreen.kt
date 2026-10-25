package com.example.netbox.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.netbox.SettingsStore
import com.example.netbox.ThemeMode
import com.example.netbox.ui.theme.AppColorScheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    settingsTab: Int,
    onSettingsTabChange: (Int) -> Unit,
    subscriptionUrl: String,
    onSubscriptionUrlChange: (String) -> Unit,
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
    onAllowedTransportsChange: (Set<String>) -> Unit,
    enableDebugLogs: Boolean,
    onDebugLogsChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useSystemTheme: Boolean,
    onUseSystemThemeChange: (Boolean) -> Unit,
    isDynamicSupported: Boolean,
    colorSchemeOption: AppColorScheme,
    onColorSchemeChange: (AppColorScheme) -> Unit,
    customColor: Color,
    onExportLogs: () -> Unit,
    onShowColorPicker: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.Start
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = settingsTab == 0, onClick = { onSettingsTabChange(0) }, label = { Text("Соединение") })
            FilterChip(selected = settingsTab == 1, onClick = { onSettingsTabChange(1) }, label = { Text("Внешний вид и логи") })
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (settingsTab == 0) {
            ConnectionSettingsTab(
                settingsStore = settingsStore,
                subscriptionUrl = subscriptionUrl,
                onSubscriptionUrlChange = onSubscriptionUrlChange,
                pingUrl = pingUrl,
                onPingUrlChange = onPingUrlChange,
                parallelPingsText = parallelPingsText,
                onParallelPingsChange = onParallelPingsChange,
                pingTimeoutText = pingTimeoutText,
                onPingTimeoutChange = onPingTimeoutChange,
                pingAttemptsText = pingAttemptsText,
                onPingAttemptsChange = onPingAttemptsChange,
                observatoryIntervalText = observatoryIntervalText,
                onObservatoryIntervalChange = onObservatoryIntervalChange,
                subscriptionRefreshText = subscriptionRefreshText,
                onSubscriptionRefreshChange = onSubscriptionRefreshChange,
                isHttpPing = isHttpPing,
                onHttpPingChange = onHttpPingChange,
                allowedTransports = allowedTransports,
                onAllowedTransportsChange = onAllowedTransportsChange
            )
        } else {
            AppearanceSettingsTab(
                settingsStore = settingsStore,
                enableDebugLogs = enableDebugLogs,
                onDebugLogsChange = onDebugLogsChange,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useSystemTheme = useSystemTheme,
                onUseSystemThemeChange = onUseSystemThemeChange,
                isDynamicSupported = isDynamicSupported,
                colorSchemeOption = colorSchemeOption,
                onColorSchemeChange = onColorSchemeChange,
                customColor = customColor,
                onExportLogs = onExportLogs,
                onShowColorPicker = onShowColorPicker
            )
        }
    }
}