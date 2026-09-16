package com.example.tunnelapp.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * FITUR BARU (pendukung Subscription auto-update): alarm dari AlarmManager
 * (lihat [SubscriptionAutoUpdateScheduler]) OTOMATIS DIBATALKAN sistem
 * setiap device reboot -- tanpa receiver ini, fitur "Auto-update
 * subscription" yang sudah dinyalakan user akan diam-diam berhenti jalan
 * begitu HP di-restart, TANPA ada tanda apa pun ke user (silent failure yang
 * paling menjengkelkan). Receiver ini memasang ULANG alarm-nya lagi kalau
 * memang [com.example.tunnelapp.model.VpnSettings.subscriptionAutoUpdateEnabled]
 * masih true & ada URL tersimpan -- lihat [SubscriptionAutoUpdateScheduler.rescheduleIfEnabled].
 *
 * TIDAK melakukan apa pun selain itu (TIDAK auto-connect VPN saat boot --
 * itu di luar cakupan fitur ini, dan sengaja tidak disentuh supaya tidak
 * mengubah perilaku start-up app yang sudah ada).
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SubscriptionAutoUpdateScheduler.rescheduleIfEnabled(context)
        }
    }
}
