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
    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = 4000): PingResult =
        withContext(Dispatchers.IO) {
            if (host.isBlank()) {
                return@withContext PingResult(false, null, "Host belum diisi")
            }
            val socket = Socket()
            val start = System.nanoTime()
            try {
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
