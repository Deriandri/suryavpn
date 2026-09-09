package com.example.tunnelapp.model

/**
 * Metode koneksi yang didukung.
 *
 *  - SSH:             TCP langsung ke server, protokol SSH mentah (tanpa bungkus apa pun).
 *  - SSH_SSL:         TCP langsung + TLS wrap (mirip stunnel) sebelum SSH dimulai.
 *  - SSH_SSL_PAYLOAD: sama seperti SSH_SSL, ditambah payload HTTP custom yang
 *                      dikirim lewat socket TLS sebelum SSH dimulai (dulu disebut
 *                      "SSH SSL + Payload" di UI, sekarang "SSH TLS Payload Proxy").
 *                      Sekarang juga bisa lewat HTTP proxy dulu (opsional, kalau host
 *                      proxy diisi) dan bisa memaksa versi TLS tertentu (opsional,
 *                      lihat [ServerConfig.tlsVersion]).
 *  - REMOTE_PROXY:    "Payload + Remote Proxy" di UI. Proxy WAJIB diisi (beda dari
 *                      SSH_SSL_PAYLOAD yang proxy-nya opsional), payload custom
 *                      opsional, dan TLS TIDAK otomatis aktif -- dua varian, sama
 *                      seperti ENHANCED, dikontrol oleh [ServerConfig.proxyRawMode]:
 *                        * proxyRawMode=false (default): proxy HTTP CONNECT biasa
 *                          ke proxyHost, TANPA TLS -- payload (kalau diisi) dikirim
 *                          plaintext lewat tunnel hasil CONNECT itu langsung ke
 *                          server SSH asli.
 *                        * proxyRawMode=true: raw passthrough ke proxyHost:proxyPort
 *                          (tanpa CONNECT) + TLS dengan SNI = [ServerConfig.sslSni]
 *                          (atau host asli), sama seperti varian raw di ENHANCED --
 *                          dipakai kalau proxyHost sebenarnya CDN/reverse-proxy yang
 *                          butuh SNI buat routing ke origin yang benar.
 *  - ENHANCED:        mode dengan proxy/CDN (WAJIB diisi kalau [ServerConfig.proxyRawMode]
 *                      aktif) + TLS (selalu aktif). Dua varian proxy, dikontrol
 *                      oleh [ServerConfig.proxyRawMode]:
 *                        * proxyRawMode=false (proxy HTTP klasik, mis. Squid): kirim HTTP
 *                          CONNECT ke proxyHost dulu, tunggu balasan "200", baru TLS+SSH
 *                          dijalankan di atas tunnel itu.
 *                        * proxyRawMode=true (reverse-proxy/CDN, mis. Cloudflare & sejenisnya,
 *                          default begitu chip ini dipilih): TCP connect langsung ke
 *                          proxyHost:proxyPort (tanpa CONNECT -- CDN tidak paham semantik
 *                          itu), lalu TLS dengan SNI = [ServerConfig.sslSni] (atau host asli)
 *                          supaya CDN bisa routing berbasis SNI ke origin yang benar.
 *  - XRAY:            mode terpisah dari jalur SSH di atas -- TIDAK dikonek pakai
 *                      trilead-ssh2 sama sekali. Server diisi lewat satu link
 *                      share ([ServerConfig.xrayLink], format vmess://, vless://,
 *                      atau trojan://) yang di-parse jadi konfigurasi Xray-core
 *                      lalu dijalankan lewat XrayTunnelManager (binding libXray/
 *                      Xray-core resmi, lihat XrayTunnelManager.kt). Xray-core
 *                      dijalankan dengan SATU inbound SOCKS5 di 127.0.0.1:[socksPort]
 *                      -- port yang SAMA yang dipakai hev-socks5-tunnel buat
 *                      menjembatani TUN, jadi pipeline TUN->SOCKS5 di MyVpnService
 *                      tidak berubah sama sekali, cuma "penyedia" SOCKS5-nya yang
 *                      beda (Xray-core, bukan SshTunnelManager+Socks5Server).
 *
 * CATATAN PENTING: WebSocket TIDAK ADA lagi togglenya di UI ataupun di [ConnectionMode] --
 * sekarang SELALU dicoba otomatis di SEMUA mode di atas (lihat [ServerConfig.usesWebSocket]),
 * dilakukan di atas socket hasil akhir mode yang dipilih (setelah proxy/TLS/payload, sebelum
 * SSH dimulai). Kalau server/CDN tujuan menerima upgrade-nya, SEMUA byte SSH sesudahnya
 * dibungkus jadi frame WebSocket biner asli (bukan cuma header palsu) -- supaya tetap valid
 * kalau lewat proxy/CDN yang benar-benar memvalidasi framing WebSocket. Kalau server/CDN
 * tujuan MENOLAK upgrade-nya (bukan endpoint WebSocket sama sekali), [ConnectRelay] otomatis
 * membuka ulang koneksi dari nol TANPA WebSocket (raw) dan tetap lanjut normal -- jadi metode
 * apa pun tetap bisa dipakai walau server/payload tujuannya sama sekali tidak menyinggung
 * WebSocket, tanpa perlu toggle manual apa pun.
 */
