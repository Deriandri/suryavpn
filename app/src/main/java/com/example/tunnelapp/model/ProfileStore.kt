package com.example.tunnelapp.model

import android.content.Context
import java.util.UUID

/**
 * Satu akun tersimpan (SSH ATAU Xray -- dibedakan lewat [SavedConfig.modeIndex],
 * sama seperti sebelumnya), dengan [id] unik sebagai identitas permanennya
 * supaya tetap bisa dirujuk (di-edit/dihapus/dijadikan aktif) walau host/nama
 * server-nya kemudian diubah.
 */
data class SavedProfile(
    val id: String,
    val config: SavedConfig
)

/**
 * FIX/FITUR BARU (permintaan user): sebelumnya [ConfigStore] cuma bisa
 * menyimpan SATU profil aktif total -- kalau user menambah akun SSH baru,
 * akun Xray yang sudah tersimpan (atau sebaliknya) ikut ketiban/hilang dari
 * tampilan, karena keduanya berbagi slot SharedPreferences yang sama persis
 * (satu-satunya alasan SshConfigActivity/XrayConfigActivity dulu saling
 * "mempertahankan" field pasangannya lewat previous?.xxx sebelum menyimpan).
 *
 * [ProfileStore] ini menyimpan BANYAK akun sekaligus (SSH maupun Xray,
 * campur), masing-masing dengan id unik sendiri, ditambah SATU pointer
 * "akun aktif" (dipakai MyVpnService/DashboardMainFragment saat tombol
 * Connect ditekan). Dengan begini:
 *   - Menambah akun baru TIDAK menghapus akun lain yang sudah ada apa pun
 *     tipenya (SSH/Xray) -- setiap akun independen sepenuhnya.
 *   - User bisa punya banyak akun SSH dan/atau banyak akun Xray sekaligus,
 *     lalu pilih sendiri yang mana yang aktif dipakai buat Connect.
 *
 * Penyimpanan murni SharedPreferences (tanpa lib JSON tambahan supaya tidak
 * perlu dependency baru): daftar id disimpan sebagai CSV di key "ids", tiap
 * field SavedConfig disimpan per-profil dengan key diprefix "cfg_<id>_...".
 *
 * MIGRASI OTOMATIS: kalau ini pertama kali dibuka setelah update (belum
 * pernah ada key "ids" sama sekali) dan ada data lama di [ConfigStore]
 * (format single-slot sebelumnya), data itu otomatis dipindah jadi SATU
 * profil di sini dan langsung dijadikan aktif -- supaya akun yang sudah
 * dikonfigurasi user sebelum update TIDAK hilang/perlu dimasukkan ulang.
 */
object ProfileStore {
    private const val PREFS_NAME = "tunnelapp_profiles"
    private const val KEY_IDS = "ids"
    private const val KEY_ACTIVE_ID = "active_id"

    // --- API publik ---------------------------------------------------

    /** Semua akun tersimpan, urut sesuai urutan ditambahkan. */
    fun getAll(context: Context): List<SavedProfile> {
        migrateLegacyIfNeeded(context)
        val prefs = prefs(context)
        return idList(prefs).mapNotNull { id -> readConfig(prefs, id)?.let { SavedProfile(id, it) } }
    }

    fun get(context: Context, id: String): SavedProfile? {
        val prefs = prefs(context)
        return readConfig(prefs, id)?.let { SavedProfile(id, it) }
    }

    /**
     * Simpan akun baru ([id] null) atau timpa akun yang sudah ada ([id] terisi).
     * Mengembalikan id akun tersebut (id baru yang di-generate kalau [id] null).
     * Akun BARU pertama yang pernah ditambahkan otomatis dijadikan aktif
     * (supaya app "langsung bisa dipakai" tanpa langkah ekstra buat user
     * baru) -- akun baru berikutnya TIDAK otomatis menggantikan akun aktif
     * yang sudah dipilih user, harus lewat [setActiveId] secara eksplisit.
     */
    fun upsert(context: Context, id: String?, config: SavedConfig): String {
        val prefs = prefs(context)
        val isNew = id == null || !idList(prefs).contains(id)
        val realId = id ?: UUID.randomUUID().toString()

        val editor = prefs.edit()
        writeConfig(editor, realId, config)

        if (isNew) {
            val ids = idList(prefs).toMutableList()
            val wasEmpty = ids.isEmpty()
            ids.add(realId)
            editor.putString(KEY_IDS, ids.joinToString(","))
            if (wasEmpty) {
                editor.putString(KEY_ACTIVE_ID, realId)
            }
        }
        editor.apply()
        return realId
    }

    fun delete(context: Context, id: String) {
        val prefs = prefs(context)
        val ids = idList(prefs).toMutableList()
        if (!ids.remove(id)) return

        val editor = prefs.edit()
        clearConfig(editor, id)
        editor.putString(KEY_IDS, ids.joinToString(","))
        if (getActiveId(context) == id) {
            editor.putString(KEY_ACTIVE_ID, ids.firstOrNull())
        }
        editor.apply()
    }

    fun getActiveId(context: Context): String? {
        migrateLegacyIfNeeded(context)
        return prefs(context).getString(KEY_ACTIVE_ID, null)
    }

