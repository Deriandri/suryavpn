package com.example.tunnelapp.tunnel

/**
 * Abstraksi engine yang membaca paket dari TUN fd lalu meneruskannya sebagai
 * koneksi SOCKS5 ke `127.0.0.1:<socksPort>`. Satu-satunya implementasi yang
 * dipakai app ini adalah [HevSocks5Engine] (hev-socks5-tunnel). Kontraknya:
 *
 *  - [start] BOLEH blocking (dipanggil dari thread terpisah oleh caller,
 *    lihat [MyVpnService.startTunEngine]) ATAU non-blocking (spawn thread
 *    sendiri secara internal) -- keduanya valid, caller cuma peduli [start]
 *    kembali setelah engine benar-benar siap ATAU melempar exception kalau
 *    gagal start.
 *  - [stop] harus aman dipanggil kapan pun, termasuk sebelum [start] pernah
 *    dipanggil (no-op), dan harus benar-benar menghentikan thread/loop
 *    internal (bukan cuma menutup fd) supaya [MyVpnService.stopVpn] bisa
 *    `join()` dengan aman.
 *  - TIDAK bertanggung jawab menutup [tunFd] itu sendiri -- itu tetap
 *    tanggung jawab [MyVpnService] (pemilik `ParcelFileDescriptor`).
 */
interface TunEngine {
    /**
     * @param tunFd file descriptor TUN interface (dari `VpnService.Builder().establish()`)
     * @param tunAddress alamat IPv4 lokal TUN interface (mis. "10.10.0.2")
     * @param mtu MTU TUN interface
     * @param socksHost host SOCKS5 tujuan, selalu "127.0.0.1" di app ini
     * @param socksPort port SOCKS5 tujuan -- disediakan oleh SshTunnelManager
     *                  ATAU XrayTunnelManager tergantung [com.example.tunnelapp.model.ServerConfig.mode]
     */
    @Throws(Exception::class)
    fun start(tunFd: Int, tunAddress: String, mtu: Int, socksHost: String, socksPort: Int)

    /** Hentikan engine yang sedang berjalan. Aman dipanggil berkali-kali / sebelum start(). */
    fun stop()
}