enum class ConnectionMode {
    SSH,
    SSH_SSL,
    SSH_SSL_PAYLOAD,
    REMOTE_PROXY,
    ENHANCED,
    XRAY
}

/**
 * Konfigurasi koneksi server SSH.
 *
 * @param mode metode koneksi, lihat [ConnectionMode]
 * @param sslSni SNI palsu untuk mode yang memakai TLS (opsional, kosongkan untuk pakai host asli)
 * @param payload template payload custom (opsional). Placeholder yang didukung:
 *                [host], [port], [crlf], [cr], [lf]
 *                Contoh: "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"
 * @param proxyHost host/IP proxy HTTP (wajib untuk mode REMOTE_PROXY, opsional untuk
 *                  SSH_SSL_PAYLOAD & ENHANCED)
 * @param proxyPort port proxy HTTP; kalau null memakai [port] yang sama dengan server SSH
 * @param tlsVersion versi TLS yang dipaksa dipakai saat handshake (opsional). Nilai valid:
 *                   "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3". Null/kosong/"Default" berarti
 *                   biarkan sistem yang pilih otomatis (perilaku lama, disarankan untuk
 *                   kebanyakan server modern -- opsi versi lama cuma untuk server SSH SSL
 *                   yang sudah tua dan cuma mendukung TLS versi lama).
 * @param useWebSocket toggle independen (lihat catatan di [ConnectionMode]): kalau true,
 *                   handshake WebSocket (RFC 6455) dilakukan di atas hasil akhir mode
 *                   yang dipilih, dikombinasikan dengan mode APA PUN -- bukan mode
 *                   tersendiri lagi.
 * @param wsPath     path HTTP yang dipakai saat request upgrade WebSocket (dipakai kalau
 *                   [useWebSocket] true), mis. "/ws" atau "/". Kosong/null berarti pakai "/".
 * @param proxyRawMode kalau true, [proxyHost]:[proxyPort] TIDAK diperlakukan sebagai proxy
 *                   HTTP asli -- tidak ada request/verifikasi CONNECT sama sekali. TCP
 *                   connect langsung ke proxyHost:proxyPort, lalu SNI TLS / Host header
 *                   WebSocket / payload sesudahnya tetap memakai [host] (atau [sslSni]) asli,
 *                   BUKAN proxyHost. Cocok kalau proxyHost sebenarnya cuma titik singgah
 *                   pass-through (host lain/CDN) yang tidak paham semantik HTTP CONNECT --
 *                   beda dengan proxy HTTP betulan yang butuh CONNECT dan balasan "200".
 *                   Kalau false (default), perilaku lama tetap dipakai: kirim CONNECT ke
 *                   proxyHost dan tunggu balasan "200" sebelum lanjut.
 * @param xrayLink   link share Xray (khusus mode [ConnectionMode.XRAY]), format
 *                   "vmess://...", "vless://...", atau "trojan://...". Diabaikan
 *                   di mode lain. Lihat [com.example.tunnelapp.tunnel.XrayLinkParser].
 */
