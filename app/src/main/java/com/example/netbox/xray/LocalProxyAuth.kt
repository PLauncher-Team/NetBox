package com.example.netbox.xray

import android.content.Context
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.SecureRandom

data class LocalSocksCredentials(
    val username: String,
    val password: String
)

/**
 * Credentials are generated locally once per installation. They protect loopback proxy
 * listeners from other apps on the device; they are not intended as remote credentials.
 */
object LocalProxyAuth {
    const val SOCKS_PORT = 10808
    private const val PREFS_NAME = "netbox_local_proxy_auth"
    private const val USERNAME_KEY = "username"
    private const val PASSWORD_KEY = "password"
    private val secureRandom = SecureRandom()
    @Volatile private var authenticatorInstalled = false

    @Synchronized
    fun credentials(context: Context): LocalSocksCredentials {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var username = prefs.getString(USERNAME_KEY, null)
        var password = prefs.getString(PASSWORD_KEY, null)
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            username = "nb" + randomHex(8)
            password = randomHex(24)
            prefs.edit()
                .putString(USERNAME_KEY, username)
                .putString(PASSWORD_KEY, password)
                .commit()
        }
        return LocalSocksCredentials(username, password)
    }

    fun installUrlAuthenticator(context: Context) {
        if (authenticatorInstalled) return
        synchronized(this) {
            if (authenticatorInstalled) return
            val localCredentials = credentials(context)
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    val host = requestingHost
                    val isLoopback = host == "127.0.0.1" ||
                        host?.equals("localhost", ignoreCase = true) == true ||
                        host == "::1"
                    // Android may report SOCKS authentication as either PROXY or SERVER
                    // depending on the socket implementation. Restrict by local host and port.
                    if (requestingPort == SOCKS_PORT && isLoopback) {
                        return PasswordAuthentication(
                            localCredentials.username,
                            localCredentials.password.toCharArray()
                        )
                    }
                    return null
                }
            })
            authenticatorInstalled = true
        }
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
