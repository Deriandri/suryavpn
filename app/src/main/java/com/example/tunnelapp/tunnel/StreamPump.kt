package com.example.tunnelapp.tunnel

import android.util.Log
import java.net.Socket

/** Menyalurkan data dua arah antara dua socket sampai salah satunya putus. */
object StreamPump {
    private const val TAG = "StreamPump"
    // FITUR BARU (maksimalkan kecepatan): sama seperti Socks5Server -- naik
    // dari 8KB -> 32KB, mengurangi jumlah syscall read()/write() per MB data.
    // REVERT (laporan user, sama seperti Socks5Server.kt): dikembalikan ke 8KB.
    private const val BUFFER_SIZE_BYTES = 8192

    /**
     * @param report opsional: dipanggil SEKALI per arah saat arah itu berhenti,
     *   berisi label arah, jumlah byte yang sempat lewat, alasan berhenti
     *   (EOF = sisi sumber menutup koneksi dengan normal, atau error) dan
     *   waktu sejak pump mulai. Dipakai untuk mendiagnosis siapa yang menutup
     *   koneksi lebih dulu. Baris yang muncul PERTAMA di log adalah penyebabnya;
     *   arah satunya biasanya ikut berhenti karena socket ditutup.
     */
    fun pumpBothWays(a: Socket, b: Socket, report: ((String) -> Unit)? = null) {
        val t0 = System.currentTimeMillis()
        Thread({ copy(a, b, "sshj -> server", t0, report) }, "pump-a-to-b").apply { isDaemon = true; start() }
        Thread({ copy(b, a, "server -> sshj", t0, report) }, "pump-b-to-a").apply { isDaemon = true; start() }
    }

    private fun copy(from: Socket, to: Socket, label: String, t0: Long, report: ((String) -> Unit)?) {
        var total = 0L
        var reason = "EOF (sumber menutup koneksi)"
        try {
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                output.flush()
                total += n
            }
        } catch (e: Exception) {
            reason = "error ${e.javaClass.simpleName}: ${e.message}"
            Log.d(TAG, "Pump berhenti: ${e.message}")
        } finally {
            report?.invoke("[Relay] $label berhenti: $reason, $total byte lewat, +${System.currentTimeMillis() - t0} ms")
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }
}
