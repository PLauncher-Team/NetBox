package com.example.netbox

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.netbox.ui.theme.AppColorScheme
import com.example.netbox.ui.theme.NetBoxTheme
import com.example.netbox.xray.AppLogger
import com.example.netbox.xray.PingTester
import com.example.netbox.xray.SubscriptionFetcher
import com.example.netbox.xray.VlessConfig
import com.example.netbox.xray.XrayVpnService
import com.example.netbox.xray.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("netbox_settings", Context.MODE_PRIVATE)

    var subscriptionUrl: String
        get() = prefs.getString("sub_url", "") ?: ""
        set(value) = prefs.edit().putString("sub_url", value).apply()

    var pingUrl: String
        get() = prefs.getString("ping_url", "https://web.telegram.org") ?: "https://web.telegram.org"
        set(value) = prefs.edit().putString("ping_url", value).apply()

    var parallelPings: Int
        get() = prefs.getInt("parallel_pings", 15)
        set(value) = prefs.edit().putInt("parallel_pings", value).apply()

    var pingTimeoutSeconds: Int
        get() = prefs.getInt("ping_timeout_sec", 3)
        set(value) = prefs.edit().putInt("ping_timeout_sec", value).apply()

    var isHttpPing: Boolean
        get() = prefs.getBoolean("is_http_ping", true)
        set(value) = prefs.edit().putBoolean("is_http_ping", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var colorScheme: String
        get() = prefs.getString("color_scheme", "DEFAULT") ?: "DEFAULT"
        set(value) = prefs.edit().putString("color_scheme", value).apply()

    var useSystemTheme: Boolean
        get() = prefs.getBoolean("use_system_theme", true)
        set(value) = prefs.edit().putBoolean("use_system_theme", value).apply()

    var customColorArgb: Int
        get() = prefs.getInt("custom_color", 0xFF6650a4.toInt())
        set(value) = prefs.edit().putInt("custom_color", value).apply()

    var enableDebugLogs: Boolean
        get() = prefs.getBoolean("enable_debug_logs", false)
        set(value) {
            prefs.edit().putBoolean("enable_debug_logs", value).apply()
            AppLogger.isEnabled = value
        }
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        AppLogger.isEnabled = settingsStore.enableDebugLogs
        enableEdgeToEdge()
        setContent {
            NetBoxApp(settingsStore)
        }
    }
}

