package com.example.tunnelapp.tunnel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.tunnelapp.model.VpnSettingsStore

/**
 * FITUR BARU (parity dengan V2RayNG "Update subscription otomatis"):
 * pasang/batalkan alarm berkala yang memicu [SubscriptionAutoUpdateReceiver]
 * untuk re-fetch [com.example.tunnelapp.model.VpnSettings.lastSubscriptionUrl]
 * tanpa user perlu buka app & tempel URL manual lagi.
 *
 * SENGAJA pakai AlarmManager (API bawaan Android), BUKAN WorkManager --
 * project ini belum punya dependency androidx.work sama sekali, dan
 * menambah dependency baru butuh Gradle sync yang tidak bisa saya
 * verifikasi di lingkungan ini (tidak ada Android SDK/network). AlarmManager
 * sudah cukup untuk kebutuhan "refresh berkala" sesederhana ini.
 *
 * setInexactRepeating (BUKAN exact) sengaja dipilih -- refresh subscription
 * bukan operasi yang butuh presisi detik/menit, jadi biarkan sistem
 * menggabungkan alarm ini dengan alarm app lain untuk hemat baterai (sama
 * seperti rekomendasi resmi dokumentasi AlarmManager Android sendiri untuk
 * kasus "berkala, tidak time-critical").
 *
 * CATATAN: alarm dari AlarmManager (baik exact maupun inexact) OTOMATIS
 * DIBATALKAN sistem setiap device reboot -- lihat [BootCompletedReceiver]
 * yang memasang ulang alarm ini kalau auto-update sedang aktif.
 */
object SubscriptionAutoUpdateScheduler {

    private const val REQUEST_CODE = 4821

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SubscriptionAutoUpdateReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Dipanggil setiap kali toggle "Auto-update" dinyalakan ATAU interval jam-nya diubah. */
    fun schedule(context: Context, intervalHours: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalMillis = intervalHours.coerceIn(1, 24 * 14) * 60L * 60L * 1000L
        val triggerAt = System.currentTimeMillis() + intervalMillis
        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            triggerAt,
            intervalMillis,
            pendingIntent(context)
        )
    }

    /** Dipanggil setiap kali toggle "Auto-update" dimatikan. */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
    }

    /** Dipanggil [BootCompletedReceiver] -- pasang ulang alarm HANYA kalau memang sedang aktif & ada URL tersimpan. */
    fun rescheduleIfEnabled(context: Context) {
        val settings = VpnSettingsStore.load(context)
        if (settings.subscriptionAutoUpdateEnabled && settings.lastSubscriptionUrl.isNotBlank()) {
            schedule(context, settings.subscriptionUpdateIntervalHours)
        }
    }
}
