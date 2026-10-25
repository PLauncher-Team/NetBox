package hev.socks5.tunnel;

public class HevSocks5Tunnel {
    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (Throwable e) {
            android.util.Log.e("NetBoxLog-JNI", "Failed to load hev-socks5-tunnel: " + e.getMessage(), e);
        }
    }

    public static native void start(String configPath, int tunFd);
    public static native void stop();
    public static native boolean stats(long[] rx, long[] tx);
}
