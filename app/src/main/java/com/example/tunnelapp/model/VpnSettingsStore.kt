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
 */
data class VpnSettings(
    val dns1: String = "",
    val dns2: String = "",
    val mtu: Int = DEFAULT_MTU,
    val keepCpuAwake: Boolean = false
) {
    companion object {
        const val DEFAULT_MTU = 1500
        // Batas wajar MTU untuk TUN VPN -- di luar rentang ini besar
        // kemungkinan tunnel gagal establish atau paket kepotong-potong.
        const val MIN_MTU = 576
        const val MAX_MTU = 1500
    }
}

object VpnSettingsStore {
    private const val PREFS_NAME = "tunnelapp_vpn_settings"
    private const val KEY_DNS1 = "vpn_dns1"
    private const val KEY_DNS2 = "vpn_dns2"
    private const val KEY_MTU = "vpn_mtu"
    private const val KEY_KEEP_CPU_AWAKE = "vpn_keep_cpu_awake"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return VpnSettings(
            dns1 = prefs.getString(KEY_DNS1, "").orEmpty(),
            dns2 = prefs.getString(KEY_DNS2, "").orEmpty(),
            mtu = prefs.getInt(KEY_MTU, VpnSettings.DEFAULT_MTU),
            keepCpuAwake = prefs.getBoolean(KEY_KEEP_CPU_AWAKE, false)
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DNS1, settings.dns1)
            .putString(KEY_DNS2, settings.dns2)
            .putInt(KEY_MTU, settings.mtu)
            .putBoolean(KEY_KEEP_CPU_AWAKE, settings.keepCpuAwake)
            .apply()
    }
}
