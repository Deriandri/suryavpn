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
 * dns1/dns2 di sini, kalau diisi (bukan string kosong), MENIMPA DNS
 * per-server ([ServerConfig.dns1]/[ServerConfig.dns2]) -- lihat
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
 * [udpgwPort] DISIMPAN untuk kompatibilitas/pengaturan lanjutan, TAPI
 * SAAT INI TIDAK mengubah perilaku apa pun: engine tunnel di app ini
 * (hev-socks5-tunnel, lihat [com.example.tunnelapp.tunnel.HevSocks5Engine])
 * sudah meneruskan UDP langsung lewat SOCKS5 UDP ASSOCIATE bawaannya sendiri
 * (`udp: 'udp'` di config), BEDA dari aplikasi tunnel lama (mis. berbasis
 * tun2socks/badvpn) yang memang butuh proses "udpgw" terpisah buat UDP.
 * Kolom ini tetap disediakan di UI/penyimpanan supaya siap dipakai kalau
 * suatu saat engine UDP-nya diganti dan benar-benar butuh port ini.
 */
data class VpnSettings(
    val dns1: String = "",
    val dns2: String = "",
    val mtu: Int = DEFAULT_MTU,
    val keepCpuAwake: Boolean = false,
    // Nyambung ulang otomatis kalau tunnel putus sendiri (lihat
    // MyVpnService.scheduleReconnectOrGiveUp). Default true supaya perilaku
    // lama (sebelum toggle ini ada) tidak berubah buat user yang sudah pakai.
    val autoReconnect: Boolean = true,
    val socksPort: Int = 0,
    val httpPort: Int = 0,
    val udpgwPort: Int = 0
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
    }
}

object VpnSettingsStore {
    private const val PREFS_NAME = "tunnelapp_vpn_settings"
    private const val KEY_DNS1 = "vpn_dns1"
    private const val KEY_DNS2 = "vpn_dns2"
    private const val KEY_MTU = "vpn_mtu"
    private const val KEY_KEEP_CPU_AWAKE = "vpn_keep_cpu_awake"
    private const val KEY_AUTO_RECONNECT = "vpn_auto_reconnect"
    private const val KEY_SOCKS_PORT = "vpn_socks_port"
    private const val KEY_HTTP_PORT = "vpn_http_port"
    private const val KEY_UDPGW_PORT = "vpn_udpgw_port"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return VpnSettings(
            dns1 = prefs.getString(KEY_DNS1, "").orEmpty(),
            dns2 = prefs.getString(KEY_DNS2, "").orEmpty(),
            mtu = prefs.getInt(KEY_MTU, VpnSettings.DEFAULT_MTU),
            keepCpuAwake = prefs.getBoolean(KEY_KEEP_CPU_AWAKE, false),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            socksPort = prefs.getInt(KEY_SOCKS_PORT, 0),
            httpPort = prefs.getInt(KEY_HTTP_PORT, 0),
            udpgwPort = prefs.getInt(KEY_UDPGW_PORT, 0)
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DNS1, settings.dns1)
            .putString(KEY_DNS2, settings.dns2)
            .putInt(KEY_MTU, settings.mtu)
            .putBoolean(KEY_KEEP_CPU_AWAKE, settings.keepCpuAwake)
            .putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            .putInt(KEY_SOCKS_PORT, settings.socksPort)
            .putInt(KEY_HTTP_PORT, settings.httpPort)
            .putInt(KEY_UDPGW_PORT, settings.udpgwPort)
            .apply()
    }
}
