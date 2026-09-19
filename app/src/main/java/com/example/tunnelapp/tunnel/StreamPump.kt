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
    private const val DUMP_CHUNKS = 3
    private const val DUMP_PREVIEW_BYTES = 48

    /** Tampilkan [n] byte pertama (maks [DUMP_PREVIEW_BYTES]) sebagai teks, byte non-cetak jadi \xNN. */
    private fun preview(buf: ByteArray, n: Int): String {
        val sb = StringBuilder()
        val limit = minOf(n, DUMP_PREVIEW_BYTES)
        for (i in 0 until limit) {
            val v = buf[i].toInt() and 0xFF
            when {
                v == 13 -> sb.append("\\r")
                v == 10 -> sb.append("\\n")
                v in 32..126 -> sb.append(v.toChar())
                else -> sb.append("\\x").append(String.format("%02x", v))
            }
        }
        if (n > limit) sb.append("...")
        return sb.toString()
    }

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
        var chunkNo = 0
        var reason = "EOF (sumber menutup koneksi)"
        try {
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                // Diagnosis: 3 potongan pertama tiap arah dicatat (waktu, ukuran,
                // dan 48 byte pertama). Ini fase awal handshake SSH (ident dan
                // KEXINIT, belum terenkripsi), jadi tidak memuat kredensial.
                if (chunkNo < DUMP_CHUNKS) {
                    chunkNo++
                    report?.invoke(
                        "[Relay] $label #$chunkNo +${System.currentTimeMillis() - t0} ms ($n byte): " +
                            preview(buffer, n)
                    )
                }
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
