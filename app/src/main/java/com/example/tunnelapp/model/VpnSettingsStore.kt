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
 * default (1.1.1.1) ke TUN interface waktu DNS1/DNS2 per-server
 * ([ServerConfig.dns1]/[ServerConfig.dns2], diisi di layar Konfigurasi SSH)
 * kosong dua-duanya. Default TRUE (nyala) -- kalau DNS per-server kosong,
 * DNS default tetap dipasang supaya resolusi domain tidak gagal total.
 * Matikan switch ini kalau user MEMANG tidak mau ada DNS default sama
 * sekali (lihat MyVpnService.applyDnsServers) -- risikonya resolusi domain
 * bisa gagal total buat profil yang tidak diisi DNS1/DNS2 manual. mtu
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
    val compressionEnabled: Boolean = false,

    // ==== FITUR BARU: Per-App Proxy (parity dengan V2RayNG) ====
    // Kalau true, TUN builder akan memanggil addAllowedApplication (mode
    // allow-list) ATAU addDisallowedApplication (mode block-list) untuk
    // paket-paket di [perAppProxyPackages] -- lihat MyVpnService.applyAppFiltering.
    // Default false = semua app lewat tunnel, perilaku lama TIDAK berubah.
    val perAppProxyEnabled: Boolean = false,
    // true = allow-list ("hanya app di daftar yang lewat tunnel, sisanya
    // pakai jaringan asli"), false = block-list ("app di daftar TIDAK lewat
    // tunnel, sisanya tetap lewat tunnel seperti biasa"). Sama seperti opsi
    // "Bypass apps"/"Per-app proxy" di V2RayNG.
    val perAppProxyIsAllowList: Boolean = true,
    val perAppProxyPackages: Set<String> = emptySet(),

    // ==== FITUR BARU: Routing dasar & bypass LAN (parity dengan V2RayNG) ====
    // Kalau true, subnet IP privat (RFC1918 + link-local) TIDAK dimasukkan
    // ke rute TUN (device mengakses LAN lewat jalur asli, bukan lewat
    // tunnel) -- lihat MyVpnService.applyTunRoutes. Tidak butuh geoip.dat,
    // daftar CIDR privat sudah pasti/statis.
    val bypassLan: Boolean = false,
    // Domain (plain-text, tanpa geosite.dat) yang di-bypass langsung (tidak
    // lewat proxy) khusus mode Xray -- satu domain per baris. Cocok dipakai
    // untuk situs lokal/CDN yang tidak perlu/tidak boleh lewat tunnel.
    val routingBypassDomains: String = "",
    // IP/CIDR (plain-text, tanpa geoip.dat) yang di-bypass langsung, satu
    // per baris, khusus mode Xray.
    val routingBypassIps: String = "",

    // ==== FITUR BARU: Mux bisa diatur user (parity dengan V2RayNG) ====
    // Sebelumnya hardcoded selalu true/8 di XrayConfigBuilder. Sekarang bisa
    // dimatikan atau diubah concurrency-nya lewat UI (kartu Routing di Tools).
    val muxEnabled: Boolean = true,
    val muxConcurrency: Int = 8,

    // ==== FITUR BARU: Fake DNS + DNS-over-HTTPS (parity dengan V2RayNG) ====
    // Kalau true, XrayConfigBuilder menambahkan objek "fakedns" + "dns" +
    // sniffing "destOverride" di inbound SOCKS -- domain yang diakses
    // di-resolve ke IP palsu di pool lokal (198.18.0.0/15) SEBELUM sempat
    // keluar device sama sekali, baru "dikembalikan" ke domain aslinya pas
    // trafik itu benar-benar dikirim ke outbound proxy. Efeknya: request DNS
    // untuk domain yang di-tunnel TIDAK PERNAH keluar lewat jalur DNS device
    // yang normal -- mengurangi risiko kebocoran DNS (ISP/jaringan lokal
    // bisa lihat kamu resolve suatu domain walau trafiknya sendiri sudah
    // di-enkripsi). Default false = perilaku lama (resolusi apa adanya lewat
    // SOCKS5 ATYP domain ke server). Khusus mode Xray.
    val fakeDnsEnabled: Boolean = false,
    // URL server DoH (DNS-over-HTTPS) custom, mis. "https://1.1.1.1/dns-query"
    // atau "https://dns.google/dns-query" -- dipakai Xray-core untuk resolusi
    // DNS fallback (domain yang TIDAK di-bypass/tidak masuk fake DNS pool,
    // atau saat fake DNS sendiri butuh cari tahu IP asli untuk logging/rule
    // IP-based). Kosong = Xray pakai DNS sistem/default bawaannya sendiri,
    // TIDAK menambahkan server DoH custom sama sekali (perilaku lama).
    val dohUrl: String = "",

    // ==== FITUR BARU: Subscription auto-update (parity dengan V2RayNG) ====
    // URL subscription TERAKHIR yang diimpor lewat ToolsActivity/
    // SubscriptionImporter -- disimpan otomatis setiap kali import manual
    // berhasil, supaya SubscriptionAutoUpdateReceiver tahu URL mana yang
    // harus di-refresh ulang secara berkala tanpa user perlu tempel URL-nya
    // lagi. Kosong = belum pernah import sama sekali (auto-update tidak
    // akan pernah jalan walau [subscriptionAutoUpdateEnabled] true, karena
    // tidak ada URL yang bisa di-refresh).
    val lastSubscriptionUrl: String = "",
    // Default false -- auto-update TIDAK pernah menyala sendiri, harus
    // dinyalakan manual user lewat kartu Subscription di Tools (sama pola
    // "opt-in" seperti fitur-fitur lain di app ini).
    val subscriptionAutoUpdateEnabled: Boolean = false,
    // Jarak refresh dalam jam. Default 12 jam -- cukup sering untuk provider
    // yang sering ganti server, tidak terlalu boros kuota/baterai untuk
    // sekadar re-fetch teks subscription (biasanya cuma beberapa KB).
    val subscriptionUpdateIntervalHours: Int = 12
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
    }
}