enum class Screen {
    HOME, SETTINGS
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetBoxApp(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var subscriptionUrl by remember { mutableStateOf(settingsStore.subscriptionUrl) }
    var pingUrl by remember { mutableStateOf(settingsStore.pingUrl) }
    var parallelPingsText by remember { mutableStateOf(settingsStore.parallelPings.toString()) }
    var pingTimeoutText by remember { mutableStateOf(settingsStore.pingTimeoutSeconds.toString()) }
    var isHttpPing by remember { mutableStateOf(settingsStore.isHttpPing) }
    var enableDebugLogs by remember { mutableStateOf(settingsStore.enableDebugLogs) }

    var themeMode by remember {
        mutableStateOf(
            try { ThemeMode.valueOf(settingsStore.themeMode) } catch (_: Exception) { ThemeMode.SYSTEM }
        )
    }
    var colorSchemeOption by remember {
        mutableStateOf(
            try { AppColorScheme.valueOf(settingsStore.colorScheme) } catch (_: Exception) { AppColorScheme.DEFAULT }
        )
    }
    var useSystemTheme by remember { mutableStateOf(settingsStore.useSystemTheme) }
    var customColor by remember { mutableStateOf(Color(settingsStore.customColorArgb)) }
    var showColorPicker by remember { mutableStateOf(false) }

    // VPN и логика подключения
    val vpnState by XrayVpnService.vpnState.collectAsState()
    val connectedServer by XrayVpnService.connectedServer.collectAsState()

    var statusMessage by remember { mutableStateOf("Отключено") }
    var isProcessing by remember { mutableStateOf(false) }
    var pendingBestConfig by remember { mutableStateOf<VlessConfig?>(null) }
    var connectJob by remember { mutableStateOf<Job?>(null) }

    // Launcher для разрешения VPN
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingBestConfig?.let { cfg ->
                XrayVpnService.startVpn(context, cfg)
                statusMessage = "Подключено: ${cfg.remark}"
            }
        } else {
            statusMessage = "Разрешение на VPN отклонено"
        }
        isProcessing = false
    }

    fun stopVpnProcess() {
        connectJob?.cancel()
        connectJob = null
        XrayVpnService.stopVpn(context)
        statusMessage = "Отключено"
        isProcessing = false
    }

    fun startConnectProcess() {
        if (isProcessing) return

        if (subscriptionUrl.isBlank()) {
            statusMessage = "Укажите URL подписки"
            return
        }

        isProcessing = true
        statusMessage = "Загрузка..."

        connectJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val configs = SubscriptionFetcher.fetchVlessConfigs(subscriptionUrl)
                coroutineContext.ensureActive()

                if (configs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Ошибка: подписка пуста"
                        isProcessing = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    statusMessage = "Пингование 0/${configs.size}"
                }
                val count = parallelPingsText.toIntOrNull() ?: 15
                val timeoutSec = pingTimeoutText.toIntOrNull() ?: 1

                val testedConfigs = PingTester.testAll(
                    configs = configs,
                    pingUrl = pingUrl,
                    parallelCount = count,
                    timeoutMs = timeoutSec * 1000,
                    isHttpFull = isHttpPing
                ) { completed, total, _ ->
                    coroutineScope.launch(Dispatchers.Main) {
                        statusMessage = "Пингование $completed/$total"
                    }
                }

                coroutineContext.ensureActive()

                val bestServer = testedConfigs.firstOrNull { it.pingMs > 0 }
                if (bestServer == null) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Ошибка: нет серверов"
                        isProcessing = false
                    }
                    return@launch
                }

                pendingBestConfig = bestServer
                withContext(Dispatchers.Main) {
                    statusMessage = "Подключение..."
                }

                val intent = VpnService.prepare(context)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    XrayVpnService.startVpn(context, bestServer)
                    withContext(Dispatchers.Main) {
                        statusMessage = "Подключено: ${bestServer.remark}"
                        isProcessing = false
                    }
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Отключено"
                    isProcessing = false
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Ошибка подключения"
                    isProcessing = false
                }
            }
        }
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveDynamicColor = useSystemTheme && isDynamicSupported

    NetBoxTheme(
        darkTheme = darkTheme,
        dynamicColor = effectiveDynamicColor,
        colorSchemeOption = colorSchemeOption,
        customColor = customColor
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .padding(24.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Кнопка настроек в верхнем левом углу
                    IconButton(
                        onClick = {
                            currentScreen = if (currentScreen == Screen.HOME) Screen.SETTINGS else Screen.HOME
                        },
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Icon(
                            imageVector = if (currentScreen == Screen.HOME) Icons.Default.Settings else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (currentScreen == Screen.HOME) "Настройки" else "Назад",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Название NetBox по центру сверху
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "NetBox",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Анимированное переключение экранов
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                                animationSpec = tween(300),
                                initialOffsetX = { fullWidth -> if (targetState == Screen.SETTINGS) fullWidth / 3 else -fullWidth / 3 }
                            ) togetherWith
                                fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                                    animationSpec = tween(300),
                                    targetOffsetX = { fullWidth -> if (targetState == Screen.SETTINGS) -fullWidth / 3 else fullWidth / 3 }
                                )
                        },
                        label = "screenTransition",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp)
                    ) { screen ->
                        when (screen) {
                            Screen.HOME -> {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    val isConnected = vpnState == VpnState.CONNECTED
                                    val isActiveOrProcessing = isConnected || isProcessing

                                    // Контейнер с круговой кнопкой и кольцом прогресса вокруг неё
                                    Box(
                                        modifier = Modifier
                                            .size(154.dp)
                                            .align(Alignment.Center)
                                            .offset(y = (-36).dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isProcessing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.fillMaxSize(),
                                                strokeWidth = 4.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                if (isActiveOrProcessing) {
                                                    stopVpnProcess()
                                                } else {
                                                    startConnectProcess()
                                                }
                                            },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isActiveOrProcessing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                contentColor = if (isActiveOrProcessing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                                            ),
                                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                                            modifier = Modifier.size(130.dp)
                                        ) {
                                            Text(
                                                text = if (isActiveOrProcessing) "Stop" else "Start",
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        }
                                    }

                                    // Краткий статус под кнопкой
                                    Text(
                                        text = if (isConnected && connectedServer != null) {
                                            "Подключено: ${connectedServer?.remark}"
                                        } else {
                                            statusMessage
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .offset(y = 72.dp)
                                            .padding(horizontal = 24.dp)
                                    )
                                }
                            }
                            Screen.SETTINGS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(top = 8.dp, bottom = 24.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    // URL подписки
                                    Text(
                                        text = "Url подписки",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )

                                    OutlinedTextField(
                                        value = subscriptionUrl,
                                        onValueChange = {
                                            subscriptionUrl = it
                                            settingsStore.subscriptionUrl = it
                                        },
                                        placeholder = {
                                            Text(
                                                text = "https://example.com/sub.txt",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // URL пинга
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
                                            pingUrl = it
                                            settingsStore.pingUrl = it
                                        },
                                        placeholder = {
                                            Text(
                                                text = "https://web.telegram.org",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Количество параллельных пингов
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
                                                parallelPingsText = input
                                                input.toIntOrNull()?.let { settingsStore.parallelPings = it }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Таймаут пинга (сек)
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
                                                pingTimeoutText = input
                                                input.toIntOrNull()?.let { settingsStore.pingTimeoutSeconds = it }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.medium
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Тип пинга
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
                                                isHttpPing = true
                                                settingsStore.isHttpPing = true
                                            },
                                            label = { Text("Полноценный (HTTP)") }
                                        )
                                        FilterChip(
                                            selected = !isHttpPing,
                                            onClick = {
                                                isHttpPing = false
                                                settingsStore.isHttpPing = false
                                            },
                                            label = { Text("Быстрый (TCP)") }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Переключатель дебаг логирования
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
                                                enableDebugLogs = it
                                                settingsStore.enableDebugLogs = it
                                            }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Режим темы
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
                                                themeMode = ThemeMode.SYSTEM
                                                settingsStore.themeMode = "SYSTEM"
                                            },
                                            label = { Text("Системная") }
                                        )
                                        FilterChip(
                                            selected = themeMode == ThemeMode.LIGHT,
                                            onClick = {
                                                themeMode = ThemeMode.LIGHT
                                                settingsStore.themeMode = "LIGHT"
                                            },
                                            label = { Text("Светлая") }
                                        )
                                        FilterChip(
                                            selected = themeMode == ThemeMode.DARK,
                                            onClick = {
                                                themeMode = ThemeMode.DARK
                                                settingsStore.themeMode = "DARK"
                                            },
                                            label = { Text("Тёмная") }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Раздел цветовой схемы
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
                                                    useSystemTheme = it
                                                    settingsStore.useSystemTheme = it
                                                },
                                                enabled = isDynamicSupported
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 6 разноцветных кнопок
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
                                                            showColorPicker = true
                                                        } else {
                                                            colorSchemeOption = scheme
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
                            }
                        }
                    }
                }
            }
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = customColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { selectedCol ->
                customColor = selectedCol
                colorSchemeOption = AppColorScheme.CUSTOM
                settingsStore.colorScheme = AppColorScheme.CUSTOM.name
                settingsStore.customColorArgb = selectedCol.hashCode()
                showColorPicker = false
            }
        )
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    var red by remember { mutableStateOf(initialColor.red * 255) }
    var green by remember { mutableStateOf(initialColor.green * 255) }
    var blue by remember { mutableStateOf(initialColor.blue * 255) }

    val currentColor = Color(red.toInt().coerceIn(0, 255), green.toInt().coerceIn(0, 255), blue.toInt().coerceIn(0, 255))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите цвет палитры") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(currentColor, CircleShape)
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Red: ${red.toInt()}")
                    Slider(
                        value = red,
                        onValueChange = { red = it },
                        valueRange = 0f..255f
                    )
                    Text("Green: ${green.toInt()}")
                    Slider(
                        value = green,
                        onValueChange = { green = it },
                        valueRange = 0f..255f
                    )
                    Text("Blue: ${blue.toInt()}")
                    Slider(
                        value = blue,
                        onValueChange = { blue = it },
                        valueRange = 0f..255f
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onColorSelected(currentColor) }
            ) {
                Text("Выбрать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
