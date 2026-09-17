package com.example.tunnelapp.tunnel

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * FITUR BARU (permintaan user: "Statistik pemakaian data & kecepatan
 * real-time" -- total pemakaian JANGAN reset kalau app ditutup/di-kill
 * selama VPN masih konek).
 *
 * Jembatan real-time antara MyVpnService (yang benar-benar menghitung byte
 * lewat TrafficStats, lihat startTrafficPolling()/stopTrafficPolling() di
 * sana) dan DashboardMainFragment (yang cuma menampilkan [state] ke kartu
 * "Download"/"Upload") -- pola PERSIS sama seperti [StatusBus] di file ini
 * juga: in-memory singleton karena Service & Activity/Fragment jalan di
 * process yang sama, TIDAK perlu broadcast.
 *
 * SEBELUMNYA hitungan byte (baseline + total) disimpan sebagai variabel
 * lokal di DashboardMainFragment -- akibatnya begitu Fragment-nya
 * di-destroy (app di-swipe dari recent apps, di-kill sistem karena RAM
 * penuh, dll) totalnya ikut hilang & mulai dari 0 lagi walau VPN
 * (MyVpnService, foreground service yang independen dari layar manapun)
 * SEBENARNYA tetap jalan terus tanpa putus. Sekarang baseline+total
 * dipegang MyVpnService sendiri (hidup selama tunnel aktif, TIDAK
 * bergantung sama sekali pada Activity/Fragment mana pun yang sedang
 * kebetulan menampilkannya) -- Dashboard tinggal berlangganan [state],
 * jadi kalau app dibuka lagi total yang tampil tetap sambung dari
 * terakhir kali, BUKAN mulai dari 0.
 */
object TrafficStatsBus {

    /**
     * @param rxSpeedBps kecepatan unduh SAAT INI, byte/detik (dihitung dari
     *   selisih [rxTotalBytes] antar dua polling berturut-turut).
     * @param txSpeedBps kecepatan unggah SAAT INI, byte/detik.
     * @param rxTotalBytes total byte terunduh sejak tunnel INI mulai
     *   terhubung (baseline direset [MyVpnService] cuma sekali tiap kali
     *   user menekan Connect dari kondisi idle -- BUKAN tiap kali
     *   reconnect otomatis, supaya reconnect singkat di tengah sesi tidak
     *   bikin total tiba-tiba turun ke 0).
     * @param txTotalBytes total byte terunggah sejak tunnel INI mulai
     *   terhubung.
     * @param active false berarti tidak ada tunnel aktif sama sekali
     *   (idle) -- Dashboard pakai ini buat tahu kapan harus menampilkan
     *   0/grafik kosong alih-alih nilai terakhir yang sudah basi.
     */
    data class TrafficSnapshot(
        val rxSpeedBps: Float = 0f,
        val txSpeedBps: Float = 0f,
        val rxTotalBytes: Long = 0L,
        val txTotalBytes: Long = 0L,
        val active: Boolean = false
    )

    val state = MutableStateFlow(TrafficSnapshot())
}
