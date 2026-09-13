package com.example.tunnelapp.model

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * FITUR BARU (permintaan user, "tambahkan bahasa Inggris"): pengaturan
 * bahasa tampilan aplikasi (Indonesia/English), diisi lewat layar
 * Pengaturan -> kartu "Bahasa" (lihat SettingsActivity & activity_settings.xml),
 * diletakkan tepat di atas kartu "Pengaturan Dasar" sesuai permintaan user.
 *
 * Beda dari [ThemeStore] yang nyimpen preferensi sendiri lewat
 * SharedPreferences manual, di sini dipakai API "per-app language" bawaan
 * AndroidX ([AppCompatDelegate.setApplicationLocales]), soalnya:
 *  - Androidx SENDIRI yang otomatis menyimpan & mengembalikan pilihan user
 *    tiap app dibuka lagi ("auto store locales", tidak perlu
 *    SharedPreferences manual milik kita sendiri).
 *  - Di Android 13+ ini otomatis nyambung ke menu bahasa per-app bawaan
 *    sistem (Setelan HP -> Aplikasi -> SuryaVPN -> Bahasa).
 *  - Sama seperti [ThemeMode], ganti pilihan otomatis me-recreate semua
 *    Activity yang lagi terbuka supaya semua teks langsung berubah tanpa
 *    perlu tutup-buka app manual.
 */
enum class AppLanguage(val languageTag: String) {
    INDONESIAN("in"),
    ENGLISH("en")
}

object LocaleStore {

    /**
     * Default aplikasi ini SEBELUM fitur ini ada: selalu Indonesia, berapa
     * pun bahasa HP-nya (dulu semua teks hardcode Indonesia) -- tetap
     * dipertahankan sebagai default supaya user lama tidak kaget tiba-tiba
     * lihat bahasa Inggris cuma gara-gara HP-nya berbahasa Inggris.
     */
    private val DEFAULT_LANGUAGE = AppLanguage.INDONESIAN

    /**
     * Terapkan default HANYA kalau user belum PERNAH memilih bahasa sama
     * sekali -- dipanggil sekali di TunnelApplication.onCreate, mirip
     * [ThemeStore.applySaved]. Kalau sudah pernah dipilih (baik Indonesia
     * atau English), AndroidX sendiri yang otomatis mengembalikan pilihan
     * itu jauh sebelum baris ini sempat jalan, jadi tidak akan menimpa
     * pilihan user.
     */
    fun applyDefaultIfUnset() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            apply(DEFAULT_LANGUAGE)
        }
    }

    /** Bahasa yang aktif sekarang, dibaca dari locale aplikasi saat ini. */
    fun current(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        val activeTag = if (locales.isEmpty) DEFAULT_LANGUAGE.languageTag else locales[0]?.language
        return AppLanguage.values().firstOrNull { it.languageTag == activeTag } ?: DEFAULT_LANGUAGE
    }

    /** Simpan & terapkan pilihan bahasa baru ke seluruh aplikasi. */
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.languageTag)
        )
    }
}
