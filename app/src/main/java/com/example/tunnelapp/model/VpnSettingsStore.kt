package com.example.tunnelapp.model

import android.content.Context

/**
 * Pengaturan VPN GLOBAL (bukan per-server): DNS, MTU, dan keep-CPU-awake.
 * Diisi lewat layar Pengaturan -> kartu "VPN Setting".
 *
 * Beda dengan [ConfigStore]/[SavedConfig] yang isinya akun & profil koneksi
 * server (SSH/Xray) -- ini murni parameter tunnel-nya sendiri, jadi sengaja
 * dipisah ke SharedPreferences sendiri ([PREFS_NAME]) supaya tidak campur
 * dengan data akun.
 *
 * dnsOverrideEnabled MENGONTROL apakah dns1/dns2 di sini benar-benar
 * dipakai. Default FALSE (mati) -- dns1/dns2 tetap terisi nilai contoh
 * (8.8.8.8 / 1.1.1.1) di field-nya supaya user tinggal nyalakan switch-nya
 * kalau mau, tapi SELAMA switch mati, isian dns1/dns2 di sini DIABAIKAN
 * total (lihat MyVpnService.applyDnsServers) -- app jalan seperti belum
 * ada override DNS sama sekali, pakai DNS per-server/default seperti biasa.
 * Kalau dinyalakan, dns1/dns2 (bukan string kosong) MENIMPA DNS per-server
 * ([ServerConfig.dns1]/[ServerConfig.dns2]) -- lihat
 * MyVpnService.applyDnsServers. mtu menggantikan konstanta TUN_MTU yang
 * dulu hardcoded 1500 di MyVpnService. keepCpuAwake mengontrol apakah
 * MyVpnService memegang PowerManager.PARTIAL_WAKE_LOCK selama tunnel aktif.
 *
 * [socksPort], kalau diisi (bukan 0), MENIMPA [ServerConfig.socksPort] milik
 * SEMUA profil (SSH maupun Xray) -- sama seperti pola override DNS di atas,
 * supaya port SOCKS5 lokal bisa diseragamkan/dipindah dari satu tempat tanpa
 * mengedit tiap profil satu-satu. Kosong/0 berarti tiap profil tetap pakai
 * port SOCKS5 masing-masing (default 1080), perilaku lama tidak berubah.
 *
 * [httpPort], kalau diisi (bukan 0), menyalakan proxy HTTP lokal tambahan
 * (lihat [com.example.tunnelapp.tunnel.HttpProxyServer]) di 127.0.0.1:<httpPort>
 * SELAMA tunnel aktif -- berguna untuk app lain di device yang cuma dukung
 * proxy HTTP (bukan SOCKS5). Proxy ini murni "penerjemah" (chaining): tiap
 * request/CONNECT yang masuk diteruskan lagi lewat SOCKS5 lokal yang sama
 * (127.0.0.1:<socksPort efektif>), jadi tetap lewat tunnel yang sama persis.
 * Kosong/0 berarti proxy HTTP tambahan ini tidak dinyalakan sama sekali
 * (perilaku lama).
 *
 * [udpgwPort], kalau diisi (bukan 0), MENYALAKAN [com.example.tunnelapp.tunnel.UdpgwClient]:
 * UDP NON-DNS (game, QUIC/HTTP3, VoIP) yang device kirim lewat TUN akan
 * diteruskan lewat protokol udpgw (badvpn-udpgw) ke proses `badvpn-udpgw`
 * yang berjalan TERPISAH di sisi SERVER SSH (diasumsikan bind ke
 * 127.0.0.1:<udpgwPort> di sana, diakses lewat channel "direct-tcpip" SSH
 * yang sama seperti trafik TCP biasa). SSH sendiri memang cuma bisa forward
 * TCP mentah -- hev-socks5-tunnel meneruskan UDP device lewat SOCKS5 UDP
 * ASSOCIATE ke [com.example.tunnelapp.tunnel.Socks5Server] seperti biasa
 * (`udp: 'udp'` di config engine), tapi Socks5Server sendiri sebelumnya
 * cuma bisa mem-bypass batasan itu utk DNS (lewat DNS-over-TCP) -- UDP
 * lain dibuang. udpgw inilah yang mengisi celah itu utk UDP non-DNS.
 * Kosong/0 (default) berarti fitur ini mati -- UDP non-DNS tetap dibuang
 * seperti sebelumnya, TIDAK ada perubahan perilaku. WAJIB ada proses
 * `badvpn-udpgw` yang benar-benar berjalan (dan mendengarkan) di sisi
 * server pada port yang diisi di sini -- kalau tidak, channel-nya akan
 * gagal dibuka terus & fitur ini tidak berefek walau diaktifkan.
 *
 * [performanceMode], kalau aktif, menyalakan TCP_NODELAY (Connection.setTCPNoDelay,
 * lihat SshTunnelManager.connect) di koneksi SSH ke relay lokal -- menonaktifkan
 * algoritma Nagle supaya tiap paket langsung dikirim tanpa nunggu buffer penuh/
 * digabung dulu. Cocok utk trafik "full traffic" (download besar, speedtest) yang
 * mengirim banyak data berurutan. Kalau dimatikan (mode "multi-tasking"), Nagle
 * tetap aktif -- paket kecil digabung dulu sebelum dikirim, sedikit menghemat
 * overhead paket utk banyak koneksi kecil bersamaan (browsing/chat/banyak app),
 * dengan trade-off latensi sedikit lebih tinggi per paket.
 *
 * [sshEngine] pilih IMPLEMENTASI SSH yang dipakai [com.example.tunnelapp.tunnel.SshEngineRouter]:
 *  - [ENGINE_SSHJ] (default SEKARANG): engine kedua (com.hierynomus:sshj),
 *    BENERAN mendukung kompresi zlib/zlib@openssh.com (lihat
 *    SshjTunnelManager.useCompression()). Dijadikan default karena terbukti
 *    JAUH lebih tahan terhadap jaringan seluler yang jitter/sibuk (jam
 *    siang/sore) dibanding trilead -- lihat catatan panjang soal ini di
 *    ENGINE_TRILEAD di bawah & di SshTunnelManager.KEEPALIVE_INTERVAL_MS.
 *    Fitur non-esensial trilead (reorder cipher cepat, ekstraksi server
 *    banner lewat reflection) BELUM diportasi ke engine ini -- fungsi inti
 *    (auth password, semua [ConnectionMode] lewat ConnectRelay yang sama,
 *    SOCKS5 lokal, forwarding UDPGW) tetap jalan penuh.
 *  - [ENGINE_TRILEAD]: fork jenkinsci/trilead-ssh2, engine ASLI app ini sejak
 *    awal. TIDAK mendukung kompresi zlib sama sekali (lihat catatan
 *    [compressionEnabled] di bawah). BUKAN default lagi (sebelumnya default)
 *    -- laporan & log nyata menunjukkan library ini rawan macet TOTAL
 *    (semua channel SOCKS5 baru timeout beruntun) begitu koneksi seluler
 *    kena jitter/drop singkat sekalipun, karena desain single-dispatcher-nya
 *    (satu thread pembaca dipakai bareng utk semua channel -- kalau satu
 *    channel nyangkut, channel BARU pun ikut tidak pernah dibalas sampai
 *    timeout). Gejalanya paling kentara pas jam sibuk & "hilang" di malam
 *    hari yang jaringannya lengang -- itu cuma pemicunya jarang muncul,
 *    bukan bug-nya sembuh. Tetap tersedia sebagai pilihan manual di kartu
 *    "VPN Setting" bagi yang mau/perlu.
 *
 * [compressionEnabled] -- JUJUR: trilead-ssh2 (ENGINE_TRILEAD) TIDAK
 * mengimplementasikan algoritma kompresi "zlib"/"zlib@openssh.com" di key
 * exchange SSH sama sekali (cryptoWishList di library ini cuma pernah
 * menawarkan "none"). Toggle ini SEKARANG BEREFEK NYATA, TAPI HANYA kalau
 * [sshEngine] = [ENGINE_SSHJ] -- SettingsActivity mengunci (disable) switch
 * ini kalau engine yang dipilih masih ENGINE_TRILEAD, supaya tidak
 * menyesatkan user seolah aktif padahal enginenya tidak mendukung.
 */
