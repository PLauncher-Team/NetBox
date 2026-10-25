package com.v2ray.ang.service

/**
 * JNI bridge expected by the bundled hev-socks5-tunnel native library.
 *
 * Keep this fully-qualified class name and native method signatures aligned
 * with the library's JNI registration contract.
 */
object TProxyService {
    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray?

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
