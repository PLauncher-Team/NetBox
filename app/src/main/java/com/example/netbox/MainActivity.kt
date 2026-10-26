package com.example.netbox

import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.netbox.ui.components.ColorPickerDialog
import com.example.netbox.ui.home.HomeScreen
import com.example.netbox.ui.settings.SettingsScreen
import com.example.netbox.ui.theme.AppColorScheme
import com.example.netbox.ui.theme.NetBoxTheme
import com.example.netbox.xray.AppLogger
import com.example.netbox.xray.CountryCatalog
import com.example.netbox.xray.CountryInfo
import com.example.netbox.xray.PingTester
import com.example.netbox.xray.ProxyTransport
import com.example.netbox.xray.SubscriptionFetcher
import com.example.netbox.xray.VlessConfig
import com.example.netbox.xray.VpnState
import com.example.netbox.xray.XrayVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var pingAttemptsText by remember { mutableStateOf(settingsStore.pingAttempts.toString()) }
    var settingsTab by remember { mutableStateOf(0) }
    var observatoryIntervalText by remember { mutableStateOf(settingsStore.observatoryIntervalSeconds.toString()) }
    var subscriptionRefreshText by remember { mutableStateOf(settingsStore.subscriptionRefreshMinutes.toString()) }
    var allowedTransports by remember { mutableStateOf(settingsStore.allowedTransports) }
    var activeProxyPool by remember { mutableStateOf<List<VlessConfig>>(emptyList()) }
    var subscriptionCatalog by remember { mutableStateOf<List<VlessConfig>>(emptyList()) }
    var countryCandidatePool by remember { mutableStateOf<List<VlessConfig>>(emptyList()) }
    var pingDataVersion by remember { mutableStateOf(0) }
    var isCheckingCountryPool by remember { mutableStateOf(false) }
    var loadedCountryPoolUrl by remember { mutableStateOf("") }
    var loadedCountryPoolTransports by remember { mutableStateOf<Set<String>>(emptySet()) }
    var countryCatalog by remember { mutableStateOf<List<CountryInfo>>(emptyList()) }
    var selectedCountryCode by remember {
        mutableStateOf(settingsStore.selectedCountryCode.takeIf { it.isNotBlank() } ?: CountryInfo.AUTO_CODE)
    }
    var lastSourceSubscriptionUris by remember { mutableStateOf<Set<String>>(emptySet()) }
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

    // The pool contains only servers that have passed a connectivity check. Keep it
    // independent of the currently selected country; apply transport filters for display/use.
    val countryStatsPool = remember(countryCandidatePool, allowedTransports) {
        countryCandidatePool.filter {
            it.pingMs >= 0L && ProxyTransport.allows(it.params["type"], allowedTransports)
        }
    }
    val countryServerCounts = remember(countryStatsPool, countryCatalog) {
        countryStatsPool.asSequence()
            .mapNotNull { CountryCatalog.detectCountry(it.remark, countryCatalog)?.code }
            .groupingBy { it }
            .eachCount()
    }
    val availableCountryCodes = remember(countryStatsPool, countryCatalog) {
        countryStatsPool.asSequence()
            .mapNotNull { CountryCatalog.detectCountry(it.remark, countryCatalog)?.code }
            .toSet()
    }
    val availableCountries = remember(countryCatalog, availableCountryCodes) {
        countryCatalog.filter { availableCountryCodes.contains(it.code) }
    }
    // Display the best ping already known for each country; this never triggers new tests.
    val countryServerMinPings = remember(countryStatsPool, countryCatalog, pingDataVersion) {
        countryStatsPool.asSequence()
            .filter { it.pingMs >= 0L }
            .mapNotNull { config ->
                CountryCatalog.detectCountry(config.remark, countryCatalog)?.code?.let { code ->
                    code to config.pingMs
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, pings) -> pings.minOrNull()!! }
    }

    val vpnState by XrayVpnService.vpnState.collectAsState()
    val connectedServer by XrayVpnService.connectedServer.collectAsState()

    fun preserveKnownPings(configs: List<VlessConfig>): List<VlessConfig> {
        val knownPings = (countryCandidatePool + activeProxyPool +
            listOfNotNull(XrayVpnService.connectedServer.value))
            .filter { it.pingMs >= 0L }
            .associate { it.rawUri to it.pingMs }
        return configs.onEach { config ->
            knownPings[config.rawUri]?.let { config.pingMs = it }
        }
    }

    suspend fun testWorkingCandidates(
        configs: List<VlessConfig>,
        onProgress: (completed: Int, total: Int, bestConfig: VlessConfig?) -> Unit = { _, _, _ -> }
    ): List<VlessConfig> {
        val candidates = configs.filter {
            ProxyTransport.allows(it.params["type"], allowedTransports)
        }
        if (candidates.isEmpty()) return emptyList()

        val timeoutSeconds = (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60)
        val tested = XrayVpnService.testCandidatesThroughTunnel(
            context = context,
            configs = candidates,
            pingUrl = pingUrl,
            parallelCount = (parallelPingsText.toIntOrNull() ?: 15).coerceIn(1, 100),
            timeoutMs = timeoutSeconds * 1000,
            method = if (isHttpPing) "GET" else "HEAD",
            attempts = (pingAttemptsText.toIntOrNull() ?: 2).coerceIn(1, 5),
            onProgress = onProgress
        )
        return tested.asSequence()
            .filter { it.pingMs >= 0L }
            .distinctBy { it.rawUri }
            .sortedBy { it.pingMs }
            .toList()
    }

    BackHandler(enabled = currentScreen == Screen.SETTINGS) {
        currentScreen = Screen.HOME
    }

    var statusMessage by remember { mutableStateOf("Отключено") }
    var isProcessing by remember { mutableStateOf(false) }
    var pendingProxyPool by remember { mutableStateOf<List<VlessConfig>>(emptyList()) }
    var maintenanceToken by remember { mutableStateOf(0) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    var isRefreshingSubscription by remember { mutableStateOf(false) }
    var refreshSubscriptionAfterConnect by remember { mutableStateOf(false) }
    var forceCurrentServerRecovery by remember { mutableStateOf(false) }

    val logExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(AppLogger.exportText())
                }
                statusMessage = "Логи сохранены в файл"
            } catch (e: Exception) {
                AppLogger.e("NetBoxLog-UI", "Could not export logs: ${e.message}", e)
                statusMessage = "Не удалось сохранить логи"
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pool = pendingProxyPool
            if (pool.isNotEmpty()) {
                activeProxyPool = pool
                XrayVpnService.startVpn(
                    context,
                    pool,
                    settingsStore.observatoryIntervalSeconds,
                    settingsStore.pingTimeoutSeconds,
                    settingsStore.pingUrl,
                    if (settingsStore.isHttpPing) "GET" else "HEAD"
                )
                statusMessage = "Подключение: авто-выбор из ${pool.size} прокси"
                maintenanceToken++
            } else {
                statusMessage = "Нет подходящих прокси"
            }
        } else {
            statusMessage = "Разрешение на VPN отклонено"
        }
        isProcessing = false
    }

    fun stopVpnProcess() {
        connectJob?.cancel()
        connectJob = null
        forceCurrentServerRecovery = false
        pendingProxyPool = emptyList()
        activeProxyPool = emptyList()
        XrayVpnService.stopVpn(context)
        statusMessage = "Отключено"
        isProcessing = false
    }

    fun forceRemoveCurrentServer() {
        if (XrayVpnService.connectedServer.value == null || vpnState != VpnState.CONNECTED) return

        forceCurrentServerRecovery = true
        statusMessage = "Принудительный поиск замены сервера…"
        AppLogger.w("NetBoxLog-Recovery", "Forced recovery requested for current server")
    }

    fun requestSubscriptionRefresh() {
        if (isRefreshingSubscription || isCheckingCountryPool || subscriptionUrl.isBlank()) return
        isRefreshingSubscription = true
        val requestedCountryCode = selectedCountryCode

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val throughVpn = XrayVpnService.vpnState.value == VpnState.CONNECTED
                val fetched = SubscriptionFetcher.fetchVlessConfigs(
                    context = context,
                    urlStr = subscriptionUrl,
                    throughVpn = throughVpn
                )
                if (fetched.configs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Не удалось загрузить подписку"
                    }
                    return@launch
                }
                if (throughVpn && fetched.loadedFromCache) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Подписка недоступна через VPN · используется кеш"
                    }
                    return@launch
                }

                val countries = CountryCatalog.load(context)
                val refreshedConfigs = preserveKnownPings(fetched.configs)
                val transportCandidates = refreshedConfigs.filter {
                    ProxyTransport.allows(it.params["type"], allowedTransports)
                }
                withContext(Dispatchers.Main) {
                    countryCatalog = countries
                    subscriptionCatalog = refreshedConfigs
                    lastSourceSubscriptionUris = fetched.configs.map { it.rawUri }.toSet()
                }

                val healthyAllCountryConfigs = testWorkingCandidates(transportCandidates)
                withContext(Dispatchers.Main) {
                    countryCandidatePool = healthyAllCountryConfigs
                    pingDataVersion++
                }

                if (healthyAllCountryConfigs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "После проверки не найдено работающих прокси"
                    }
                    return@launch
                }

                var candidates = CountryCatalog.filterByCountry(
                    healthyAllCountryConfigs,
                    requestedCountryCode,
                    countries
                )
                if (candidates.isEmpty() && requestedCountryCode != CountryInfo.AUTO_CODE) {
                    candidates = healthyAllCountryConfigs
                    withContext(Dispatchers.Main) {
                        selectedCountryCode = CountryInfo.AUTO_CODE
                        settingsStore.selectedCountryCode = CountryInfo.AUTO_CODE
                        statusMessage = "В выбранной стране нет работающих серверов · включён автоматический выбор"
                    }
                }

                if (candidates.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "После проверки не найдено подходящих прокси"
                    }
                    return@launch
                }

                if (!throughVpn) {
                    withContext(Dispatchers.Main) {
                        statusMessage = if (fetched.loadedFromCache) {
                            "Подписка недоступна · в кеше ${candidates.size} рабочих прокси"
                        } else {
                            "Подписка обновлена · найдено ${candidates.size} рабочих прокси"
                        }
                    }
                    return@launch
                }

                val healthy = candidates.sortedBy { it.pingMs }
                withContext(Dispatchers.Main) {
                    val best = healthy.first()
                    val current = XrayVpnService.connectedServer.value
                    activeProxyPool = healthy
                    pingDataVersion++
                    if (current?.rawUri != best.rawUri) {
                        XrayVpnService.updateProxyPool(
                            context,
                            listOf(best),
                            (observatoryIntervalText.toIntOrNull() ?: 30).coerceIn(10, 86400),
                            (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60),
                            pingUrl,
                            if (isHttpPing) "GET" else "HEAD"
                        )
                        statusMessage = "Подписка обновлена · переключение на ${best.remark}"
                    } else {
                        statusMessage = "Подписка обновлена · текущий прокси остаётся лучшим"
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("NetBoxLog-Subscription", "Manual subscription refresh failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Ошибка обновления подписки"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isRefreshingSubscription = false
                }
            }
        }
    }

    fun startConnectProcess() {
        if (isProcessing || isCheckingCountryPool) return

        if (subscriptionUrl.isBlank()) {
            statusMessage = "Укажите URL подписки"
            return
        }

        val requestedCountryCode = selectedCountryCode
        isProcessing = true
        statusMessage = "Загрузка подписки..."

        connectJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val fetchResult = SubscriptionFetcher.fetchVlessConfigs(context, subscriptionUrl)
                coroutineContext.ensureActive()
                val countries = CountryCatalog.load(context)
                val refreshedConfigs = preserveKnownPings(fetchResult.configs)
                val transportFiltered = refreshedConfigs.filter { config ->
                    ProxyTransport.allows(config.params["type"], allowedTransports)
                }
                withContext(Dispatchers.Main) {
                    countryCatalog = countries
                    subscriptionCatalog = refreshedConfigs
                }

                if (transportFiltered.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        countryCandidatePool = emptyList()
                        pingDataVersion++
                        statusMessage = if (fetchResult.configs.isEmpty()) {
                            "Ошибка: подписка пуста или недоступна"
                        } else {
                            "Нет прокси с разрешёнными транспортами"
                        }
                        isProcessing = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    statusMessage = "Проверка прокси 0/${transportFiltered.size}"
                }

                val parallelCount = (parallelPingsText.toIntOrNull() ?: 15).coerceIn(1, 100)
                val timeoutSeconds = (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60)
                val timeoutMs = timeoutSeconds * 1000

                val testedConfigs = XrayVpnService.testCandidatesThroughTunnel(
                    context = context,
                    configs = transportFiltered,
                    pingUrl = pingUrl,
                    parallelCount = parallelCount,
                    timeoutMs = timeoutMs,
                    method = if (isHttpPing) "GET" else "HEAD",
                    attempts = (pingAttemptsText.toIntOrNull() ?: 2).coerceIn(1, 5)
                ) { completed, total, _ ->
                    coroutineScope.launch(Dispatchers.Main) {
                        statusMessage = "Проверка прокси $completed/$total"
                    }
                }
                coroutineContext.ensureActive()
                val healthyAllCountryConfigs = testedConfigs.filter {
                    it.pingMs >= 0L
                }
                // Keep only healthy servers in the cross-country working pool. Country
                // counts and minimum pings are both derived from this same pool.
                withContext(Dispatchers.Main) {
                    countryCandidatePool = healthyAllCountryConfigs
                    pingDataVersion++
                }
                val healthyConfigs = CountryCatalog.filterByCountry(
                    healthyAllCountryConfigs,
                    requestedCountryCode,
                    countries
                )

                if (healthyConfigs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (requestedCountryCode != CountryInfo.AUTO_CODE) {
                            selectedCountryCode = CountryInfo.AUTO_CODE
                            settingsStore.selectedCountryCode = CountryInfo.AUTO_CODE
                        }
                        statusMessage = if (requestedCountryCode == CountryInfo.AUTO_CODE) {
                            "Нет доступных прокси с пингом до ${timeoutSeconds} с"
                        } else {
                            "В выбранной стране нет рабочих серверов · выберите другую страну"
                        }
                        isProcessing = false
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    lastSourceSubscriptionUris = fetchResult.configs.map { it.rawUri }.toSet()
                    refreshSubscriptionAfterConnect = fetchResult.loadedFromCache
                    pendingProxyPool = healthyConfigs
                    statusMessage = "Подключение через пул из ${healthyConfigs.size} прокси..."

                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        activeProxyPool = healthyConfigs
                        XrayVpnService.startVpn(
                            context,
                            healthyConfigs,
                            settingsStore.observatoryIntervalSeconds,
                            timeoutSeconds,
                            pingUrl,
                            if (isHttpPing) "GET" else "HEAD"
                        )
                        statusMessage = "Подключение: авто-выбор из ${healthyConfigs.size} прокси"
                        isProcessing = false
                        maintenanceToken++
                    }
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    statusMessage = "Отключено"
                    isProcessing = false
                }
            } catch (e: Exception) {
                AppLogger.e("NetBoxLog-Connect", "Connection setup failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "Ошибка подключения"
                    isProcessing = false
                }
            }
        }
    }

    fun handleCountrySelection(countryCode: String) {
        if (countryCode == selectedCountryCode || isProcessing || isRefreshingSubscription || vpnState == VpnState.CONNECTING) return

        val countries = if (countryCatalog.isNotEmpty()) countryCatalog else CountryCatalog.load(context)
        countryCatalog = countries
        val countryName = countries.firstOrNull { it.code == countryCode }?.displayName
            ?: if (countryCode == CountryInfo.AUTO_CODE) "Автоматически" else countryCode
        val candidates = CountryCatalog.filterByCountry(countryCandidatePool, countryCode, countries)

        if (vpnState != VpnState.CONNECTED) {
            selectedCountryCode = countryCode
            settingsStore.selectedCountryCode = countryCode
            activeProxyPool = CountryCatalog.filterByCountry(activeProxyPool, countryCode, countries)
            statusMessage = if (countryCode == CountryInfo.AUTO_CODE) {
                "Выбран автоматический выбор сервера"
            } else {
                "Выбрана страна $countryName · применится при подключении"
            }
            return
        }

        if (candidates.isEmpty()) {
            statusMessage = if (countryCode == CountryInfo.AUTO_CODE) {
                "Список серверов пуст · обновите подписку"
            } else {
                "В подписке не найдены серверы для страны $countryName"
            }
            return
        }

        // Reuse known in-memory ping results. Never probe servers just because
        // the user selected a different country; unknown results sort last.
        val knownPings = (subscriptionCatalog + activeProxyPool +
            listOfNotNull(XrayVpnService.connectedServer.value))
            .filter { it.pingMs >= 0L }
            .associate { it.rawUri to it.pingMs }

        val rankedCandidates = candidates
            .onEach { candidate ->
                knownPings[candidate.rawUri]?.let { candidate.pingMs = it }
            }
            .sortedWith(
                compareBy<VlessConfig> { if (it.pingMs >= 0L) 0 else 1 }
                    .thenBy { if (it.pingMs >= 0L) it.pingMs else Long.MAX_VALUE }
            )

        val best = rankedCandidates.first()
        val current = XrayVpnService.connectedServer.value

        selectedCountryCode = countryCode
        settingsStore.selectedCountryCode = countryCode
        activeProxyPool = rankedCandidates
        pingDataVersion++

        if (current?.rawUri != best.rawUri) {
            XrayVpnService.updateProxyPool(
                context,
                listOf(best),
                (observatoryIntervalText.toIntOrNull() ?: 30).coerceIn(10, 86400),
                (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60),
                pingUrl,
                if (isHttpPing) "GET" else "HEAD"
            )
            statusMessage = if (best.pingMs >= 0L) {
                "Страна: $countryName · переключение на ${best.remark} (${best.pingMs} мс, по сохранённому ping)"
            } else {
                "Страна: $countryName · переключение на ${best.remark} без повторного ping"
            }
        } else {
            statusMessage = if (best.pingMs >= 0L) {
                "Страна: $countryName · текущий сервер лучший по сохранённому ping (${best.pingMs} мс)"
            } else {
                "Страна: $countryName · текущий сервер оставлен без повторного ping"
            }
        }
        maintenanceToken++
    }

    LaunchedEffect(Unit) {
        countryCatalog = withContext(Dispatchers.IO) { CountryCatalog.load(context) }
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
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(20.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {

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

                    IconButton(
                        onClick = { requestSubscriptionRefresh() },
                        enabled = currentScreen == Screen.HOME && vpnState == VpnState.CONNECTED &&
                            !isRefreshingSubscription && !isProcessing && !isCheckingCountryPool,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        if (isRefreshingSubscription) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Обновить подписку",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

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
                            Screen.HOME -> HomeScreen(
                                vpnState = vpnState,
                                connectedServer = connectedServer,
                                activeProxyPool = activeProxyPool,
                                statusMessage = statusMessage,
                                isProcessing = isProcessing,
                                forceCurrentServerRecovery = forceCurrentServerRecovery,
                                pingUrl = pingUrl,
                                availableCountries = availableCountries,
                                selectedCountryCode = selectedCountryCode,
                                countryServerCounts = countryServerCounts,
                                countryServerMinPings = countryServerMinPings,
                                countrySelectionEnabled = subscriptionCatalog.isNotEmpty() &&
                                    !isProcessing && !isCheckingCountryPool && !isRefreshingSubscription &&
                                    vpnState != VpnState.CONNECTING,
                                onCountrySelected = { handleCountrySelection(it) },
                                onToggleConnection = {
                                    if (vpnState == VpnState.CONNECTED || isProcessing || vpnState == VpnState.CONNECTING) {
                                        stopVpnProcess()
                                    } else if (isCheckingCountryPool) {
                                        statusMessage = "Сначала завершится проверка списка серверов"
                                    } else {
                                        startConnectProcess()
                                    }
                                },
                                onForceRemoveCurrentServer = { forceRemoveCurrentServer() }
                            )
                            Screen.SETTINGS -> SettingsScreen(settingsStore, settingsTab, { settingsTab = it }, subscriptionUrl, { subscriptionUrl = it }, pingUrl, { pingUrl = it }, parallelPingsText, { parallelPingsText = it }, pingTimeoutText, { pingTimeoutText = it }, pingAttemptsText, { pingAttemptsText = it }, observatoryIntervalText, { observatoryIntervalText = it }, subscriptionRefreshText, { subscriptionRefreshText = it }, isHttpPing, { isHttpPing = it }, allowedTransports, { allowedTransports = it }, enableDebugLogs, { enableDebugLogs = it }, themeMode, { themeMode = it }, useSystemTheme, { useSystemTheme = it }, isDynamicSupported, colorSchemeOption, { colorSchemeOption = it }, customColor, { logExportLauncher.launch("netbox-logs.txt") }, { showColorPicker = true })
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(
        vpnState,
        maintenanceToken,
        pingUrl,
        pingTimeoutText,
        pingAttemptsText,
        observatoryIntervalText,
        subscriptionRefreshText,
        subscriptionUrl,
        parallelPingsText,
        isHttpPing,
        allowedTransports,
        forceCurrentServerRecovery
    ) {
        if (vpnState != VpnState.CONNECTED) {
            return@LaunchedEffect
        }

        if (refreshSubscriptionAfterConnect && !isRefreshingSubscription) {
            refreshSubscriptionAfterConnect = false
            requestSubscriptionRefresh()
        }

        var lastSubscriptionRefreshAt = System.currentTimeMillis()

        suspend fun testCandidates(
            candidates: List<VlessConfig>,
            stopAfterFirstHealthy: Boolean = false
        ): List<VlessConfig> {
            if (candidates.isEmpty()) return emptyList()
            val timeoutSeconds = (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60)
            val timeoutMs = timeoutSeconds * 1000
            val tested = XrayVpnService.testCandidatesThroughTunnel(
                context = context,
                configs = candidates,
                pingUrl = pingUrl,
                parallelCount = (parallelPingsText.toIntOrNull() ?: 15).coerceIn(1, 100),
                timeoutMs = timeoutMs,
                method = if (isHttpPing) "GET" else "HEAD",
                attempts = (pingAttemptsText.toIntOrNull() ?: 2).coerceIn(1, 5),
                stopAfterFirstHealthy = stopAfterFirstHealthy
            ) { _, _, _ -> }
            pingDataVersion++
            return tested
                .filter { it.pingMs >= 0L }
                .sortedBy { it.pingMs }
        }

        suspend fun refreshSubscription(force: Boolean): Boolean {
            val fetched = withContext(Dispatchers.IO) {
                SubscriptionFetcher.fetchVlessConfigs(
                    context,
                    subscriptionUrl,
                    throughVpn = XrayVpnService.vpnState.value == VpnState.CONNECTED
                )
            }
            if (fetched.configs.isEmpty()) return false

            if (fetched.loadedFromCache && activeProxyPool.isNotEmpty() &&
                (!force || XrayVpnService.vpnState.value == VpnState.CONNECTED)
            ) return false

            val countries = if (countryCatalog.isNotEmpty()) countryCatalog else CountryCatalog.load(context)
            val refreshedConfigs = preserveKnownPings(fetched.configs)
            countryCatalog = countries
            subscriptionCatalog = refreshedConfigs
            val transportCandidates = refreshedConfigs.filter {
                ProxyTransport.allows(it.params["type"], allowedTransports)
            }
            if (transportCandidates.isEmpty()) {
                countryCandidatePool = emptyList()
                pingDataVersion++
                statusMessage = "В подписке нет прокси с разрешёнными транспортами"
                return false
            }

            // Recheck every allowed country, not just the currently selected one. This
            // prevents untested countries from reappearing without ping values after refresh.
            val healthyAllCountryConfigs = testCandidates(transportCandidates)
            countryCandidatePool = healthyAllCountryConfigs
            if (healthyAllCountryConfigs.isEmpty()) {
                statusMessage = "Обновление подписки: работающие прокси не найдены"
                return false
            }

            var healthyConfigs = CountryCatalog.filterByCountry(
                healthyAllCountryConfigs,
                selectedCountryCode,
                countries
            )
            if (healthyConfigs.isEmpty() && selectedCountryCode != CountryInfo.AUTO_CODE) {
                selectedCountryCode = CountryInfo.AUTO_CODE
                settingsStore.selectedCountryCode = CountryInfo.AUTO_CODE
                healthyConfigs = healthyAllCountryConfigs
                statusMessage = "В выбранной стране больше нет рабочих серверов · включён автоматический выбор"
            }
            if (healthyConfigs.isEmpty()) {
                statusMessage = "Обновление подписки: работающие прокси не найдены"
                return false
            }

            val current = XrayVpnService.connectedServer.value
            val best = healthyConfigs.first()
            activeProxyPool = healthyConfigs
            lastSourceSubscriptionUris = fetched.configs.map { it.rawUri }.toSet()

            if (current?.rawUri != best.rawUri) {
                XrayVpnService.updateProxyPool(
                    context,
                    listOf(best),
                    (observatoryIntervalText.toIntOrNull() ?: 30).coerceIn(10, 86400),
                    (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60),
                    pingUrl,
                    if (isHttpPing) "GET" else "HEAD"
                )
                statusMessage = "Подписка обновлена · переключение на ${best.remark}"
            } else {
                statusMessage = "Подписка обновлена · текущий прокси остаётся лучшим"
            }
            return true
        }

        while (true) {
            val checkIntervalSeconds = (observatoryIntervalText.toIntOrNull() ?: 30).coerceIn(10, 86400)
            if (!forceCurrentServerRecovery) delay(checkIntervalSeconds * 1000L)
            if (XrayVpnService.vpnState.value != VpnState.CONNECTED) break

            val current = XrayVpnService.connectedServer.value
            if (current != null) {
                val timeoutSeconds = (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60)
                val forcedRecovery = forceCurrentServerRecovery
                val currentProbeMs = if (forcedRecovery) {
                    -1L
                } else {
                    PingTester.testActiveProxyWithRetry(
                        pingUrl,
                        timeoutSeconds * 1000,
                        if (isHttpPing) "GET" else "HEAD",
                        attempts = (pingAttemptsText.toIntOrNull() ?: 2).coerceIn(1, 5)
                    )
                }
                val currentHealthy = !forcedRecovery && currentProbeMs >= 0L
                if (currentHealthy) {
                    current.pingMs = currentProbeMs
                    pingDataVersion++
                }
                if (!currentHealthy) {
                    statusMessage = if (forcedRecovery) {
                        "Текущий сервер исключён · поиск замены"
                    } else {
                        "Текущий прокси недоступен · поиск замены"
                    }

                    val candidates = activeProxyPool
                        .filter { it.rawUri != current.rawUri }
                        .sortedBy { if (it.pingMs >= 0L) it.pingMs else Long.MAX_VALUE }
                    activeProxyPool = activeProxyPool.filter { it.rawUri != current.rawUri }

                    val selected = testCandidates(
                        candidates,
                        stopAfterFirstHealthy = true
                    ).firstOrNull()

                    val failedCandidates = if (selected == null) {
                        candidates
                    } else {
                        candidates.takeWhile { it.rawUri != selected.rawUri }
                    }
                    failedCandidates.forEach { candidate ->
                        activeProxyPool = activeProxyPool.filter { it.rawUri != candidate.rawUri }
                        countryCandidatePool = countryCandidatePool.filter { it.rawUri != candidate.rawUri }
                        AppLogger.w(
                            "NetBoxLog-Recovery",
                            "Removing unreachable candidate ${candidate.remark} (${candidate.address}:${candidate.port})"
                        )
                    }
                    if (selected != null) {

                        activeProxyPool = (listOf(selected) + activeProxyPool
                            .filter { it.rawUri != selected.rawUri })
                            .distinctBy { it.rawUri }
                            .sortedBy { if (it.pingMs >= 0L) it.pingMs else Long.MAX_VALUE }
                        XrayVpnService.updateProxyPool(
                            context,
                            listOf(selected),
                            checkIntervalSeconds,
                            (pingTimeoutText.toIntOrNull() ?: 3).coerceIn(1, 60),
                            pingUrl,
                            if (isHttpPing) "GET" else "HEAD"
                        )
                        statusMessage = "Переключение на ${selected.remark}"
                    } else {
                        lastSubscriptionRefreshAt = System.currentTimeMillis()
                        val refreshed = refreshSubscription(force = true)
                        if (!refreshed) {
                            statusMessage = "Замена не найдена · обновление подписки не удалось"
                        }
                    }
                }
            }
            if (forceCurrentServerRecovery) {
                forceCurrentServerRecovery = false
            }

            val refreshIntervalMs = (subscriptionRefreshText.toIntOrNull() ?: 60)
                .coerceIn(1, 10080) * 60_000L
            val now = System.currentTimeMillis()
            if (now - lastSubscriptionRefreshAt >= refreshIntervalMs) {
                lastSubscriptionRefreshAt = now
                refreshSubscription(force = false)
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

