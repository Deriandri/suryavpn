package com.example.tunnelapp.tunnel

/**
 * Jembatan JNI ke pustaka native hev-socks5-tunnel.
 *
 * startTunnel() BLOCKING (tidak return sampai stopTunnel() dipanggil atau
 * terjadi error) -> WAJIB dipanggil dari thread terpisah, jangan dari
 * main thread ataupun langsung di coroutine yang sama dengan kerjaan lain.
 */
object HevSocks5Bridge {
    init {
        System.loadLibrary("tunneljni")
    }

    /**
     * @param configYaml konfigurasi lengkap dalam format YAML hev-socks5-tunnel
     * @param tunFd file descriptor TUN interface (dari VpnService.Builder().establish())
     * @return 0 jika sukses, -1 jika gagal (lihat log native untuk detail)
     */
    external fun startTunnel(configYaml: String, tunFd: Int): Int

    /** Menghentikan tunnel yang sedang berjalan (unblock startTunnel()). */
    external fun stopTunnel()
}
