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
 * dnsFallbackEnabled MENGONTROL apakah MyVpnService boleh memasang DNS
 * default ([defaultDns]) ke TUN interface waktu DNS1/DNS2 per-server
 * ([ServerConfig.dns1]/[ServerConfig.dns2], diisi di layar Konfigurasi SSH)
 * kosong dua-duanya. Default TRUE (nyala) -- kalau DNS per-server kosong,
 * DNS default tetap dipasang supaya resolusi domain tidak gagal total.
 * Matikan switch ini kalau user MEMANG tidak mau ada DNS default sama
 * sekali (lihat MyVpnService.applyDnsServers) -- risikonya resolusi domain
 * bisa gagal total buat profil yang tidak diisi DNS1/DNS2 manual.
 *
 * [defaultDns] adalah alamat IP yang dipakai sebagai DNS default itu --
 * SEBELUMNYA hardcode "1.1.1.1" di MyVpnService.DEFAULT_DNS &
 * XrayTunnelManager.resolveDnsAddr(), SEKARANG bisa diganti user sendiri
 * lewat field "Default DNS" di kartu VPN Setting (dipakai di KEDUA jalur,
 * SSH maupun Xray/VLESS -- lihat catatan resolveDnsAddr di
 * XrayTunnelManager utk kenapa jalur Xray juga butuh nilai ini sebagai
 * fallback TERAKHIR-nya). Kosong/blank dianggap "belum diisi" dan balik ke
 * [DEFAULT_DNS_FALLBACK] ("1.1.1.1", perilaku lama) -- baik di sini waktu
 * load() maupun di titik pemakaiannya, jadi data lama yang belum pernah
 * simpan field ini otomatis dapat 1.1.1.1 persis seperti sebelum field ini
 * ada. Tidak divalidasi format IPv6 di sini (layar Pengaturan cuma terima
 * IPv4 lewat Patterns.IP_ADDRESS, sama seperti DNS1/DNS2 per-profil di
 * SshConfigActivity) -- kalau butuh DNS default IPv6, isi manual lewat
 * DNS1/DNS2 per-profil yang formatDnsAddr()-nya sudah dukung IPv6. mtu
 * menggantikan konstanta TUN_MTU yang
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
 * [performanceMode], kalau aktif, menyalakan TCP_NODELAY (lihat
 * SshjTunnelManager.connect) di koneksi SSH ke relay lokal -- menonaktifkan
 * algoritma Nagle supaya tiap paket langsung dikirim tanpa nunggu buffer penuh/
 * digabung dulu. Cocok utk trafik "full traffic" (download besar, speedtest) yang
 * mengirim banyak data berurutan. Kalau dimatikan (mode "multi-tasking"), Nagle
 * tetap aktif -- paket kecil digabung dulu sebelum dikirim, sedikit menghemat
 * overhead paket utk banyak koneksi kecil bersamaan (browsing/chat/banyak app),
 * dengan trade-off latensi sedikit lebih tinggi per paket.
 *
 * Engine SSH yang dipakai app ini adalah sshj (com.hierynomus:sshj) --
 * dipilih dibanding Apache MINA SSHD karena API-nya blocking/socket biasa
 * yang cocok dengan arsitektur relay+SOCKS5 app ini, dan riwayat
 * kompatibilitas Android yang lebih baik dibanding MINA SSHD.
 *
 * [compressionEnabled] menyalakan kompresi "zlib"/"zlib@openssh.com" di key
 * exchange SSH lewat SshjTunnelManager.useCompression().
 */
