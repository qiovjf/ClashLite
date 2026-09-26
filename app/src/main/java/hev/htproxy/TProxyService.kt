package hev.htproxy

/**
 * hev-socks5-tunnel 的 JNI 绑定（与预编译 so 的默认 JNI 契约保持一致）。
 * 在应用进程内直接接管 VpnService 的 TUN fd，无需子进程。
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
