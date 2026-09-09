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

    override fun start(tunFd: Int, tunAddress: String, mtu: Int, socksHost: String, socksPort: Int) {
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
        }, "hev-socks5-tunnel").apply { start() }
    }

    override fun stop() {
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
