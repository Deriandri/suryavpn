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
        // FITUR BARU (permintaan user: maksimalkan kecepatan jaringan) --
        // sudah diverifikasi lewat README resmi heiher/hev-socks5-tunnel
        // (bukan tebakan) karena project ini build LANGSUNG dari branch
        // `main` GitHub-nya (lihat CMakeLists.txt), jadi salah nama key
        // beresiko besar ke seluruh tunnel:
        //  - misc.tcp-buffer-size dinaikkan dari default -> 65536 -- ini
        //    versi "socket buffer tuning" tapi di sisi stack TCP/IP virtual
        //    (lwIP) milik hev sendiri, yang menangani sisi TUN<->socks5.
        //    Sama alasannya dengan SOCKET_BUFFER_SIZE_BYTES di ConnectRelay.kt.
        //  - SENGAJA TIDAK mengaktifkan tunnel.multi-queue: true -- fitur
        //    ini didesain untuk beberapa fd TUN paralel, sedangkan integrasi
        //    VpnService Android di app ini cuma membuka SATU fd
        //    (ParcelFileDescriptor dari establish()) -- mengaktifkannya
        //    tanpa multi-fd kemungkinan besar tidak berefek atau malah
        //    bikin hev gagal start, bukan bikin lebih cepat.
        //  - SENGAJA TIDAK mengaktifkan socks5.pipeline: true -- ini varian
        //    handshake SOCKS5 yang menggabungkan beberapa langkah jadi lebih
        //    sedikit round-trip, TAPI Socks5Server.kt kita adalah
        //    implementasi SOCKS5 minimal buatan sendiri yang belum tentu
        //    mendukung varian pipeline ini -- resiko merusak handshake demi
        //    keuntungan kecepatan yang nyaris nol (toh ini localhost,
        //    127.0.0.1, RTT-nya sudah praktis 0 dari awal).
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
              tcp-buffer-size: 65536
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
