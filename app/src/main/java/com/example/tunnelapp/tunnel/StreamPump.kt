package com.example.tunnelapp.tunnel

import android.util.Log
import java.net.Socket

/** Menyalurkan data dua arah antara dua socket sampai salah satunya putus. */
object StreamPump {
    private const val TAG = "StreamPump"

    fun pumpBothWays(a: Socket, b: Socket) {
        Thread({ copy(a, b) }, "pump-a-to-b").apply { isDaemon = true; start() }
        Thread({ copy(b, a) }, "pump-b-to-a").apply { isDaemon = true; start() }
    }

    private fun copy(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(8192)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Pump berhenti: ${e.message}")
        } finally {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
        }
    }
}
