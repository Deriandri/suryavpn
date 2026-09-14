package com.example.tunnelapp.model

import android.content.Context

/**
 * Pengaturan DASAR aplikasi (bukan pengaturan tunnel VPN seperti DNS/MTU di
 * [VpnSettingsStore]) -- diisi lewat layar Pengaturan -> kartu "Pengaturan
 * Dasar", SENGAJA dipisah dari kartu "VPN Setting" & SharedPreferences-nya
 * sendiri sesuai permintaan awal ("jangan digabung ke VPN Setting").
 *
 * Saat ini isinya "Auto Ping": ping berkala ke host server yang lagi konek
 * (lihat MyVpnService.startPingLoop) selama tunnel aktif, buat pantau
 * kualitas/latency koneksi -- hasilnya masuk ke StatusBus.log() sehingga
 * kelihatan di layar Log Koneksi. Beda dari watchdog SOCKS5
 * (MyVpnService.startWatchdog) yang cuma ngecek "hidup/mati" buat trigger
 * reconnect, auto ping ini murni informatif (tidak memicu reconnect).
 *
 * Auto Ping juga menjalankan keep-alive SUNGGUHAN lewat tunnel ke
 * [keepAliveTarget] -- targetnya bisa diatur user di sini, BUKAN hardcode
 * lagi ke www.google.com:443.
 *
 * [keepAliveTarget] SEKARANG boleh diisi "host" saja TANPA port (lihat
 * MyVpnService.parseKeepAliveTarget) -- kalau tidak ada titik dua, port
 * otomatis dianggap [DEFAULT_KEEP_ALIVE_PORT] (443), host yang diketik user
 * TETAP dipakai apa adanya (dulu -- bug -- host ikut dibuang & diganti
 * default Google kalau usernya lupa nulis port).
 *
 * DEFAULT_KEEP_ALIVE_TARGET (permintaan user: "sembunyikan port dari
 * tampilan") SEKARANG cuma "www.google.com" TANPA ":443" -- field jadi
 * lebih ringkas & tidak bikin bingung user awam yang lihat angka port yang
 * sebenarnya tidak perlu mereka utak-atik. Port 443 TETAP dipakai persis
 * sama seperti sebelumnya karena parseKeepAliveTarget() di atas otomatis
 * fallback ke DEFAULT_KEEP_ALIVE_PORT waktu tidak ada ":port" di teksnya --
 * jadi ini MURNI perubahan tampilan, perilaku keep-alive-nya sama sekali
 * tidak berubah.
 *
 * [keepAliveMethod] pilih MEKANISME keep-alive-nya (dua-duanya tetap lewat
 * SOCKS5 lokal/tunnel yang aktif, beda cuma di "kedalaman" pembuktiannya):
 *  - [METHOD_TCP]: buka-tutup handshake SOCKS5 CONNECT ke target (cepat,
 *    ringan, lihat MyVpnService.keepAliveThroughTunnel).
 *  - [METHOD_HTTP]: BENERAN kirim request HTTP GET ke target & tunggu
 *    balasan header HTTP asli (lebih "berat" dikit tapi bukti koneksinya
 *    lebih kuat, mirip semangat verifyTunnelReallyWorks tapi ke target yang
 *    bisa diatur user -- lihat MyVpnService.httpKeepAliveThroughTunnel).
 */
data class GeneralSettings(
    val autoPingEnabled: Boolean = false,
    val pingIntervalSeconds: Int = DEFAULT_PING_INTERVAL_SECONDS,
    val keepAliveTarget: String = DEFAULT_KEEP_ALIVE_TARGET,
    val keepAliveMethod: String = METHOD_TCP
) {
    companion object {
        const val DEFAULT_PING_INTERVAL_SECONDS = 30
        const val MIN_PING_INTERVAL_SECONDS = 5
        const val MAX_PING_INTERVAL_SECONDS = 3600
        const val DEFAULT_KEEP_ALIVE_TARGET = "www.google.com"

        /** Port yang dipakai kalau user cuma isi host tanpa ":port" sama sekali. */
        const val DEFAULT_KEEP_ALIVE_PORT = 443

        const val METHOD_TCP = "TCP"
        const val METHOD_HTTP = "HTTP"
    }
}

object GeneralSettingsStore {
    private const val PREFS_NAME = "tunnelapp_general_settings"
    private const val KEY_AUTO_PING_ENABLED = "auto_ping_enabled"
    private const val KEY_PING_INTERVAL_SECONDS = "ping_interval_seconds"
    private const val KEY_KEEP_ALIVE_TARGET = "keep_alive_target"
    private const val KEY_KEEP_ALIVE_METHOD = "keep_alive_method"

    fun load(context: Context): GeneralSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GeneralSettings(
            autoPingEnabled = prefs.getBoolean(KEY_AUTO_PING_ENABLED, false),
            pingIntervalSeconds = prefs.getInt(
                KEY_PING_INTERVAL_SECONDS,
                GeneralSettings.DEFAULT_PING_INTERVAL_SECONDS
            ),
            keepAliveTarget = prefs.getString(
                KEY_KEEP_ALIVE_TARGET,
                GeneralSettings.DEFAULT_KEEP_ALIVE_TARGET
            ) ?: GeneralSettings.DEFAULT_KEEP_ALIVE_TARGET,
            // Data lama (sebelum fitur ini ada) tidak punya key ini sama
            // sekali -- default ke METHOD_TCP supaya perilaku user lama yang
            // sudah pernah simpan Pengaturan Dasar TIDAK BERUBAH sama sekali.
            keepAliveMethod = prefs.getString(KEY_KEEP_ALIVE_METHOD, GeneralSettings.METHOD_TCP)
                ?: GeneralSettings.METHOD_TCP
        )
    }

    fun save(context: Context, settings: GeneralSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_PING_ENABLED, settings.autoPingEnabled)
            .putInt(KEY_PING_INTERVAL_SECONDS, settings.pingIntervalSeconds)
            .putString(KEY_KEEP_ALIVE_TARGET, settings.keepAliveTarget)
            .putString(KEY_KEEP_ALIVE_METHOD, settings.keepAliveMethod)
            .apply()
    }
}
