package com.example.tunnelapp.model

import android.content.Context

/**
 * FITUR BARU (permintaan user, "log debug di menu Pengaturan, default off,
 * pengamat log dengan detail error yang tinggi"): penyimpanan SATU flag
 * boolean [KEY_DEBUG_LOG_ENABLED] -- SENGAJA default `false` (opt-in),
 * karena tujuannya cuma alat bantu troubleshooting saat dibutuhkan, bukan
 * sesuatu yang jalan terus menerus menulis ke disk untuk semua user.
 *
 * Dipisah dari [GeneralSettingsStore]/[VpnSettingsStore] (SharedPreferences
 * sendiri) supaya independen -- lihat [com.example.tunnelapp.tunnel.DebugLog]
 * untuk logika observer-nya sendiri (bukan cuma penyimpanan pengaturan).
 */
object DebugLogStore {
    private const val PREFS_NAME = "tunnelapp_debug_log"
    private const val KEY_DEBUG_LOG_ENABLED = "debug_log_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOG_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_LOG_ENABLED, enabled)
            .apply()
    }
}