data class VpnSettings(
    // Default diisi contoh publik yang umum dipakai (Google & Cloudflare)
    // supaya user tinggal nyalakan switch [dnsOverrideEnabled] kalau mau,
    // tanpa perlu isi manual dulu. TIDAK berefek sama sekali selama
    // dnsOverrideEnabled masih false -- lihat catatan di atas.
    val dns1: String = "8.8.8.8",
    val dns2: String = "1.1.1.1",
    // Default FALSE (mati): override DNS1/DNS2 di atas baru benar-benar
    // dipakai kalau user menyalakan sendiri lewat switch ini. Lihat
    // MyVpnService.applyDnsServers.
    val dnsOverrideEnabled: Boolean = false,
    val mtu: Int = DEFAULT_MTU,
    // Default true: WakeLock aktif dari awal supaya tunnel tidak putus-putus
    // di background tanpa user harus menyalakannya manual.
    val keepCpuAwake: Boolean = true,
    // Nyambung ulang otomatis kalau tunnel putus sendiri (lihat
    // MyVpnService.scheduleReconnectOrGiveUp). Default true supaya perilaku
    // lama (sebelum toggle ini ada) tidak berubah buat user yang sudah pakai.
    val autoReconnect: Boolean = true,
    // Default aktif (bukan 0) dengan port standar aplikasi, supaya SOCKS5,
    // proxy HTTP lokal, dan forwarding UDPGW langsung jalan tanpa user perlu
    // mengisi manual di kartu "VPN Setting". 0 tetap berarti "nonaktif" kalau
    // user mengosongkan sendiri field-nya.
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val udpgwPort: Int = DEFAULT_UDPGW_PORT,
    // Default true: langsung "mode full traffic" (lihat catatan performanceMode
    // di atas) tanpa perlu diaktifkan manual, meniru default ON di app referensi.
    val performanceMode: Boolean = true,
    // Default false -- lihat catatan JUJUR di atas: cuma berefek nyata kalau
    // sshEngine = ENGINE_SSHJ, dikunci disabled di UI selama masih ENGINE_TRILEAD.
    val compressionEnabled: Boolean = false,
    // GANTI DEFAULT ke ENGINE_SSHJ (sebelumnya ENGINE_TRILEAD): laporan user
    // nyata di lapangan -- trilead-ssh2 sering macet total (SEMUA channel
    // SOCKS5 baru timeout beruntun, lihat catatan panjang di
    // SshTunnelManager.KEEPALIVE_INTERVAL_MS) begitu jaringan seluler kena
    // jitter/drop kecil sekalipun (paling kentara pas jam sibuk siang/sore;
    // malam hari jaringan lengang jadi kelihatan "stabil" -- BUKAN berarti
    // bug-nya hilang, cuma pemicunya jarang muncul). sshj terbukti jauh
    // lebih tahan terhadap gangguan jaringan yang sama karena deteksi
    // putus & penanganan channel-nya lebih halus (lihat SshjTunnelManager,
    // client.connection.keepAlive + disconnectWatchThread yang polling tiap
    // 2 detik). trilead TETAP tersedia sebagai opsi manual di "VPN Setting"
    // buat yang mau/perlu (mis. server yang entah kenapa lebih cocok
    // dengannya), cuma bukan default lagi.
    val sshEngine: String = ENGINE_SSHJ
) {
    companion object {
        const val DEFAULT_MTU = 1500
        // Batas wajar MTU untuk TUN VPN -- di luar rentang ini besar
        // kemungkinan tunnel gagal establish atau paket kepotong-potong.
        const val MIN_MTU = 576
        const val MAX_MTU = 1500

        // Rentang port valid untuk socksPort/httpPort/udpgwPort di atas --
        // 0 sendiri berarti "tidak diisi/nonaktif", BUKAN bagian rentang
        // valid ini (dicek terpisah sebagai kondisi kosong di UI).
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        // Nilai default port ketika fitur ini "aktif dari awal" (belum
        // pernah disimpan user). Mengikuti port yang sudah dipakai di
        // tempat lain pada app ini (mis. ServerConfig.socksPort = 1080
        // dipakai per-profil; di sini nilainya sengaja beda supaya
        // override global ini gampang dibedakan saat debugging).
        const val DEFAULT_SOCKS_PORT = 3080
        const val DEFAULT_HTTP_PORT = 8880
        const val DEFAULT_UDPGW_PORT = 7300

        const val ENGINE_TRILEAD = "TRILEAD"
        const val ENGINE_SSHJ = "SSHJ"
    }
}

