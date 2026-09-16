package com.example.tunnelapp.model

import android.content.Context

/**
 * Pengaturan GLOBAL khusus mode Xray (VMess/VLESS/Trojan) -- diisi lewat
 * layar Pengaturan -> kartu "Xray" (tepat di atas kartu "Tentang aplikasi"),
 * SENGAJA dipisah dari [GeneralSettingsStore]/[VpnSettingsStore] karena ini
 * murni berlaku buat jalur Xray, tidak relevan sama sekali untuk jalur SSH.
 *
 * FITUR BARU (permintaan user, "tambahkan fungsi untuk mematikan mux"):
 * [muxEnabled] adalah SAKLAR UTAMA/override manual untuk mux.cool Xray-core
 * (lihat [com.example.tunnelapp.tunnel.XrayConfigBuilder.buildOutbound]).
 *
 * Default TRUE supaya perilaku app TIDAK BERUBAH buat user yang sudah pernah
 * pakai app ini sebelum fitur ini ada -- kalau [muxEnabled] true, Mux tetap
 * jalan sesuai heuristik pintar yang sudah ada di [com.example.tunnelapp.tunnel.XrayConfigBuilder]
 * sendiri (cuma aktif buat transport "tcp"/"grpc", MATI otomatis buat
 * transport keluarga HTTP seperti ws/httpupgrade/h2/http yang rawan konflik
 * sama reverse-proxy/CDN "bug host").
 *
 * Kalau user MATIKAN switch ini secara manual (muxEnabled = false), Mux
 * dipaksa OFF TOTAL untuk SEMUA transport tanpa kecuali -- override penuh,
 * buat kasus provider yang ternyata tetap bermasalah dengan Mux bahkan di
 * transport tcp/grpc (heuristik otomatis di atas tidak selalu bisa menebak
 * SEMUA kasus provider yang ada di lapangan).
 */
data class XraySettings(
    val muxEnabled: Boolean = true
)

object XraySettingsStore {
    private const val PREFS_NAME = "tunnelapp_xray_settings"
    private const val KEY_MUX_ENABLED = "mux_enabled"

    fun load(context: Context): XraySettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return XraySettings(
            muxEnabled = prefs.getBoolean(KEY_MUX_ENABLED, true)
        )
    }

    fun save(context: Context, settings: XraySettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MUX_ENABLED, settings.muxEnabled)
            .apply()
    }
}