data class VpnSettings(
    // Default TRUE (nyala): DNS default (1.1.1.1) dipasang otomatis kalau
    // DNS1/DNS2 per-server kosong dua-duanya. User bisa matikan sendiri
    // lewat switch "DNS Default Otomatis" di kartu VPN Setting kalau
    // memang tidak mau ada DNS default sama sekali. Lihat
    // MyVpnService.applyDnsServers.
    val dnsFallbackEnabled: Boolean = true,
    // Lihat catatan [defaultDns] di kdoc atas. Kosong/blank == belum diisi,
    // ditangani sebagai DEFAULT_DNS_FALLBACK di titik pemakaiannya.
    val defaultDns: String = DEFAULT_DNS_FALLBACK,
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
    // Default false -- kompresi zlib SSH, lihat catatan compressionEnabled
    // di atas. User menyalakan sendiri lewat kartu "VPN Setting" kalau mau.
    val compressionEnabled: Boolean = false
) {
    companion object {
        // Nilai lama yang dulu hardcode di MyVpnService.DEFAULT_DNS &
        // XrayTunnelManager.resolveDnsAddr() -- sekarang jadi fallback kalau
        // [defaultDns] kosong/belum pernah diisi user (lihat kdoc di atas).
        const val DEFAULT_DNS_FALLBACK = "1.1.1.1"
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
    }
}

object VpnSettingsStore {
    private const val PREFS_NAME = "tunnelapp_vpn_settings"
    private const val KEY_DNS_FALLBACK_ENABLED = "vpn_dns_fallback_enabled"
    private const val KEY_DEFAULT_DNS = "vpn_default_dns"
    private const val KEY_MTU = "vpn_mtu"
    private const val KEY_KEEP_CPU_AWAKE = "vpn_keep_cpu_awake"
    private const val KEY_AUTO_RECONNECT = "vpn_auto_reconnect"
    private const val KEY_SOCKS_PORT = "vpn_socks_port"
    private const val KEY_HTTP_PORT = "vpn_http_port"
    private const val KEY_UDPGW_PORT = "vpn_udpgw_port"
    private const val KEY_PERFORMANCE_MODE = "vpn_performance_mode"
    private const val KEY_COMPRESSION_ENABLED = "vpn_compression_enabled"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return VpnSettings(
            // Default true kalau belum pernah disimpan -- lihat catatan
            // dnsFallbackEnabled di atas.
            dnsFallbackEnabled = prefs.getBoolean(KEY_DNS_FALLBACK_ENABLED, true),
            // Data lama (sebelum field ini ada) tidak punya key ini sama
            // sekali -- default & blank dua-duanya jatuh ke
            // DEFAULT_DNS_FALLBACK di titik pemakaiannya, jadi trim() di
            // sini murni jaga-jaga (mis. user isi spasi doang lalu Simpan).
            defaultDns = (prefs.getString(KEY_DEFAULT_DNS, VpnSettings.DEFAULT_DNS_FALLBACK)
                ?: VpnSettings.DEFAULT_DNS_FALLBACK).trim().ifEmpty { VpnSettings.DEFAULT_DNS_FALLBACK },
            mtu = prefs.getInt(KEY_MTU, VpnSettings.DEFAULT_MTU),
            keepCpuAwake = prefs.getBoolean(KEY_KEEP_CPU_AWAKE, true),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            socksPort = prefs.getInt(KEY_SOCKS_PORT, VpnSettings.DEFAULT_SOCKS_PORT),
            httpPort = prefs.getInt(KEY_HTTP_PORT, VpnSettings.DEFAULT_HTTP_PORT),
            udpgwPort = prefs.getInt(KEY_UDPGW_PORT, VpnSettings.DEFAULT_UDPGW_PORT),
            performanceMode = prefs.getBoolean(KEY_PERFORMANCE_MODE, true),
            compressionEnabled = prefs.getBoolean(KEY_COMPRESSION_ENABLED, false)
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DNS_FALLBACK_ENABLED, settings.dnsFallbackEnabled)
            .putString(KEY_DEFAULT_DNS, settings.defaultDns)
            .putInt(KEY_MTU, settings.mtu)
            .putBoolean(KEY_KEEP_CPU_AWAKE, settings.keepCpuAwake)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putInt(KEY_SOCKS_PORT, settings.socksPort)
            .putInt(KEY_HTTP_PORT, settings.httpPort)
            .putInt(KEY_UDPGW_PORT, settings.udpgwPort)
            .putBoolean(KEY_PERFORMANCE_MODE, settings.performanceMode)
            .putBoolean(KEY_COMPRESSION_ENABLED, settings.compressionEnabled)
            .apply()
    }
}
