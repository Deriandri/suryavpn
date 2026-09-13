package com.example.tunnelapp.model

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * FITUR BARU (permintaan user, "enkripsi file konfig" / "kunci master").
 *
 * Ini kunci MASTER yang dipakai untuk benar-benar mengenkripsi file
 * penyimpanan akun di disk (beda dari fitur kunci-per-akun sebelumnya, yang
 * cuma menahan tombol Edit/Hapus di UI -- lihat [SavedConfig.isLocked]).
 * Dengan ini, isi file SharedPreferences (host, username, password, dst)
 * tersimpan dalam bentuk TERENKRIPSI (AES-256-GCM) di disk, bukan teks
 * biasa yang bisa dibaca langsung siapa pun yang punya akses ke file
 * `/data/data/<package>/shared_prefs/`.
 *
 * PENTING -- kenapa TIDAK ada "file kunci master" terpisah yang bisa
 * dipindah/dibuka manual di luar aplikasi: kuncinya dibuat & disimpan lewat
 * [MasterKey], yang hidup di ANDROID KEYSTORE milik perangkat (hardware-
 * backed kalau perangkatnya mendukung) -- BUKAN sebagai file yang bisa
 * disalin. Ini justru intinya: kalau kuncinya ada sebagai file biasa yang
 * bisa "dibuka" di luar aplikasi, siapa pun yang mendapat file itu juga
 * bisa membaca semua password akun tersimpan Anda -- enkripsinya jadi
 * percuma. Karena kuncinya terikat ke Keystore aplikasi ini di perangkat
 * ini, dekripsinya OTOMATIS & TRANSPARAN cuma dari DALAM aplikasi ini
 * sendiri: buka Konfigurasi seperti biasa, akun-akun tetap kebaca/kelola
 * normal (lihat & edit lewat UI yang sudah ada) -- tidak perlu & tidak ada
 * langkah "buka file" manual terpisah.
 *
 * Kalau nanti butuh MEMPERBAIKI/melihat isi mentah file ini secara manual
 * (mis. lewat adb, untuk debug), satu-satunya cara sah adalah dari
 * *aplikasi yang sama, sign yang sama, perangkat yang sama* -- persis
 * seperti kalau melihatnya lewat layar Konfigurasi ini.
 */
object SecurePrefsFactory {

    /**
     * Bikin (atau buka yang sudah ada) SharedPreferences terenkripsi dengan
     * nama file [fileName]. Aman dipanggil berkali-kali -- MasterKey akan
     * dipakai ulang (bukan dibuat baru tiap panggilan) selama key alias-nya
     * sama, jadi file yang sama tetap bisa dibuka lagi.
     */
    fun create(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
