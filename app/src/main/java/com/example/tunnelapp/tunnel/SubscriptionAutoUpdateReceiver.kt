package com.example.tunnelapp.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tunnelapp.model.VpnSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FITUR BARU (parity dengan V2RayNG "Update subscription otomatis"): dipicu
 * berkala oleh [SubscriptionAutoUpdateScheduler] lewat AlarmManager --
 * re-fetch [com.example.tunnelapp.model.VpnSettings.lastSubscriptionUrl] di
 * background, TANPA notifikasi/Toast apa pun (tidak ada Activity yang aktif
 * saat alarm ini bunyi, biasanya) -- akun baru dari hasil refresh langsung
 * muncul di daftar "Akun Tersimpan" (ConfigActivity) lain kali user membuka
 * app, sama seperti kalau di-import manual.
 *
 * goAsync() DIPERLUKAN di sini -- BroadcastReceiver.onReceive() normalnya
 * HARUS selesai dalam hitungan detik (kalau tidak, sistem anggap ANR),
 * padahal [SubscriptionImporter.importFromUrl] melakukan network I/O yang
 * bisa lebih lambat dari itu. goAsync() memberi jendela waktu tambahan
 * (~10 detik) untuk coroutine di Dispatchers.IO selesai sebelum
 * finish() dipanggil, tanpa perlu naikkan ini jadi foreground service segala.
 */
class SubscriptionAutoUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = VpnSettingsStore.load(context)
        if (!settings.subscriptionAutoUpdateEnabled || settings.lastSubscriptionUrl.isBlank()) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SubscriptionImporter.importFromUrl(context, settings.lastSubscriptionUrl)
            } catch (_: Exception) {
                // Silent by design -- ini background refresh berkala, bukan
                // aksi yang diminta user secara langsung saat itu juga. Kalau
                // memang URL-nya sudah tidak valid/provider down, biarkan
                // gagal senyap; import MANUAL (ToolsActivity) tetap akan
                // menampilkan error apa adanya kalau user coba lagi sendiri.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
