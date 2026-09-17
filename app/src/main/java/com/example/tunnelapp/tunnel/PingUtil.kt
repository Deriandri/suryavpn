package com.example.tunnelapp.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Hasil satu kali pengukuran ping.
 *
 *  - [success] true kalau socket berhasil connect ke host:port dalam batas [timeoutMs].
 *  - [latencyMs] waktu tempuh (round-trip TCP connect) dalam milidetik, null kalau gagal.
 *  - [errorMessage] alasan gagal (timeout / host tidak ditemukan / dst), null kalau sukses.
 */
data class PingResult(
    val success: Boolean,
    val latencyMs: Long?,
    val errorMessage: String? = null
)

/**
 * Cek ping sederhana lewat TCP connect ke host:port target (BUKAN ICMP echo/ping asli).
 *
 * Sengaja pakai TCP connect timing, bukan ICMP (java.net.InetAddress.isReachable atau
 * perintah /system/bin/ping) -- banyak provider seluler & firewall server memblokir ICMP
 * echo request sepenuhnya, sehingga hasil ICMP sering keliru menunjukkan "gagal" walau
 * server sebenarnya hidup & port SSH/Xray-nya bisa dijangkau. TCP connect ke port yang
 * benar-benar dipakai server (port SSH atau port Xray) jauh lebih mencerminkan kualitas
 * koneksi yang sebenarnya akan dipakai tunnel.
 */
object PingUtil {
    /**
     * @param protect FITUR BARU (permintaan user, "cek ping sebelum & sesudah
     *                connect"): kalau diisi, dipanggil ke socket ini SEBELUM
     *                connect() -- dipakai [ConfigActivity.onTestPingAllClicked]
     *                lewat [com.example.tunnelapp.tunnel.MyVpnService.protectSocketIfRunning]
     *                supaya kalau ADA tunnel yang sedang aktif saat tes ping
     *                dijalankan, socket tes ini TETAP langsung ke internet asli
     *                (persis seperti [com.example.tunnelapp.tunnel.MyVpnService.pingHost]
     *                yang sudah lebih dulu ada untuk host tunnel aktif) --
     *                BUKAN ikut tertarik masuk TUN VpnService milik app ini
     *                sendiri, yang kalau dibiarkan bakal menghasilkan angka
     *                latency yang salah (nebeng keluar-masuk tunnel yang
     *                sedang jalan) atau malah gagal total. Null/default
     *                (tidak ada tunnel aktif) berarti socket biasa memang
     *                sudah langsung ke internet asli, tidak perlu apa-apa.
     */
    suspend fun tcpPing(
        host: String,
        port: Int,
        timeoutMs: Int = 4000,
        protect: ((Socket) -> Unit)? = null
    ): PingResult =
        withContext(Dispatchers.IO) {
            if (host.isBlank()) {
                return@withContext PingResult(false, null, "Host belum diisi")
            }
            val socket = Socket()
            try {
                protect?.invoke(socket)
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                PingResult(true, elapsedMs)
            } catch (e: Exception) {
                PingResult(false, null, e.message ?: "Tidak dapat menjangkau $host:$port")
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }
}
