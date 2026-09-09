package com.example.tunnelapp.model

import android.content.Context

/**
 * Pengaturan DASAR aplikasi (bukan pengaturan tunnel VPN seperti DNS/MTU di
 * [VpnSettingsStore]) -- diisi lewat layar Pengaturan -> kartu "Pengaturan
 * Dasar", SENGAJA dipisah dari kartu "VPN Setting" & SharedPreferences-nya
 * sendiri sesuai permintaan awal ("jangan digabung ke VPN Setting").
 *
 * Saat ini isinya cuma "Auto Ping": ping berkala ke host server yang lagi
 * konek (lihat MyVpnService.startPingLoop) selama tunnel aktif, buat pantau
 * kualitas/latency koneksi -- hasilnya masuk ke StatusBus.log() sehingga
 * kelihatan di layar Log Koneksi. Beda dari watchdog SOCKS5
 * (MyVpnService.startWatchdog) yang cuma ngecek "hidup/mati" buat trigger
 * reconnect, auto ping ini murni informatif (tidak memicu reconnect).
 */
data class GeneralSettings(
    val autoPingEnabled: Boolean = false,
    val pingIntervalSeconds: Int = DEFAULT_PING_INTERVAL_SECONDS
) {
    companion object {
        const val DEFAULT_PING_INTERVAL_SECONDS = 30
        const val MIN_PING_INTERVAL_SECONDS = 5
        const val MAX_PING_INTERVAL_SECONDS = 3600
    }
}

object GeneralSettingsStore {
    private const val PREFS_NAME = "tunnelapp_general_settings"
    private const val KEY_AUTO_PING_ENABLED = "auto_ping_enabled"
    private const val KEY_PING_INTERVAL_SECONDS = "ping_interval_seconds"

    fun load(context: Context): GeneralSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GeneralSettings(
            autoPingEnabled = prefs.getBoolean(KEY_AUTO_PING_ENABLED, false),
            pingIntervalSeconds = prefs.getInt(
                KEY_PING_INTERVAL_SECONDS,
                GeneralSettings.DEFAULT_PING_INTERVAL_SECONDS
            )
        )
    }

    fun save(context: Context, settings: GeneralSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_PING_ENABLED, settings.autoPingEnabled)
            .putInt(KEY_PING_INTERVAL_SECONDS, settings.pingIntervalSeconds)
            .apply()
    }
}
