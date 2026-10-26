package com.example.netbox.xray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.netbox.MainActivity
import com.example.netbox.NetBoxTileService
import com.example.netbox.SettingsStore
import com.v2ray.ang.service.TProxyService
import libXray.LibXray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class XrayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var xrayStarted = false
    private var tunnelStarted = false
    private var activeProxyPool: List<VlessConfig> = emptyList()
    private var activeProbeUrl = "https://www.gstatic.com/generate_204"
    private var activeObservatoryIntervalSeconds = 30
    private var activeProbeTimeoutSeconds = 3
    private var activeProbeMethod = "HEAD"

    companion object {
        private const val TAG = "NetBoxLog-Service"
        private const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"

        @Volatile var instance: XrayVpnService? = null

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _connectedServer = MutableStateFlow<VlessConfig?>(null)
        val connectedServer: StateFlow<VlessConfig?> = _connectedServer.asStateFlow()

        var currentConfig: VlessConfig? = null
        @Volatile private var pendingProxyPool: List<VlessConfig> = emptyList()
        @Volatile private var pendingProbeUrl: String = DEFAULT_PROBE_URL
        @Volatile private var pendingObservatoryIntervalSeconds: Int = 30
        @Volatile private var pendingProbeTimeoutSeconds: Int = 3
        @Volatile private var pendingProbeMethod: String = "HEAD"

        fun startVpn(
            context: Context,
            configs: List<VlessConfig>,
            observatoryIntervalSeconds: Int = 30,
            probeTimeoutSeconds: Int = 3,
            probeUrl: String = DEFAULT_PROBE_URL,
            probeMethod: String = "HEAD"
        ) {
            val pool = configs.distinctBy { it.rawUri }
                .sortedWith(compareBy<VlessConfig> { if (it.pingMs >= 0) 0 else 1 }.thenBy { it.pingMs })
            if (pool.isEmpty()) return

            pendingProxyPool = pool
            pendingProbeUrl = probeUrl.ifBlank { DEFAULT_PROBE_URL }
            pendingObservatoryIntervalSeconds = observatoryIntervalSeconds.coerceAtLeast(10)
            pendingProbeTimeoutSeconds = probeTimeoutSeconds.coerceAtLeast(1)
            pendingProbeMethod = if (probeMethod.equals("HEAD", ignoreCase = true)) "HEAD" else "GET"
            currentConfig = pool.first()

            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = "START"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startVpn(context: Context, config: VlessConfig) {
            startVpn(context, listOf(config))
        }

        fun updateProxyPool(
            context: Context,
            configs: List<VlessConfig>,
            observatoryIntervalSeconds: Int,
            probeTimeoutSeconds: Int,
            probeUrl: String,
            probeMethod: String = "HEAD"
        ) {
            val pool = configs.distinctBy { it.rawUri }
                .sortedWith(compareBy<VlessConfig> { if (it.pingMs >= 0) 0 else 1 }.thenBy { it.pingMs })
            if (pool.isEmpty()) return

            pendingProxyPool = pool
            pendingProbeUrl = probeUrl.ifBlank { DEFAULT_PROBE_URL }
            pendingObservatoryIntervalSeconds = observatoryIntervalSeconds.coerceAtLeast(10)
            pendingProbeTimeoutSeconds = probeTimeoutSeconds.coerceAtLeast(1)
            pendingProbeMethod = if (probeMethod.equals("HEAD", ignoreCase = true)) "HEAD" else "GET"

            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = "UPDATE_POOL"
            }
            context.startService(intent)
        }

        suspend fun testCandidatesThroughTunnel(
            context: Context,
            configs: List<VlessConfig>,
            pingUrl: String,
            parallelCount: Int,
            timeoutMs: Int,
            method: String,
            attempts: Int = 2,
            stopAfterFirstHealthy: Boolean = false,
            onProgress: (completed: Int, total: Int, bestConfig: VlessConfig?) -> Unit
        ): List<VlessConfig> {
            if (configs.isEmpty()) return emptyList()

            val service = instance
            if (service != null &&
                service.xrayStarted &&
                service.vpnInterface != null &&
                service.tunnelStarted
            ) {
                return try {
                    val portMap = service.prepareCandidateProbeRoutes(configs)
                    val credentials = LocalProxyAuth.credentials(context)
                    PingTester.testCandidatesOnPorts(
                        configs = configs,
                        portByRawUri = portMap,
                        pingUrl = pingUrl,
                        timeoutMs = timeoutMs,
                        method = method,
                        parallelCount = parallelCount,
                        attempts = attempts,
                        stopAfterFirstHealthy = stopAfterFirstHealthy,
                        socksUsername = credentials.username,
                        socksPassword = credentials.password,
                        onProgress = onProgress
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "Could not test candidate proxies through active Xray: ${e.message}", e)
                    configs.forEach { it.pingMs = -1L }
                    configs
                }
            }

            return PingTester.testAllThroughTemporaryXray(
                context = context,
                configs = configs,
                pingUrl = pingUrl,
                parallelCount = parallelCount,
                timeoutMs = timeoutMs,
                method = method,
                attempts = attempts,
                onProgress = onProgress
            )
        }

        fun refreshNotification(context: Context) {
            instance?.refreshForegroundNotification()
        }

        fun stopVpn(context: Context) {
            AppLogger.d(TAG, "stopVpn called")
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.d(TAG, "XrayVpnService onCreate starting...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            "START" -> {
                if (vpnInterface != null && tunnelStarted) {
                    updateProxyPoolInternal()
                } else {
                    startVpnInternal()
                }
            }
            "UPDATE_POOL" -> updateProxyPoolInternal()
            "REFRESH_NOTIFICATION" -> refreshForegroundNotification()
            "STOP" -> stopVpnInternal()
        }
        return START_NOT_STICKY
    }

    private fun startVpnInternal() {
        val pool = pendingProxyPool.ifEmpty { listOfNotNull(currentConfig) }
        val config = pool.firstOrNull()
        if (config == null) {
            AppLogger.e(TAG, "startVpnInternal aborted: no proxy configs")
            stopSelf()
            return
        }

        currentConfig = config
        activeProxyPool = pool
        activeProbeUrl = pendingProbeUrl
        activeObservatoryIntervalSeconds = pendingObservatoryIntervalSeconds
        activeProbeTimeoutSeconds = pendingProbeTimeoutSeconds
        activeProbeMethod = pendingProbeMethod
        _vpnState.value = VpnState.CONNECTING

        try {
            ensureNotificationChannel()
            startForegroundNotification("Подключение к ${config.remark}...")

            startXrayEngine(
                activeProxyPool,
                activeProbeUrl,
                activeObservatoryIntervalSeconds,
                activeProbeTimeoutSeconds,
                activeProbeMethod
            )

            val builder = Builder()
                .setSession("NetBox VPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.0.0.1")

            try {
                builder.addDisallowedApplication(packageName)
                AppLogger.d(TAG, "Disallowed package '$packageName' from VPN loopback")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not addDisallowedApplication: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                throw IllegalStateException("VpnService.Builder.establish() returned null")
            }
            vpnInterface = pfd

            val credentials = LocalProxyAuth.credentials(this)
            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.yaml")
            hevConfigFile.writeText(
                """
                tunnel:
                  mtu: 8500
                  ipv4: 10.0.0.2
                socks5:
                  port: 10808
                  address: 127.0.0.1
                  username: ${credentials.username}
                  password: ${credentials.password}
                  udp: 'udp'
                misc:
                  task-stack-size: 8192
                """.trimIndent()
            )

            tunnelStarted = true
            val tunnelOk = TProxyService.TProxyStartService(hevConfigFile.absolutePath, pfd.fd)
            if (!tunnelOk || !TProxyService.TProxyIsRunning()) {
                throw IllegalStateException("HEV tun2socks native service failed to start")
            }

            _connectedServer.value = config
            _vpnState.value = VpnState.CONNECTED
            refreshForegroundNotification("Подключено · ${config.remark}")
            refreshQuickSettingsTile()
            AppLogger.d(TAG, "VPN started on ${config.remark}; app candidate pool contains ${activeProxyPool.size} proxies")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpnInternal()
        }
    }

    private fun startXrayEngine(
        pool: List<VlessConfig>,
        probeUrl: String,
        observatoryIntervalSeconds: Int,
        probeTimeoutSeconds: Int,
        probeMethod: String,
        includeCandidateProbeInbounds: Boolean = false
    ) {
        val primary = pool.firstOrNull()
            ?: throw IllegalStateException("Cannot start Xray without a proxy")
        val jsonConfig = XrayConfigBuilder.buildJsonConfig(
            config = primary,
            proxyPool = pool,
            probeUrl = probeUrl,
            observatoryIntervalSeconds = observatoryIntervalSeconds,
            probeTimeoutSeconds = probeTimeoutSeconds,
            probeMethod = probeMethod,
            includeCandidateProbeInbounds = includeCandidateProbeInbounds,
            socksUsername = LocalProxyAuth.credentials(this).username,
            socksPassword = LocalProxyAuth.credentials(this).password
        )
        val configFile = File(filesDir, "xray_config.json")
        configFile.writeText(jsonConfig)

        val runRequestJson = JSONObject().apply {
            put("apiVersion", 1)
            put("method", "runXray")
            put("payload", JSONObject().put("configPath", configFile.absolutePath))
        }.toString()

        AppLogger.d(TAG, "Starting Xray with ${pool.size} candidate proxies")
        val result = LibXray.invoke(runRequestJson)
        val response = JSONObject(result)
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(
                response.optString("error").ifBlank { "LibXray did not start Xray core" }
            )
        }
        xrayStarted = true
        _connectedServer.value = primary
        if (_vpnState.value == VpnState.CONNECTED) {
            refreshForegroundNotification("Подключено · ${primary.remark}")
            refreshQuickSettingsTile()
        }
    }

    private fun prepareCandidateProbeRoutes(candidates: List<VlessConfig>): Map<String, Int> {
        val primary = currentConfig ?: _connectedServer.value
            ?: throw IllegalStateException("No active proxy to preserve during candidate testing")
        if (!xrayStarted || vpnInterface == null || !tunnelStarted) {
            throw IllegalStateException("Active Xray tunnel is not ready for candidate testing")
        }

        val probePool = (listOf(primary) + candidates).distinctBy { it.rawUri }
        val allPorts = probePool.mapIndexed { index, config ->
            config.rawUri to (XrayConfigBuilder.CANDIDATE_PROBE_PORT_BASE + index)
        }.toMap()

        stopXrayEngine()
        try {
            startXrayEngine(
                pool = probePool,
                probeUrl = activeProbeUrl,
                observatoryIntervalSeconds = activeObservatoryIntervalSeconds,
                probeTimeoutSeconds = activeProbeTimeoutSeconds,
                probeMethod = activeProbeMethod,
                includeCandidateProbeInbounds = true
            )
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Could not start candidate-probe routes; restoring active proxy", e)
            stopXrayEngine()
            try {
                startXrayEngine(
                    pool = listOf(primary),
                    probeUrl = activeProbeUrl,
                    observatoryIntervalSeconds = activeObservatoryIntervalSeconds,
                    probeTimeoutSeconds = activeProbeTimeoutSeconds,
                    probeMethod = activeProbeMethod
                )
            } catch (restoreError: Throwable) {
                AppLogger.e(TAG, "Could not restore active proxy after candidate-probe setup failed", restoreError)
                stopVpnInternal()
            }
            throw e
        }

        return candidates.associate { candidate ->
            candidate.rawUri to (allPorts[candidate.rawUri]
                ?: throw IllegalStateException("No candidate SOCKS port for ${candidate.remark}"))
        }
    }

    private fun stopXrayEngine() {
        if (!xrayStarted) return
        try {
            val stopRequestJson = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "stopXray")
                put("payload", JSONObject())
            }.toString()
            val result = LibXray.invoke(stopRequestJson)
            AppLogger.d(TAG, "LibXray stopXray result: $result")
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Error stopping LibXray: ${e.message}")
        } finally {
            xrayStarted = false
        }
    }

    private fun updateProxyPoolInternal() {
        val newPool = pendingProxyPool
        if (newPool.isEmpty()) return

        if (vpnInterface == null || !tunnelStarted || !xrayStarted) {
            activeProxyPool = newPool
            activeProbeUrl = pendingProbeUrl
            activeObservatoryIntervalSeconds = pendingObservatoryIntervalSeconds
            activeProbeTimeoutSeconds = pendingProbeTimeoutSeconds
            activeProbeMethod = pendingProbeMethod
            currentConfig = newPool.first()
            return
        }

        val oldPool = activeProxyPool
        val oldProbeUrl = activeProbeUrl
        val oldInterval = activeObservatoryIntervalSeconds
        val oldTimeout = activeProbeTimeoutSeconds
        val oldMethod = activeProbeMethod
        val oldCurrentConfig = currentConfig

        try {
            stopXrayEngine()
            startXrayEngine(
                newPool,
                pendingProbeUrl,
                pendingObservatoryIntervalSeconds,
                pendingProbeTimeoutSeconds,
                pendingProbeMethod
            )
            activeProxyPool = newPool
            activeProbeUrl = pendingProbeUrl
            activeObservatoryIntervalSeconds = pendingObservatoryIntervalSeconds
            activeProbeTimeoutSeconds = pendingProbeTimeoutSeconds
            activeProbeMethod = pendingProbeMethod
            currentConfig = newPool.first()
            _connectedServer.value = currentConfig
            refreshForegroundNotification("Подключено · ${newPool.first().remark}")
            refreshQuickSettingsTile()
            AppLogger.d(TAG, "Switched selected proxy to ${newPool.first().remark}")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Could not apply refreshed proxy pool; restoring previous pool", e)
            stopXrayEngine()
            try {
                startXrayEngine(oldPool, oldProbeUrl, oldInterval, oldTimeout, oldMethod)
                activeProxyPool = oldPool
                activeProbeUrl = oldProbeUrl
                activeObservatoryIntervalSeconds = oldInterval
                activeProbeTimeoutSeconds = oldTimeout
                activeProbeMethod = oldMethod
                currentConfig = oldCurrentConfig
                _connectedServer.value = oldCurrentConfig
            } catch (restoreError: Throwable) {
                AppLogger.e(TAG, "Could not restore previous Xray config", restoreError)
                stopVpnInternal()
            }
        }
    }

    private fun stopVpnInternal() {
        AppLogger.d(TAG, "Stopping VPN")
        if (tunnelStarted) {
            try {
                TProxyService.TProxyStopService()
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Error stopping HEV tun2socks: ${e.message}")
            } finally {
                tunnelStarted = false
            }
        }

        stopXrayEngine()

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Error closing vpnInterface: ${e.message}")
        }

        activeProxyPool = emptyList()
        _connectedServer.value = null
        _vpnState.value = VpnState.DISCONNECTED
        refreshQuickSettingsTile()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(1)
        stopForeground(true)
        instance = null
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    "netbox_vpn_channel",
                    "NetBox VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Состояние VPN-подключения NetBox"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildForegroundNotification(message: String): Notification {
        val notificationsEnabled = SettingsStore(this).notificationsEnabled
        val visibleMessage = if (notificationsEnabled) message else "VPN-сервис работает"
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "netbox_vpn_channel")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("NetBox VPN")
            .setContentText(visibleMessage)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundNotification(message: String) {
        startForeground(1, buildForegroundNotification(message))
    }

    private fun refreshForegroundNotification(message: String = run {
        val state = _vpnState.value
        val config = _connectedServer.value ?: currentConfig
        when (state) {
            VpnState.CONNECTING -> "Подключение..."
            VpnState.CONNECTED -> "Подключено" + (config?.remark?.let { " · $it" } ?: "")
            VpnState.DISCONNECTED -> "Отключено"
        }
    }) {
        if (vpnInterface == null && _vpnState.value == VpnState.DISCONNECTED) return
        ensureNotificationChannel()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildForegroundNotification(message))
    }

    private fun refreshQuickSettingsTile() {
        try {
            NetBoxTileService.requestUpdate(this)
        } catch (e: Exception) {
            AppLogger.d(TAG, "Could not update Quick Settings tile: ${e.message}")
        }
    }

    override fun onDestroy() {
        stopVpnInternal()
        instance = null
        super.onDestroy()
    }
}
