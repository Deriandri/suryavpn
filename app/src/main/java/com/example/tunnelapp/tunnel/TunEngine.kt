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
     * @param socksPort port SOCKS5 tujuan -- disediakan oleh SshjTunnelManager
     *                  ATAU XrayTunnelManager tergantung [com.example.tunnelapp.model.ServerConfig.mode]
     * @param onUnexpectedStop dipanggil (dari thread APAPUN, implementasi caller wajib thread-safe)
     *   kalau engine berhenti SENDIRI di tengah jalan (native lib exit, socket
     *   fatal error, dll) -- BUKAN karena [stop] dipanggil. Dipakai [MyVpnService]
     *   untuk mendeteksi "tunnel mati sendiri" dan memicu reconnect otomatis.
     *   Null = tidak ada yang peduli (dipakai test/pemanggil lama).
     */
    @Throws(Exception::class)
    fun start(
        tunFd: Int,
        tunAddress: String,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        onUnexpectedStop: (() -> Unit)? = null
    )

    /** Hentikan engine yang sedang berjalan. Aman dipanggil berkali-kali / sebelum start(). */
    fun stop()

    /**
     * True kalau engine ini SAAT INI masih benar-benar berjalan (thread
     * native/wrapper-nya masih hidup) -- dipakai [MyVpnService] untuk
     * memutuskan apakah engine perlu di-restart saat reconnect, atau boleh
     * dibiarkan hidup apa adanya (TUN fd & port SOCKS5 tidak berubah
     * selama reconnect RINGAN, jadi engine yang masih sehat TIDAK PERNAH
     * perlu ikut direstart -- lihat catatan panjang di
     * MyVpnService.handleTunnelDeath()/establishTunnel()).
     */
    val isAlive: Boolean
}
