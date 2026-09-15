package com.example.tunnelapp

import android.app.Activity

/**
 * BARU (permintaan user, "bikin smooth untuk perpindahan menu"): sebelumnya
 * tiap tap item bottom nav (Dashboard/Config/Settings/Tools) di
 * [DashboardActivity], [ConfigActivity], [SettingsActivity], &
 * [ToolsActivity] cuma startActivity(...)/finish() polos -- Android
 * defaultnya langsung "memotong" ke layar berikutnya tanpa transisi sama
 * sekali (lihat rekaman layar laporan user: Settings -> Dashboard
 * berpindah seketika). Panggil fungsi ini PERSIS setelah startActivity(...)
 * (atau setelah finish() untuk kasus balik ke Dashboard yang sudah ada di
 * bawah back stack) di dalam setiap setupBottomNav() supaya transisinya
 * jadi crossfade halus -- lihat [R.anim.nav_fade_in]/[R.anim.nav_fade_out]
 * untuk detail animasinya.
 *
 * overridePendingTransition sudah deprecated sejak API 34 (diganti
 * overrideActivityTransition), tapi masih berfungsi penuh & tetap jadi cara
 * paling sederhana yang kompatibel sampai minSdk 28 di app ini -- makanya
 * @Suppress di sini, bukan pakai API barunya (yang baru berlaku dari API 34
 * ke atas saja, akan meninggalkan pengguna Android di bawahnya tanpa
 * animasi).
 */
@Suppress("DEPRECATION")
fun Activity.applyNavFadeTransition() {
    overridePendingTransition(R.anim.nav_fade_in, R.anim.nav_fade_out)
}
