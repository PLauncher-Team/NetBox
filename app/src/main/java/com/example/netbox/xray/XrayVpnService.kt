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

    companion object {
        private const val TAG = "NetBoxLog-Service"
        var instance: XrayVpnService? = null

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _connectedServer = MutableStateFlow<VlessConfig?>(null)
        val connectedServer: StateFlow<VlessConfig?> = _connectedServer.asStateFlow()

        var currentConfig: VlessConfig? = null

        fun startVpn(context: Context, config: VlessConfig) {
            AppLogger.d(TAG, "startVpn called for remark: ${config.remark}, address: ${config.address}:${config.port}")
            currentConfig = config
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = "START"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
        // Do not initialize native code here. Initialize it at connection time so
        // startup failures can be reported and cleaned up without crashing the service.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val action = intent?.action
        AppLogger.d(TAG, "onStartCommand received action: $action")
        when (action) {
            "START" -> startVpnInternal()
            "STOP" -> stopVpnInternal()
        }
        return START_NOT_STICKY
    }

    private fun startVpnInternal() {
        val config = currentConfig
        if (config == null) {
            AppLogger.e(TAG, "startVpnInternal aborted: currentConfig is null")
            return
        }

        AppLogger.d(TAG, "=== STARTING VPN INTERNAL ===")
        AppLogger.d(TAG, "Target Config: ${config.remark} (${config.address}:${config.port}), Protocol: vless, Security: ${config.params["security"]}")
        _vpnState.value = VpnState.CONNECTING
        instance = this

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
                AppLogger.d(TAG, "Foreground notification started (Android 8+ requirement met)")
            }

            // 1. Build JSON & Run Xray Core via official LibXray library
            AppLogger.d(TAG, "Building Xray JSON config...")
            val jsonConfig = XrayConfigBuilder.buildJsonConfig(config)
            AppLogger.d(TAG, "Xray JSON Config Generated:\n$jsonConfig")

            // Maven Central currently provides LibXray 26.7.28, whose
            // structured request API is v1 and reads the config from configPath.
            val configFile = File(filesDir, "xray_config.json")
            configFile.writeText(jsonConfig)
            val runRequestJson = JSONObject().apply {
                put("apiVersion", 1)
                put("method", "runXray")
                put("payload", JSONObject().put("configPath", configFile.absolutePath))
            }.toString()

            AppLogger.d(TAG, "Executing LibXray.invoke(runXray)...")
            val result = LibXray.invoke(runRequestJson)
            AppLogger.d(TAG, "LibXray.invoke result: $result")
            val response = JSONObject(result)
            if (!response.optBoolean("success", false)) {
                throw IllegalStateException(
                    response.optString("error").ifBlank { "LibXray did not start Xray core" }
                )
            }
            xrayStarted = true

            // 2. Establish VPN TUN Interface
            AppLogger.d(TAG, "Establishing VpnService.Builder TUN interface...")
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
                AppLogger.e(TAG, "CRITICAL: VpnService.Builder.establish() returned NULL!")
                stopVpnInternal()
                return
            }
            vpnInterface = pfd
            AppLogger.d(TAG, "TUN interface established successfully with FD: ${pfd.fd}")

            // 3. Bridge TUN packets to Xray's local SOCKS5 inbound. The native
            // library resolves this exact TProxyService class during JNI_OnLoad.
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

            AppLogger.d(TAG, "Starting HEV tunnel on FD ${pfd.fd}...")
            // Mark it before the native call so cleanup also attempts to stop a
            // partially initialized tunnel if the JNI entry point fails midway.
            tunnelStarted = true
            val tunnelOk = TProxyService.TProxyStartService(hevConfigFile.absolutePath, pfd.fd)
            if (!tunnelOk) {
                throw IllegalStateException("HEV tun2socks native service failed to start")
            }
            if (!TProxyService.TProxyIsRunning()) {
                throw IllegalStateException("HEV tun2socks reports that it is not running")
            }

            _connectedServer.value = config
            _vpnState.value = VpnState.CONNECTED
            AppLogger.d(TAG, "=== VPN STARTED & CONNECTED SUCCESSFULLY VIA TUN2SOCKS ===")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "CRITICAL: Failed to start VPN internal: ${e.message}", e)
            stopVpnInternal()
        }
    }

    private fun stopVpnInternal() {
        AppLogger.d(TAG, "=== STOPPING VPN INTERNAL ===")
        if (tunnelStarted) {
            try {
                AppLogger.d(TAG, "Stopping HEV tun2socks...")
                TProxyService.TProxyStopService()
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Error stopping HEV tun2socks: ${e.message}")
            } finally {
                tunnelStarted = false
            }
        }

        if (xrayStarted) {
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

        try {
            AppLogger.d(TAG, "Closing TUN ParcelFileDescriptor...")
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Error closing vpnInterface: ${e.message}")
        }

        _connectedServer.value = null
        _vpnState.value = VpnState.DISCONNECTED
        instance = null
        stopSelf()
        AppLogger.d(TAG, "=== VPN STOPPED ===")
    }

    override fun onDestroy() {
        AppLogger.d(TAG, "onDestroy called")
        stopVpnInternal()
        instance = null
        super.onDestroy()
    }
}
