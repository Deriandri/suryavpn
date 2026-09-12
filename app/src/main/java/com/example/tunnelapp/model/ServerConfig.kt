package com.example.tunnelapp.model

/**
 * Metode koneksi yang didukung.
 *
 *  - SSH:             TCP langsung ke server, protokol SSH mentah (tanpa bungkus apa pun).
 *  - SSH_SSL:         TCP langsung + TLS wrap (mirip stunnel) sebelum SSH dimulai.
 *                      FITUR BARU (permintaan user): sekarang juga boleh punya
 *                      proxy/CDN opsional (kalau host proxy diisi), dikontrol oleh
 *                      [ServerConfig.proxyRawMode] -- begitu chip "SSH SSL" dipilih
 *                      di UI, Raw Passthrough otomatis AKTIF secara default (beda
 *                      dari SSH_SSL_PAYLOAD/ENHANCED yang juga default aktif tapi
 *                      baru kalau proxy diisi): TCP connect langsung ke
 *                      proxyHost:proxyPort (tanpa CONNECT), lalu TLS dengan SNI =
 *                      [ServerConfig.sslSni] (atau host asli) -- sama seperti
 *                      varian raw di ENHANCED. Kalau proxy dikosongkan, berperilaku
 *                      seperti sebelumnya: TLS wrap langsung ke [ServerConfig.host].
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
 * @param customHeaders header HTTP tambahan (opsional), satu header per baris,
 *                   format "Nama: Nilai" (mis. "X-Online-Host: bug.host.com").
 *                   Disisipkan ke DUA tempat yang masih hardcode headernya sendiri
 *                   (beda dari [payload] yang memang request HTTP lengkap buatan
 *                   sendiri, jadi TIDAK disentuh oleh field ini):
 *                     1. Request "CONNECT" ke proxy HTTP (lihat
 *                        [com.example.tunnelapp.tunnel.ConnectRelay.sendProxyConnect]) --
 *                        berguna untuk header semacam "X-Online-Host" yang dipakai
 *                        sebagian proxy/bug host untuk routing.
 *                     2. Request upgrade WebSocket genuine RFC 6455 (lihat
 *                        [com.example.tunnelapp.tunnel.WebSocketHandshake.perform]) --
 *                        berguna untuk header semacam "Origin" atau "User-Agent"
 *                        yang kadang diperlukan CDN/reverse-proxy tujuan.
 *                   Mendukung placeholder yang sama seperti [payload]: [host], [port].
 *                   Baris kosong atau tanpa ":" diabaikan. Header dengan nama yang
 *                   sama seperti yang sudah dikirim bawaan (mis. "Host") akan
 *                   membuat header itu terkirim DUA KALI apa adanya -- ini sengaja
 *                   tidak divalidasi/dicegah supaya tetap fleksibel untuk trik bug
 *                   host yang justru butuh header dobel.
 * @param ignoreCertErrors kalau true, TLS handshake ([com.example.tunnelapp.tunnel.ConnectRelay])
 *                   menerima sertifikat server APA ADANYA -- self-signed, kedaluwarsa,
 *                   atau nama tidak cocok dengan host/SNI -- tanpa validasi chain sama
 *                   sekali. TIDAK memengaruhi keamanan trafik SSH itu sendiri
 *                   (autentikasi SSH tetap lewat username/password/key seperti biasa)
 *                   -- TLS di sini cuma "pembungkus" (stunnel-style) supaya trafik
 *                   terlihat seperti HTTPS biasa, BUKAN untuk memverifikasi identitas
 *                   server. Default false (validasi normal via trust store sistem) --
 *                   nyalakan HANYA kalau server tujuan memang pakai sertifikat
 *                   self-signed/kedaluwarsa yang bikin handshake gagal padahal server
 *                   & akun SSH-nya sendiri valid.
 * @param dns1       DNS primer yang dipasang di TUN interface (lihat
 *                   [com.example.tunnelapp.tunnel.MyVpnService]) -- SEMUA query DNS
 *                   device diarahkan ke sini (lewat SOCKS5 UDP ASSOCIATE, lihat
 *                   [com.example.tunnelapp.tunnel.Socks5Server], jadi tetap lewat
 *                   tunnel, bukan bocor ke DNS jaringan lokal). Kosong/null berarti
 *                   pakai default "1.1.1.1" (Cloudflare).
 * @param dns2       DNS sekunder (opsional), cuma dipasang ke TUN kalau diisi --
 *                   tidak ada fallback otomatis, murni tambahan resolver kedua untuk
 *                   OS pilih sendiri kalau yang pertama tidak merespons.
 * @param udpgwPort  Port lokal (di sisi SERVER SSH, diakses lewat tunnel -- BUKAN
 *                   port di device) tempat proses `badvpn-udpgw` terpisah berjalan,
 *                   dipakai [com.example.tunnelapp.tunnel.UdpgwClient] via
 *                   [com.example.tunnelapp.tunnel.Socks5Server] utk meneruskan UDP
 *                   NON-DNS (game, QUIC/HTTP3, VoIP) yang tidak bisa lewat SSH biasa.
 *                   0/default = fitur ini mati, UDP non-DNS tetap dibuang seperti
 *                   sebelumnya. Field ini SENGAJA selalu diisi dari pengaturan
 *                   global (VpnSettingsStore.udpgwPort, kartu "VPN Setting"), bukan
 *                   per-profil -- lihat MyVpnService.startVpn/pickNextFallbackConfig.
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
    val xrayLink: String? = null,
    val customHeaders: String? = null,
    val ignoreCertErrors: Boolean = false,
    val dns1: String? = null,
    val dns2: String? = null,
    val udpgwPort: Int = 0
) {
    /**
     * Parse [customHeaders] jadi daftar pasangan (nama, nilai) siap pakai,
     * dengan placeholder [host]/[port] sudah disubstitusi. Baris kosong atau
     * yang tidak mengandung ":" diabaikan diam-diam (bukan error) supaya user
     * tidak perlu khawatir soal baris kosong sisa di textarea.
     */
    fun parsedCustomHeaders(): List<Pair<String, String>> {
        val raw = customHeaders ?: return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                    .replace("[host]", host)
                    .replace("[port]", port.toString())
                if (name.isEmpty()) null else name to value
            }
            .toList()
    }

    /**
     * Apakah koneksi harus lewat HTTP proxy (CONNECT) dulu sebelum mencapai host asli.
     * Selalu false untuk mode XRAY -- jalur proxy/CDN Xray (kalau ada) sudah diatur
     * sendiri di dalam link share-nya (mis. parameter `?host=`/`?path=` WebSocket),
     * bukan lewat field proxyHost/proxyPort punya jalur SSH.
     */
    fun usesProxy(): Boolean {
        if (mode == ConnectionMode.XRAY) return false
        // FITUR BARU (permintaan user): SSH_SSL sekarang juga boleh punya proxy/CDN
        // opsional + Raw Passthrough, sama seperti SSH_SSL_PAYLOAD/ENHANCED -- lihat
        // catatan lengkap di dokumentasi ConnectionMode.SSH_SSL di atas.
        val optionalProxyMode = mode == ConnectionMode.SSH_SSL ||
            mode == ConnectionMode.SSH_SSL_PAYLOAD ||
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

/**
 * Ubah [SavedConfig] (hasil tersimpan dari layar Konfigurasi SSH/Xray) jadi
 * [ServerConfig] siap pakai buat MyVpnService -- logikanya sengaja disalin
 * dari DashboardMainFragment.onConnectClicked() (bukan menggantikannya,
 * supaya pesan error spesifik yang ditampilkan ke user di sana tidak
 * berubah), dipakai MyVpnService buat FITUR FALLBACK AKUN CADANGAN: kalau
 * akun yang lagi aktif gagal terus (reconnect + reset penuh sudah dicoba
 * semua), MyVpnService butuh cara membangun ServerConfig dari akun LAIN yang
 * tersimpan di ProfileStore, bukan cuma akun aktif yang sudah lewat Intent
 * extras dari Fragment. Null kalau konfigurasinya tidak valid (mis. link
 * Xray kosong, host/username kosong) -- silent, bukan menampilkan pesan ke
 * user (fallback ini jalan di background, tidak ada UI buat menampilkannya).
 */
fun SavedConfig.toServerConfigOrNull(): ServerConfig? {
    if (modeIndex == 5) {
        if (xrayLink.isBlank()) return null
        return ServerConfig(
            host = "", username = "", mode = ConnectionMode.XRAY,
            xrayLink = xrayLink, dns1 = dns1, dns2 = dns2
        )
    }

    if (host.isBlank() || username.isBlank()) return null

    val mode = when (modeIndex) {
        1 -> ConnectionMode.SSH_SSL
        2 -> ConnectionMode.SSH_SSL_PAYLOAD
        3 -> ConnectionMode.REMOTE_PROXY
        else -> ConnectionMode.SSH
    }
    val usesPayload = modeIndex == 2 || modeIndex == 3
    // FITUR BARU (permintaan user): modeIndex 1 (SSH SSL) sekarang juga ikut
    // usesProxy -- proxy/CDN tetap opsional (lihat ServerConfig.usesProxy(),
    // proxyHost kosong = tidak dipakai), tapi Raw Passthrough-nya default aktif
    // begitu mode ini dipilih (lihat SshConfigActivity.applyDefaultRawModeForEnhancedIfNeeded).
    val usesProxy = modeIndex == 1 || modeIndex == 2 || modeIndex == 3
    val proxyRawModeResolved = usesProxy && proxyRawMode
    val usesTls = modeIndex == 1 || modeIndex == 2

    if (usesProxy && proxyRawModeResolved && proxyHost.isBlank()) return null
    if (modeIndex == 3 && proxyHost.isBlank()) return null

    return ServerConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        mode = mode,
        sslSni = sni,
        payload = if (usesPayload) payload else "",
        proxyHost = if (usesProxy) proxyHost else "",
        proxyPort = if (usesProxy) proxyPort.toIntOrNull() else null,
        tlsVersion = if (usesTls) tlsVersion.ifEmpty { null } else null,
        useWebSocket = true,
        wsPath = wsPath,
        proxyRawMode = proxyRawModeResolved,
        xrayLink = "",
        customHeaders = customHeaders,
        ignoreCertErrors = ignoreCertErrors,
        dns1 = dns1,
        dns2 = dns2
    )
}
