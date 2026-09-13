package com.example.tunnelapp.model

import android.content.Context

/**
 * FITUR BARU (permintaan user, "cloud config"): pengaturan sinkronisasi
 * konfigurasi dari URL online, supaya akun tidak perlu diperbarui manual
 * lewat Impor tiap kali server/provider konfig mengganti detailnya.
 *
 * [cloudUrl] menunjuk ke sebuah teks yang formatnya SAMA PERSIS dengan
 * hasil "Ekspor Semua" (lihat [profilesToJson]) ATAU satu kode bagikan
 * "SVPN1:..." (lihat [buildShareCode]) -- keduanya diterima apa adanya
 * lewat [importConfigsFromText] yang sudah ada, jadi tidak perlu format
 * baru sama sekali. Pemilik server konfig cukup meng-host file JSON hasil
 * Ekspor Semua di URL statis (GitHub raw, hosting sendiri, dst) dan
 * memperbaruinya kapan saja -- app akan mengambil isi TERBARU tiap kali
 * disinkron, tanpa user perlu impor ulang manual.
 *
 * [managedProfileIds] adalah id-id akun ([ProfileStore]) yang dibuat/
 * diperbarui oleh sinkron TERAKHIR -- dipakai [CloudConfigSync] supaya
 * sinkron berikutnya tahu akun mana yang "milik cloud" (boleh diperbarui/
 * dihapus otomatis kalau sudah tidak ada di cloud lagi) dan mana yang
 * ditambahkan manual oleh user sendiri (tidak boleh disentuh sama sekali).
 */
data class CloudSyncSettings(
    val cloudUrl: String = "",
    val autoSyncEnabled: Boolean = false,
    val lastSyncTimeMillis: Long = 0L,
    val lastSyncSummary: String = "",
    val managedProfileIds: List<String> = emptyList()
)

object CloudSyncStore {
    private const val PREFS_NAME = "tunnelapp_cloud_sync"
    private const val KEY_CLOUD_URL = "cloud_url"
    private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_SYNC_SUMMARY = "last_sync_summary"
    private const val KEY_MANAGED_IDS = "managed_ids"

    fun load(context: Context): CloudSyncSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CloudSyncSettings(
            cloudUrl = prefs.getString(KEY_CLOUD_URL, "").orEmpty(),
            autoSyncEnabled = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false),
            lastSyncTimeMillis = prefs.getLong(KEY_LAST_SYNC_TIME, 0L),
            lastSyncSummary = prefs.getString(KEY_LAST_SYNC_SUMMARY, "").orEmpty(),
            managedProfileIds = prefs.getString(KEY_MANAGED_IDS, "").orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        )
    }

    /** Simpan hanya URL & toggle auto-sync (dipanggil dari dialog pengaturan). */
    fun saveSettings(context: Context, cloudUrl: String, autoSyncEnabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLOUD_URL, cloudUrl)
            .putBoolean(KEY_AUTO_SYNC_ENABLED, autoSyncEnabled)
            .apply()
    }

    /** Simpan hasil sinkron (dipanggil dari [CloudConfigSync] tiap kali selesai sync). */
    fun saveSyncResult(
        context: Context,
        managedProfileIds: List<String>,
        summary: String,
        timeMillis: Long
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANAGED_IDS, managedProfileIds.joinToString(","))
            .putString(KEY_LAST_SYNC_SUMMARY, summary)
            .putLong(KEY_LAST_SYNC_TIME, timeMillis)
            .apply()
    }
}
