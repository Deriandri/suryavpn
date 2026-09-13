package com.example.tunnelapp

import android.app.Application
import com.example.tunnelapp.model.LocaleStore
import com.example.tunnelapp.model.ThemeStore

/**
 * FITUR BARU (permintaan user, "tema dark"): Application class baru,
 * satu-satunya tugasnya menerapkan mode Terang/Gelap/Ikuti Sistem yang
 * tersimpan lewat [ThemeStore] SEBELUM Activity mana pun dibuat.
 *
 * PENTING kenapa ini harus di Application, bukan cukup di masing-masing
 * Activity.onCreate: AppCompatDelegate.setDefaultNightMode() butuh
 * dipanggil sedini mungkin di lifecycle proses, idealnya sebelum Activity
 * pertama sempat inflate layout-nya sama sekali -- kalau baru dipanggil di
 * dalam onCreate() Activity (walau sebelum super.onCreate()), pada
 * beberapa kondisi (mis. Activity dibuka lewat proses yang baru saja start
 * dari notifikasi/deep link) tetap ada risiko "kedip" sesaat ke mode lama
 * sebelum berubah. Didaftarkan lewat android:name di AndroidManifest.xml.
 */
class TunnelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeStore.applySaved(this)
        // FITUR BARU (permintaan user, "tambahkan bahasa Inggris"): lihat
        // LocaleStore -- cukup terapkan default kalau user belum pernah
        // memilih bahasa, AndroidX sendiri yang mengembalikan pilihan lama.
        LocaleStore.applyDefaultIfUnset()
    }
}
