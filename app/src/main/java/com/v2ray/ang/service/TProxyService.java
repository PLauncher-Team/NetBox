package com.v2ray.ang.service;

/**
 * JNI bridge expected by the bundled hev-socks5-tunnel native library.
 * Keep this fully-qualified class name and method signatures aligned with
 * the library's JNI registration contract.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
