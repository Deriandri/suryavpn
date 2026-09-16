package com.example.tunnelapp.tunnel

import android.util.Log
import java.net.Socket
import java.net.SocketTimeoutException

/** Menyalurkan data dua arah antara dua socket sampai salah satunya putus. */
object StreamPump {
    private const val TAG = "StreamPump"
    // FITUR BARU (maksimalkan kecepatan): sama seperti Socks5Server -- naik
    // dari 8KB -> 32KB, mengurangi jumlah syscall read()/write() per MB data.
    // REVERT (laporan user, sama seperti Socks5Server.kt): dikembalikan ke 8KB.
    private const val BUFFER_SIZE_BYTES = 8192

    fun pumpBothWays(a: Socket, b: Socket) {
        Thread({ copy(a, b) }, "pump-a-to-b").apply { isDaemon = true; start() }
        Thread({ copy(b, a) }, "pump-b-to-a").apply { isDaemon = true; start() }
    }

    private fun copy(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (e: SocketTimeoutException) {
            // FIX (lihat TUNNEL_READ_TIMEOUT_MS di ConnectRelay): timeout di sini
            // artinya socket asli tidak menerima APA PUN (bukan cuma idle biasa --
            // keepalive SSH 30 detik harusnya selalu bikin sesuatu terbaca kalau
            // koneksi memang masih hidup) -- ini sinyal koneksi sudah mati diam-diam
            // (mis. jaringan device berpindah/putus-sambung). Dicatat ke StatusBus
            // (bukan cuma Logcat) supaya kelihatan jelas di layar Log app kalau ini
            // yang memicu reconnect otomatis, bukan sekadar "putus" generik.
            Log.d(TAG, "Pump berhenti (timeout, kemungkinan koneksi mati diam-diam): ${e.message}")
            StatusBus.log("Tidak ada data lewat tunnel dalam waktu lama -- kemungkinan koneksi mati diam-diam (jaringan berpindah/putus-sambung), memutus tunnel")
        } catch (e: Exception) {
            Log.d(TAG, "Pump berhenti: ${e.message}")
        } finally {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }
}
