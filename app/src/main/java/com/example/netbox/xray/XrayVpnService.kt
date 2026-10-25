package com.example.netbox.xray

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.v2ray.ang.service.TProxyService
import libXray.LibXray
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
    private var activeProbeUrl = "https://one.one.one.one"
    private var activeObservatoryIntervalSeconds = 30
    private var activeProbeTimeoutSeconds = 1
    private var activeProbeMethod = "GET"

    companion object {
        private const val TAG = "NetBoxLog-Service"
        private const val DEFAULT_PROBE_URL = "https://one.one.one.one"

        var instance: XrayVpnService? = null

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _connectedServer = MutableStateFlow<VlessConfig?>(null)
        val connectedServer: StateFlow<VlessConfig?> = _connectedServer.asStateFlow()

        var currentConfig: VlessConfig? = null
        @Volatile private var pendingProxyPool: List<VlessConfig> = emptyList()
        @Volatile private var pendingProbeUrl: String = DEFAULT_PROBE_URL
        @Volatile private var pendingObservatoryIntervalSeconds: Int = 30
        @Volatile private var pendingProbeTimeoutSeconds: Int = 1
        @Volatile private var pendingProbeMethod: String = "GET"

        fun startVpn(
            context: Context,
            configs: List<VlessConfig>,
            observatoryIntervalSeconds: Int = 30,
            probeTimeoutSeconds: Int = 1,
            probeUrl: String = DEFAULT_PROBE_URL,
            probeMethod: String = "GET"
        ) {
            val pool = configs.distinctBy { it.rawUri }
                .sortedWith(compareBy<VlessConfig> { if (it.pingMs >= 0) 0 else 1 }.thenBy { it.pingMs })
            if (pool.isEmpty()) return

            pendingProxyPool = pool
            pendingProbeUrl = probeUrl.ifBlank { DEFAULT_PROBE_URL }
            pendingObservatoryIntervalSeconds = observatoryIntervalSeconds.coerceAtLeast(10)
            pendingProbeTimeoutSeconds = probeTimeoutSeconds.coerceAtLeast(1)
            pendingProbeMethod = probeMethod.takeIf { it.equals("HEAD", ignoreCase = true) }?.uppercase() ?: "GET"
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
            probeMethod: String = "GET"
        ) {
            val pool = configs.distinctBy { it.rawUri }
                .sortedWith(compareBy<VlessConfig> { if (it.pingMs >= 0) 0 else 1 }.thenBy { it.pingMs })
            if (pool.isEmpty()) return

            pendingProxyPool = pool
            pendingProbeUrl = probeUrl.ifBlank { DEFAULT_PROBE_URL }
            pendingObservatoryIntervalSeconds = observatoryIntervalSeconds.coerceAtLeast(10)
            pendingProbeTimeoutSeconds = probeTimeoutSeconds.coerceAtLeast(1)
            pendingProbeMethod = probeMethod.takeIf { it.equals("HEAD", ignoreCase = true) }?.uppercase() ?: "GET"

            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = "UPDATE_POOL"
            }
            context.startService(intent)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = "netbox_vpn_channel"
                val channel = NotificationChannel(
                    channelId,
                    "NetBox VPN Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)

                val notification = Notification.Builder(this, channelId)
                    .setContentTitle("NetBox VPN")
                    .setContentText("Подключение к ${config.remark}...")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .build()

                startForeground(1, notification)
            }

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

            val hevConfigFile = File(filesDir, "hev-socks5-tunnel.yaml")
            hevConfigFile.writeText(
                """
                tunnel:
                  mtu: 8500
                  ipv4: 10.0.0.2
                socks5:
                  port: 10808
                  address: 127.0.0.1
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
            AppLogger.d(TAG, "VPN started with ${activeProxyPool.size} proxies in the Observatory pool")
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
        probeMethod: String
    ) {
        val primary = pool.firstOrNull()
            ?: throw IllegalStateException("Cannot start Xray without a proxy")
        val jsonConfig = XrayConfigBuilder.buildJsonConfig(
            config = primary,
            proxyPool = pool,
            probeUrl = probeUrl,
            observatoryIntervalSeconds = observatoryIntervalSeconds,
            probeTimeoutSeconds = probeTimeoutSeconds,
            probeMethod = probeMethod
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

        // Service may still be coming up. Save the desired pool; START will use it.
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
            AppLogger.d(TAG, "Updated Observatory pool to ${newPool.size} proxies")
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
        instance = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpnInternal()
        instance = null
        super.onDestroy()
    }
}
