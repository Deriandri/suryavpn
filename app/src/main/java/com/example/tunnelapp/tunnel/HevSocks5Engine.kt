package com.example.tunnelapp.tunnel

import android.util.Log

/**
 * [TunEngine] berbasis hev-socks5-tunnel (heiher/hev-socks5-tunnel, native C,
 * bundling lwIP internal sebagai stack TCP/IP-nya -- lihat
 * `app/src/main/cpp/CMakeLists.txt`). Ini SATU-SATUNYA engine yang punya
 * binary ter-compile & teruji jalan di app ini per hari ini.
 *
 * `HevSocks5Bridge.startTunnel()` sendiri BLOCKING (tidak return sampai
 * `stopTunnel()` dipanggil), jadi dijalankan di thread `Thread` terpisah di
 * sini -- caller ([MyVpnService]) cukup panggil [start] dari coroutine biasa,
 * tidak perlu tahu detail threading-nya.
 */
class HevSocks5Engine : TunEngine {

    companion object {
        private const val TAG = "HevSocks5Engine"
    }

    private var thread: Thread? = null

    /**
     * PENTING: dicek di dalam thread native sebelum memanggil [onUnexpectedStop].
     * Kalau true, berarti [stop] memang sengaja dipanggil (user disconnect,
     * atau MyVpnService lagi bikin ulang tunnel buat reconnect) -- BUKAN
     * kematian tak terduga, jadi callback TIDAK dipanggil.
     */
    @Volatile
    private var stoppedByUs = false

    override fun start(tunFd: Int, tunAddress: String, mtu: Int, socksHost: String, socksPort: Int, onUnexpectedStop: (() -> Unit)?) {
        stoppedByUs = false
        val yamlConfig = """
            tunnel:
              name: tun0
              mtu: $mtu
              ipv4: $tunAddress
            socks5:
              address: $socksHost
              port: $socksPort
              udp: 'udp'
            misc:
              log-level: warn
              log-file: stdout
        """.trimIndent()

        thread = Thread({
            val result = HevSocks5Bridge.startTunnel(yamlConfig, tunFd)
            Log.i(TAG, "hev-socks5-tunnel berhenti, kode: $result")
            if (!stoppedByUs) {
                // Engine berhenti sendiri (bukan diminta stop()) -- native lib exit
                // atau socket ke SOCKS5 lokal putus fatal. Ini persis kondisi
                // "tunnel mati sendiri" yang mau dideteksi.
                Log.w(TAG, "hev-socks5-tunnel berhenti TAK TERDUGA (kode $result)")
                onUnexpectedStop?.invoke()
            }
        }, "hev-socks5-tunnel").apply { start() }
    }

    override fun stop() {
        stoppedByUs = true
        val t = thread ?: return
        HevSocks5Bridge.stopTunnel()
        try {
            t.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted saat menunggu hev-tunnel berhenti", e)
        }
        thread = null
    }
}
