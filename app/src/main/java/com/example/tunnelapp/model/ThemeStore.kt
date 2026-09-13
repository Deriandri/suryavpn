package com.example.tunnelapp.model

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * FITUR BARU (permintaan user, "tema dark"): pengaturan tampilan
 * Terang/Gelap/Ikuti Sistem, diisi lewat layar Pengaturan -> kartu
 * "Tampilan" (lihat SettingsActivity & activity_settings.xml).
 *
 * SharedPreferences biasa (bukan terenkripsi seperti ProfileStore) karena
 * ini cuma preferensi tampilan, bukan data akun/kredensial -- sama seperti
 * [GeneralSettingsStore] & [VpnSettingsStore].
 *
 * Penerapannya lewat [AppCompatDelegate.setDefaultNightMode], dipanggil
 * SEKALI paling awal di [com.example.tunnelapp.TunnelApplication.onCreate]
 * (supaya semua Activity langsung terbuka dengan mode yang benar sejak
 * frame pertama, tidak "kedip" dulu ke mode lama), DAN dipanggil ulang tiap
 * kali user mengganti pilihan di layar Pengaturan (lihat
 * SettingsActivity.applyThemeChoice) -- AppCompatDelegate otomatis
 * me-recreate seluruh Activity yang lagi terbuka saat mode berubah.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM;

    fun toNightMode(): Int = when (this) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}

object ThemeStore {
    private const val PREFS_NAME = "tunnelapp_theme_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    /** Default: ikut sistem -- paling ramah untuk user baru yang belum pernah pilih. */
    private val DEFAULT_MODE = ThemeMode.SYSTEM

    fun load(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME_MODE, null) ?: return DEFAULT_MODE
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(DEFAULT_MODE)
    }

    fun save(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /** Terapkan mode tersimpan ke seluruh app -- dipanggil dari TunnelApplication.onCreate. */
    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(load(context).toNightMode())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