data class ServerConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val socksPort: Int = 1080,
    val mode: ConnectionMode = ConnectionMode.SSH,
    val sslSni: String? = null,
    val payload: String? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val tlsVersion: String? = null,
    val useWebSocket: Boolean = false,
    val wsPath: String? = null,
    val proxyRawMode: Boolean = false,
    val xrayLink: String? = null
) {
    /**
     * Apakah koneksi harus lewat HTTP proxy (CONNECT) dulu sebelum mencapai host asli.
     * Selalu false untuk mode XRAY -- jalur proxy/CDN Xray (kalau ada) sudah diatur
     * sendiri di dalam link share-nya (mis. parameter `?host=`/`?path=` WebSocket),
     * bukan lewat field proxyHost/proxyPort punya jalur SSH.
     */
    fun usesProxy(): Boolean {
        if (mode == ConnectionMode.XRAY) return false
        val optionalProxyMode = mode == ConnectionMode.SSH_SSL_PAYLOAD ||
            mode == ConnectionMode.ENHANCED
        return mode == ConnectionMode.REMOTE_PROXY || (optionalProxyMode && !proxyHost.isNullOrBlank())
    }

    /**
     * Apakah koneksi harus dibungkus TLS sebelum SSH/handshake WebSocket dimulai.
     *
     * REMOTE_PROXY + proxyRawMode TIDAK ikut menghasilkan true -- di mode ini Raw
     * Passthrough berarti TCP polos ke proxyHost:proxyPort tanpa TLS/SNI sama sekali
     * (payload custom dikirim plaintext langsung). Selalu false untuk mode XRAY --
     * TLS (kalau dipakai) sudah ditangani di dalam Xray-core sendiri berdasarkan
     * security yang tertulis di link share, bukan lewat jalur TLS punya SSH
     * ([ConnectRelay]).
     */
    fun usesTls(): Boolean {
        if (mode == ConnectionMode.XRAY) return false
        return mode == ConnectionMode.SSH_SSL || mode == ConnectionMode.SSH_SSL_PAYLOAD ||
            mode == ConnectionMode.ENHANCED
    }

    /**
     * Apakah harus mencoba handshake WebSocket (RFC 6455) sebelum SSH dimulai.
     *
     * TIDAK ADA lagi togglenya di UI -- sekarang SELALU dicoba di SEMUA mode
     * (SSH, SSH_SSL, SSH_SSL_PAYLOAD/ENHANCED), tidak peduli payload/server tujuan
     * benar-benar paham WebSocket atau tidak. Kalau server/CDN tujuan MENOLAK
     * handshake upgrade-nya (bukan server WebSocket asli), [ConnectRelay] otomatis
     * membuka ulang koneksi dari awal TANPA WebSocket (raw) dan tetap lanjut --
     * jadi tetap bisa dipakai normal walau payload/server-nya sama sekali tidak
     * menyinggung WebSocket. Field [useWebSocket] & [wsPath] dipertahankan cuma
     * untuk kompatibilitas config lama yang tersimpan, nilainya sudah tidak
     * dipakai lagi di sini. Selalu false untuk mode XRAY -- transport (tcp/ws/grpc)
     * mode XRAY ditentukan oleh link share-nya sendiri, ditangani di dalam Xray-core.
     */
    fun usesWebSocket(): Boolean = mode != ConnectionMode.XRAY

    /**
     * Apakah harus mencoba handshake WebSocket RFC 6455 GENUINE (framing biner asli,
     * masking, dst -- lihat [com.example.tunnelapp.tunnel.WebSocketTransport]).
     *
     * PENTING (bug fix, ditemukan dari perbandingan langsung dengan log DarkTunnel
     * yang berhasil connect ke server yang SAMA persis): kalau payload custom SUDAH
     * diisi, payload itu di konvensi komunitas (HTTP Injector/DarkTunnel/HTTP Custom)
     * BIASANYA sudah jadi trik HTTP yang lengkap & berdiri sendiri -- termasuk baris
     * request semacam "PATCH / HTTP/1.1[crlf]Host: ...[crlf]Upgrade: websocket[crlf][crlf]"
     * yang cuma dipakai sebagai KATA KUNCI pemicu supaya reverse-proxy/CDN tujuan
     * beralih ke mode raw passthrough polos ke sshd asli -- BUKAN implementasi
     * WebSocket (RFC 6455) yang benar-benar mem-framing/mask byte sesudahnya. Semua
     * byte sesudah "101 Switching Protocols" di server semacam ini murni APA ADANYA
     * (bahkan banner SSH dropbear terkirim mentah, tanpa bungkus frame apa pun).
     *
     * Kalau [ConnectRelay] TETAP memaksakan handshake WebSocket genuine-nya sendiri
     * setelah payload seperti itu, dua hal yang terjadi: (1) request upgrade KEDUA
     * (duplikat, dari kode ini) dikirim ke server yang SEBENARNYA sudah beralih ke
     * mode raw sejak request pertama (bagian dari payload) -- ditolak/parsing gagal
     * (menerima banner SSH mentah, disangka respons HTTP) -- lalu (2) walau berhasil
     * bertemu framing WebSocket asli di server lain, byte SSH sesudahnya kadung
     * dibungkus frame biner RFC 6455 yang backend bug-host semacam ini TIDAK PERNAH
     * membongkarnya balik -- sshd cuma menerima sampah.
     *
     * Jadi: WebSocket genuine cuma relevan/aman dicoba kalau TIDAK ada payload custom
     * sama sekali (mis. mode SSH/SSH SSL polos ke server WebSocket asli) -- begitu
     * user mengisi payload sendiri, payload itu dipercaya penuh sebagai satu-satunya
     * trik HTTP yang dipakai, sama seperti perilaku DarkTunnel/HTTP Custom.
     */
    fun attemptsFormalWebSocket(): Boolean = usesWebSocket() && payload.isNullOrEmpty()

    /** Apakah mode ini pakai jalur Xray-core, bukan jalur SSH (trilead-ssh2). */
    fun usesXray(): Boolean = mode == ConnectionMode.XRAY
}