object VpnSettingsStore {
    private const val PREFS_NAME = "tunnelapp_vpn_settings"
    private const val KEY_DNS1 = "vpn_dns1"
    private const val KEY_DNS2 = "vpn_dns2"
    private const val KEY_DNS_OVERRIDE_ENABLED = "vpn_dns_override_enabled"
    private const val KEY_MTU = "vpn_mtu"
    private const val KEY_KEEP_CPU_AWAKE = "vpn_keep_cpu_awake"
    private const val KEY_AUTO_RECONNECT = "vpn_auto_reconnect"
    private const val KEY_SOCKS_PORT = "vpn_socks_port"
    private const val KEY_HTTP_PORT = "vpn_http_port"
    private const val KEY_UDPGW_PORT = "vpn_udpgw_port"
    private const val KEY_PERFORMANCE_MODE = "vpn_performance_mode"
    private const val KEY_COMPRESSION_ENABLED = "vpn_compression_enabled"
    private const val KEY_SSH_ENGINE = "vpn_ssh_engine"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return VpnSettings(
            // Default fallback "8.8.8.8"/"1.1.1.1" (bukan string kosong)
            // supaya user baru langsung lihat contoh terisi di field-nya --
            // TETAP aman karena dnsOverrideEnabled default-nya false di
            // bawah (lihat KEY_DNS_OVERRIDE_ENABLED).
            dns1 = prefs.getString(KEY_DNS1, VpnSettings().dns1).orEmpty(),
            dns2 = prefs.getString(KEY_DNS2, VpnSettings().dns2).orEmpty(),
            dnsOverrideEnabled = prefs.getBoolean(KEY_DNS_OVERRIDE_ENABLED, false),
            mtu = prefs.getInt(KEY_MTU, VpnSettings.DEFAULT_MTU),
            keepCpuAwake = prefs.getBoolean(KEY_KEEP_CPU_AWAKE, true),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            socksPort = prefs.getInt(KEY_SOCKS_PORT, VpnSettings.DEFAULT_SOCKS_PORT),
            httpPort = prefs.getInt(KEY_HTTP_PORT, VpnSettings.DEFAULT_HTTP_PORT),
            udpgwPort = prefs.getInt(KEY_UDPGW_PORT, VpnSettings.DEFAULT_UDPGW_PORT),
            performanceMode = prefs.getBoolean(KEY_PERFORMANCE_MODE, true),
            compressionEnabled = prefs.getBoolean(KEY_COMPRESSION_ENABLED, false),
            // Data lama (sebelum fitur multi-engine ini ada, atau user yang
            // belum pernah menyentuh switch "SSH Engine") tidak punya key ini
            // sama sekali -- fallback-nya SEKARANG ikut default baru
            // (ENGINE_SSHJ, lihat catatan panjang di VpnSettings.sshEngine)
            // karena trilead terbukti gampang macet total di jaringan
            // seluler yang jitter/sibuk. User yang PERNAH sengaja memilih
            // trilead secara eksplisit tetap aman -- KEY_SSH_ENGINE mereka
            // sudah tersimpan "TRILEAD", jadi tidak kena fallback ini sama
            // sekali dan pilihannya tetap dihormati.
            sshEngine = prefs.getString(KEY_SSH_ENGINE, VpnSettings.ENGINE_SSHJ)
                ?: VpnSettings.ENGINE_SSHJ
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DNS1, settings.dns1)
            .putString(KEY_DNS2, settings.dns2)
            .putBoolean(KEY_DNS_OVERRIDE_ENABLED, settings.dnsOverrideEnabled)
            .putInt(KEY_MTU, settings.mtu)
            .putBoolean(KEY_KEEP_CPU_AWAKE, settings.keepCpuAwake)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putInt(KEY_SOCKS_PORT, settings.socksPort)
            .putInt(KEY_HTTP_PORT, settings.httpPort)
            .putInt(KEY_UDPGW_PORT, settings.udpgwPort)
            .putBoolean(KEY_PERFORMANCE_MODE, settings.performanceMode)
            .putBoolean(KEY_COMPRESSION_ENABLED, settings.compressionEnabled)
            .putString(KEY_SSH_ENGINE, settings.sshEngine)
            .apply()
    }
}
