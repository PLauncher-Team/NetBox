package com.example.netbox.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netbox.SettingsStore
import com.example.netbox.ThemeMode
import com.example.netbox.ui.theme.AppColorScheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsTab(
    settingsStore: SettingsStore,
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Дебаг логирование",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onBackground
        )

        Switch(
            checked = enableDebugLogs,
            onCheckedChange = {
                onDebugLogsChange(it)
                settingsStore.enableDebugLogs = it
            }
        )
    }

    Button(
        onClick = { onExportLogs() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Сохранить логи в файл")
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Режим темы",
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
            selected = themeMode == ThemeMode.SYSTEM,
            onClick = {
                onThemeModeChange(ThemeMode.SYSTEM)
                settingsStore.themeMode = "SYSTEM"
            },
            label = { Text("Системная") }
        )
        FilterChip(
            selected = themeMode == ThemeMode.LIGHT,
            onClick = {
                onThemeModeChange(ThemeMode.LIGHT)
                settingsStore.themeMode = "LIGHT"
            },
            label = { Text("Светлая") }
        )
        FilterChip(
            selected = themeMode == ThemeMode.DARK,
            onClick = {
                onThemeModeChange(ThemeMode.DARK)
                settingsStore.themeMode = "DARK"
            },
            label = { Text("Тёмная") }
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Цветовая схема",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = if (!useSystemTheme) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Системная",
                style = MaterialTheme.typography.bodySmall,
                color = if (isDynamicSupported) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Switch(
                checked = useSystemTheme && isDynamicSupported,
                onCheckedChange = {
                    onUseSystemThemeChange(it)
                    settingsStore.useSystemTheme = it
                },
                enabled = isDynamicSupported
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val colorOptions = listOf(
            Triple(AppColorScheme.DEFAULT, Color(0xFF6650a4), "Стандартная"),
            Triple(AppColorScheme.BLUE, Color(0xFF005AC1), "Синяя"),
            Triple(AppColorScheme.GREEN, Color(0xFF006D32), "Зелёная"),
            Triple(AppColorScheme.ORANGE, Color(0xFF9C4200), "Оранжевая"),
            Triple(AppColorScheme.TEAL, Color(0xFF006874), "Бирюзовая"),
            Triple(AppColorScheme.CUSTOM, customColor, "Свой цвет")
        )

        colorOptions.forEach { (scheme, col, desc) ->
            val isSelected = colorSchemeOption == scheme
            val borderModifier = if (isSelected && !useSystemTheme) {
                Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
            } else {
                Modifier
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(col)
                    .then(borderModifier)
                    .clickable(enabled = !useSystemTheme) {
                        if (scheme == AppColorScheme.CUSTOM) {
                            onShowColorPicker()
                        } else {
                            onColorSchemeChange(scheme)
                            settingsStore.colorScheme = scheme.name
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (scheme == AppColorScheme.CUSTOM) {
                    Icon(
                        imageVector = Icons.Default.Create,
                        contentDescription = desc,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}