object VpnSettingsStore {
    private const val PREFS_NAME = "tunnelapp_vpn_settings"
    private const val KEY_DNS_FALLBACK_ENABLED = "vpn_dns_fallback_enabled"
    private const val KEY_MTU = "vpn_mtu"
    private const val KEY_KEEP_CPU_AWAKE = "vpn_keep_cpu_awake"
    private const val KEY_AUTO_RECONNECT = "vpn_auto_reconnect"
    private const val KEY_SOCKS_PORT = "vpn_socks_port"
    private const val KEY_HTTP_PORT = "vpn_http_port"
    private const val KEY_UDPGW_PORT = "vpn_udpgw_port"
    private const val KEY_PERFORMANCE_MODE = "vpn_performance_mode"
    private const val KEY_COMPRESSION_ENABLED = "vpn_compression_enabled"
    private const val KEY_PER_APP_ENABLED = "vpn_per_app_enabled"
    private const val KEY_PER_APP_IS_ALLOW_LIST = "vpn_per_app_is_allow_list"
    private const val KEY_PER_APP_PACKAGES = "vpn_per_app_packages"
    private const val KEY_BYPASS_LAN = "vpn_bypass_lan"
    private const val KEY_ROUTING_BYPASS_DOMAINS = "vpn_routing_bypass_domains"
    private const val KEY_ROUTING_BYPASS_IPS = "vpn_routing_bypass_ips"
    private const val KEY_MUX_ENABLED = "vpn_mux_enabled"
    private const val KEY_MUX_CONCURRENCY = "vpn_mux_concurrency"
    private const val KEY_FAKE_DNS_ENABLED = "vpn_fake_dns_enabled"
    private const val KEY_DOH_URL = "vpn_doh_url"
    private const val KEY_SUBSCRIPTION_URL = "vpn_subscription_url"
    private const val KEY_SUBSCRIPTION_AUTO_UPDATE = "vpn_subscription_auto_update"
    private const val KEY_SUBSCRIPTION_INTERVAL_HOURS = "vpn_subscription_interval_hours"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return VpnSettings(
            // Default true kalau belum pernah disimpan -- lihat catatan
            // dnsFallbackEnabled di atas.
            dnsFallbackEnabled = prefs.getBoolean(KEY_DNS_FALLBACK_ENABLED, true),
            mtu = prefs.getInt(KEY_MTU, VpnSettings.DEFAULT_MTU),
            keepCpuAwake = prefs.getBoolean(KEY_KEEP_CPU_AWAKE, true),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            socksPort = prefs.getInt(KEY_SOCKS_PORT, VpnSettings.DEFAULT_SOCKS_PORT),
            httpPort = prefs.getInt(KEY_HTTP_PORT, VpnSettings.DEFAULT_HTTP_PORT),
            udpgwPort = prefs.getInt(KEY_UDPGW_PORT, VpnSettings.DEFAULT_UDPGW_PORT),
            performanceMode = prefs.getBoolean(KEY_PERFORMANCE_MODE, true),
            compressionEnabled = prefs.getBoolean(KEY_COMPRESSION_ENABLED, false),
            perAppProxyEnabled = prefs.getBoolean(KEY_PER_APP_ENABLED, false),
            perAppProxyIsAllowList = prefs.getBoolean(KEY_PER_APP_IS_ALLOW_LIST, true),
            perAppProxyPackages = prefs.getStringSet(KEY_PER_APP_PACKAGES, emptySet()) ?: emptySet(),
            bypassLan = prefs.getBoolean(KEY_BYPASS_LAN, false),
            routingBypassDomains = prefs.getString(KEY_ROUTING_BYPASS_DOMAINS, "") ?: "",
            routingBypassIps = prefs.getString(KEY_ROUTING_BYPASS_IPS, "") ?: "",
            muxEnabled = prefs.getBoolean(KEY_MUX_ENABLED, true),
            muxConcurrency = prefs.getInt(KEY_MUX_CONCURRENCY, 8),
            fakeDnsEnabled = prefs.getBoolean(KEY_FAKE_DNS_ENABLED, false),
            dohUrl = prefs.getString(KEY_DOH_URL, "") ?: "",
            lastSubscriptionUrl = prefs.getString(KEY_SUBSCRIPTION_URL, "") ?: "",
            subscriptionAutoUpdateEnabled = prefs.getBoolean(KEY_SUBSCRIPTION_AUTO_UPDATE, false),
            subscriptionUpdateIntervalHours = prefs.getInt(KEY_SUBSCRIPTION_INTERVAL_HOURS, 12)
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DNS_FALLBACK_ENABLED, settings.dnsFallbackEnabled)
            .putInt(KEY_MTU, settings.mtu)
            .putBoolean(KEY_KEEP_CPU_AWAKE, settings.keepCpuAwake)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putInt(KEY_SOCKS_PORT, settings.socksPort)
            .putInt(KEY_HTTP_PORT, settings.httpPort)
            .putInt(KEY_UDPGW_PORT, settings.udpgwPort)
            .putBoolean(KEY_PERFORMANCE_MODE, settings.performanceMode)
            .putBoolean(KEY_COMPRESSION_ENABLED, settings.compressionEnabled)
            .putBoolean(KEY_PER_APP_ENABLED, settings.perAppProxyEnabled)
            .putBoolean(KEY_PER_APP_IS_ALLOW_LIST, settings.perAppProxyIsAllowList)
            .putStringSet(KEY_PER_APP_PACKAGES, settings.perAppProxyPackages)
            .putBoolean(KEY_BYPASS_LAN, settings.bypassLan)
            .putString(KEY_ROUTING_BYPASS_DOMAINS, settings.routingBypassDomains)
            .putString(KEY_ROUTING_BYPASS_IPS, settings.routingBypassIps)
            .putBoolean(KEY_MUX_ENABLED, settings.muxEnabled)
            .putInt(KEY_MUX_CONCURRENCY, settings.muxConcurrency)
            .putBoolean(KEY_FAKE_DNS_ENABLED, settings.fakeDnsEnabled)
            .putString(KEY_DOH_URL, settings.dohUrl)
            .putString(KEY_SUBSCRIPTION_URL, settings.lastSubscriptionUrl)
            .putBoolean(KEY_SUBSCRIPTION_AUTO_UPDATE, settings.subscriptionAutoUpdateEnabled)
            .putInt(KEY_SUBSCRIPTION_INTERVAL_HOURS, settings.subscriptionUpdateIntervalHours)
            .apply()
    }

    /** Helper ringan dipakai AppFilterActivity: baca/tulis cuma bagian per-app proxy. */
    fun savePerAppProxy(context: Context, enabled: Boolean, isAllowList: Boolean, packages: Set<String>) {
        val current = load(context)
        save(context, current.copy(
            perAppProxyEnabled = enabled,
            perAppProxyIsAllowList = isAllowList,
            perAppProxyPackages = packages
        ))
    }
}
