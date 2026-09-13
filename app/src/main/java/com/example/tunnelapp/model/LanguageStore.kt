package com.example.tunnelapp.model

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * FITUR BARU (permintaan user, "penggantian bahasa" + "tambahkan bahasa
 * Inggris"): pengaturan bahasa tampilan aplikasi, diisi lewat layar
 * Pengaturan -> kartu "Bahasa" (lihat SettingsActivity &
 * activity_settings.xml), diletakkan TEPAT DI ATAS kartu "Pengaturan Dasar"
 * sesuai permintaan.
 *
 * Pola & alasannya SAMA PERSIS seperti [ThemeStore] (tema Terang/Gelap/
 * Ikuti Sistem): SharedPreferences biasa (bukan terenkripsi) karena ini
 * cuma preferensi tampilan, bukan data akun/kredensial.
 *
 * Penerapannya lewat [AppCompatDelegate.setApplicationLocales] (API
 * "per-app language" dari AndroidX Core 1.6+ / AppCompat 1.6+, tersedia di
 * project ini lewat appcompat 1.7.0) -- sama seperti setDefaultNightMode,
 * pemanggilan ini otomatis me-recreate SEMUA Activity yang lagi terbuka
 * supaya teks langsung berubah tanpa perlu tutup-buka app manual. Dipanggil
 * SEKALI paling awal di [com.example.tunnelapp.TunnelApplication.onCreate]
 * (biar tidak "kedip" ke bahasa lama dulu di frame pertama), DAN dipanggil
 * ulang tiap kali user ganti pilihan di layar Pengaturan.
 *
 * CATATAN JUJUR soal cakupan: teks yang benar-benar ikut berubah lewat
 * mekanisme ini HANYA teks yang sudah diambil dari resource @string (lihat
 * res/values/strings.xml vs res/values-en/strings.xml) -- untuk sekarang itu
 * mencakup layar Pengaturan ini sendiri + label bilah navigasi bawah
 * (Dashboard/Konfigurasi/Pengaturan/Tools) yang dipakai di semua layar.
 * Layar lain (Dashboard, Konfigurasi, Tools) belum di-ekstrak ke @string
 * resource sepenuhnya, jadi sebagian teksnya untuk saat ini masih tetap
 * berbahasa Indonesia walau bahasa aplikasi sudah diganti ke English --
 * ini BUKAN bug di mekanisme LanguageStore, cuma pekerjaan ekstraksi string
 * yang belum menjangkau semua layar.
 */
enum class LanguageMode {
    INDONESIAN,
    ENGLISH;

    fun toLocaleTag(): String = when (this) {
        INDONESIAN -> "id"
        ENGLISH -> "en"
    }
}

object LanguageStore {
    private const val PREFS_NAME = "tunnelapp_language_settings"
    private const val KEY_LANGUAGE_MODE = "language_mode"

    /** Default: Indonesia -- bahasa asli aplikasi ini sejak awal. */
    private val DEFAULT_MODE = LanguageMode.INDONESIAN

    fun load(context: Context): LanguageMode {
        val raw = prefs(context).getString(KEY_LANGUAGE_MODE, null) ?: return DEFAULT_MODE
        return runCatching { LanguageMode.valueOf(raw) }.getOrDefault(DEFAULT_MODE)
    }

    fun save(context: Context, mode: LanguageMode) {
        prefs(context).edit().putString(KEY_LANGUAGE_MODE, mode.name).apply()
    }

    /** Terapkan mode tersimpan ke seluruh app -- dipanggil dari TunnelApplication.onCreate. */
    fun applySaved(context: Context) {
        applyLocale(load(context))
    }

    /** Simpan lalu langsung terapkan -- dipanggil dari SettingsActivity saat user memilih. */
    fun saveAndApply(context: Context, mode: LanguageMode) {
        save(context, mode)
        applyLocale(mode)
    }

    private fun applyLocale(mode: LanguageMode) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(mode.toLocaleTag())
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