    fun setActiveId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    /**
     * Akun yang lagi aktif dipakai (dipanggil dari Dashboard saat Connect
     * ditekan & saat menampilkan ringkasan profil). Kalau pointer aktifnya
     * ternyata mengarah ke id yang sudah dihapus/rusak, otomatis jatuh balik
     * ke akun pertama yang ada di daftar (dan pointer-nya diperbaiki supaya
     * konsisten untuk pemanggilan berikutnya).
     */
    fun getActive(context: Context): SavedProfile? {
        val all = getAll(context)
        if (all.isEmpty()) return null

        val activeId = getActiveId(context)
        val found = all.firstOrNull { it.id == activeId }
        if (found != null) return found

        val fallback = all.first()
        setActiveId(context, fallback.id)
        return fallback
    }

    // --- Migrasi dari ConfigStore (format single-slot lama) ------------

    private fun migrateLegacyIfNeeded(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(KEY_IDS)) return // sudah pernah diinisialisasi, jangan migrasi ulang

        val legacy = ConfigStore.load(context)
        if (legacy == null) {
            // Tandai sudah "diinisialisasi" (daftar kosong) supaya baris ini
            // tidak dicek ulang tiap kali getAll()/getActiveId() dipanggil.
            prefs.edit().putString(KEY_IDS, "").apply()
            return
        }

        val id = UUID.randomUUID().toString()
        val editor = prefs.edit()
        writeConfig(editor, id, legacy)
        editor.putString(KEY_IDS, id)
        editor.putString(KEY_ACTIVE_ID, id)
        editor.apply()
    }

    // --- Helper serialisasi per-profil ----------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun idList(prefs: android.content.SharedPreferences): List<String> =
        prefs.getString(KEY_IDS, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun k(id: String, field: String) = "cfg_${id}_$field"

    private fun writeConfig(editor: android.content.SharedPreferences.Editor, id: String, c: SavedConfig) {
        editor.putString(k(id, "host"), c.host)
        editor.putInt(k(id, "port"), c.port)
        editor.putString(k(id, "username"), c.username)
        editor.putString(k(id, "password"), c.password)
        editor.putInt(k(id, "modeIndex"), c.modeIndex)
        editor.putString(k(id, "sni"), c.sni)
        editor.putString(k(id, "payload"), c.payload)
        editor.putString(k(id, "proxyHost"), c.proxyHost)
        editor.putString(k(id, "proxyPort"), c.proxyPort)
        editor.putString(k(id, "tlsVersion"), c.tlsVersion)
        editor.putBoolean(k(id, "useWebSocket"), c.useWebSocket)
        editor.putString(k(id, "wsPath"), c.wsPath)
        editor.putBoolean(k(id, "proxyRawMode"), c.proxyRawMode)
        editor.putString(k(id, "xrayLink"), c.xrayLink)
        editor.putString(k(id, "customHeaders"), c.customHeaders)
        editor.putBoolean(k(id, "ignoreCertErrors"), c.ignoreCertErrors)
        editor.putString(k(id, "dns1"), c.dns1)
        editor.putString(k(id, "dns2"), c.dns2)
    }

    private fun readConfig(prefs: android.content.SharedPreferences, id: String): SavedConfig? {
        val host = prefs.getString(k(id, "host"), null) ?: return null
        return SavedConfig(
            host = host,
            port = prefs.getInt(k(id, "port"), 22),
            username = prefs.getString(k(id, "username"), "").orEmpty(),
            password = prefs.getString(k(id, "password"), "").orEmpty(),
            modeIndex = prefs.getInt(k(id, "modeIndex"), 0),
            sni = prefs.getString(k(id, "sni"), "").orEmpty(),
            payload = prefs.getString(k(id, "payload"), "").orEmpty(),
            proxyHost = prefs.getString(k(id, "proxyHost"), "").orEmpty(),
            proxyPort = prefs.getString(k(id, "proxyPort"), "").orEmpty(),
            tlsVersion = prefs.getString(k(id, "tlsVersion"), "").orEmpty(),
            useWebSocket = prefs.getBoolean(k(id, "useWebSocket"), false),
            wsPath = prefs.getString(k(id, "wsPath"), "").orEmpty(),
            proxyRawMode = prefs.getBoolean(k(id, "proxyRawMode"), false),
            xrayLink = prefs.getString(k(id, "xrayLink"), "").orEmpty(),
            customHeaders = prefs.getString(k(id, "customHeaders"), "").orEmpty(),
            ignoreCertErrors = prefs.getBoolean(k(id, "ignoreCertErrors"), false),
            dns1 = prefs.getString(k(id, "dns1"), "").orEmpty(),
            dns2 = prefs.getString(k(id, "dns2"), "").orEmpty()
        )
    }

    private fun clearConfig(editor: android.content.SharedPreferences.Editor, id: String) {
        for (field in listOf(
            "host", "port", "username", "password", "modeIndex", "sni", "payload",
            "proxyHost", "proxyPort", "tlsVersion", "useWebSocket", "wsPath",
            "proxyRawMode", "xrayLink", "customHeaders", "ignoreCertErrors", "dns1", "dns2"
        )) {
            editor.remove(k(id, field))
        }
    }
}
