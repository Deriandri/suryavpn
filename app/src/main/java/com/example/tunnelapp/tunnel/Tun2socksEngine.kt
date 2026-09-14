package com.example.tunnelapp.tunnel

import android.util.Log
import com.xjasonlyu.tun2socks.mobile.Mobile

/**
 * [TunEngine] berbasis xjasonlyu/tun2socks (Go, stack TCP/IP dari gvisor/netstack),
 * lewat binding gomobile `app/libs/tun2socks.aar`. Engine KEDUA di app ini,
 * alternatif dari [HevSocks5Engine] (default) -- dipilih user lewat toggle
 * "Tunnel Engine (TUN)" di kartu VPN Setting (lihat
 * [com.example.tunnelapp.model.VpnSettings.tunEngine]).
 *
 * API native yang di-expose AAR ini (class `com.xjasonlyu.tun2socks.mobile.Mobile`,
 * dibongkar dari classes.jar karena tidak ada dokumentasi header resmi ikut
 * di dalam .aar):
 *  - `public static native void startTun2Socks(long fd, String proxy, long mtu)`
 *  - `public static native void stopTun2Socks()`
 *  - `public static native boolean isRunning()`
 *
 * Berbeda dari `HevSocks5Bridge.startTunnel()` ([HevSocks5Engine]) yang
 * BLOCKING, `startTun2Socks` di sini me-launch tunnel-nya secara internal
 * (goroutine Go di sisi native) lalu LANGSUNG return -- konsisten dengan
 * adanya `isRunning()`/`stopTun2Socks()` terpisah (kalau blocking, dua
 * fungsi itu tidak akan pernah kepanggil selama tunnel jalan). Karena AAR
 * ini TIDAK menyediakan callback native->Java untuk "tunnel mati sendiri"
 * (beda dari hev-socks5-tunnel yang exit code-nya bisa ditangkap di thread
 * pemanggil), kontrak [TunEngine.onUnexpectedStop] di sini diimplementasikan
 * dengan POLLING [Mobile.isRunning] tiap [POLL_INTERVAL_MS] di thread
 * terpisah -- lihat [monitorThread].
 *
 * `proxy` diberikan sebagai URI `socks5://host:port` -- lihat string
 * `socks5://%s` yang ditemukan di `libgojni.so` saat verifikasi API ini.
 */
class Tun2socksEngine : TunEngine {

    companion object {
        private const val TAG = "Tun2socksEngine"
        private const val POLL_INTERVAL_MS = 1000L
    }

    private var monitorThread: Thread? = null

    /**
     * Sama seperti [HevSocks5Engine.stoppedByUs]: dicek di [monitorThread]
     * sebelum memanggil [onUnexpectedStop]/menandai engine berhenti tak
     * terduga -- true berarti [stop] memang sengaja dipanggil.
     */
    @Volatile
    private var stoppedByUs = false

    @Volatile
    private var started = false

    override fun start(
        tunFd: Int,
        tunAddress: String,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        onUnexpectedStop: (() -> Unit)?
    ) {
        stoppedByUs = false
        val proxy = "socks5://$socksHost:$socksPort"

        // Panggilan ini TIDAK blocking (lihat catatan kelas di atas) --
        // exception di sisi Go (mis. "empty proxy", "tun2socks is already
        // running") dilempar balik ke Java lewat mekanisme error gomobile,
        // ditangkap sebagai Exception biasa oleh kontrak [TunEngine.start].
        Mobile.startTun2Socks(tunFd.toLong(), proxy, mtu.toLong())
        started = true

        monitorThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    if (!Mobile.isRunning()) {
                        if (!stoppedByUs) {
                            Log.w(TAG, "tun2socks berhenti TAK TERDUGA")
                            onUnexpectedStop?.invoke()
                        }
                        return@Thread
                    }
                }
            } catch (e: InterruptedException) {
                // stop() memanggil interrupt() sebagai bagian dari teardown normal.
            }
        }, "tun2socks-monitor").apply { start() }
    }

    override fun stop() {
        stoppedByUs = true
        monitorThread?.interrupt()
        monitorThread = null
        if (started) {
            try {
                Mobile.stopTun2Socks()
            } catch (e: Exception) {
                Log.w(TAG, "Error saat stopTun2Socks()", e)
            }
            started = false
        }
    }
}
