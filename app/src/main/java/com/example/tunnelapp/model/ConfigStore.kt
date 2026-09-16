package com.example.tunnelapp.model

import android.content.Context

/**
 * Menyimpan akun/konfigurasi server terakhir yang dipakai, supaya tidak
 * hilang setiap kali aplikasi ditutup lalu dibuka lagi (SharedPreferences
 * bersifat persisten di disk, tidak seperti variabel di memori Activity
 * yang otomatis hilang saat Activity dihancurkan).
 *
 * Catatan: password disimpan di SharedPreferences biasa (bukan terenkripsi).
 * Untuk MVP ini cukup, tapi untuk rilis produksi sebaiknya pindah ke
 * EncryptedSharedPreferences (androidx.security.crypto).
 */
data class SavedConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    // 0 = SSH, 1 = SSH SSL, 2 = SSH TLS Payload Proxy, 3 = Payload + Remote Proxy
    // (ConnectionMode.REMOTE_PROXY, proxy wajib + payload opsional, TLS hanya
    // kalau proxyRawMode aktif), 4 = Enhanced (legacy, tidak dipakai dari UI),
    // 5 = Xray
    val modeIndex: Int,
    val sni: String,
    val payload: String,
    val proxyHost: String = "",
    val proxyPort: String = "",
    // "Default"/kosong = biarkan sistem pilih otomatis. Selain itu: "TLSv1"/"TLSv1.1"/"TLSv1.2"/"TLSv1.3".
    val tlsVersion: String = "",
    // Toggle WebSocket independen -- bisa aktif dikombinasikan dengan mode APA PUN di atas.
    val useWebSocket: Boolean = false,
    // Path HTTP untuk handshake WebSocket (dipakai kalau useWebSocket true). Kosong = "/".
    val wsPath: String = "",
    // true = proxyHost:proxyPort raw passthrough (tanpa CONNECT), lihat ServerConfig.proxyRawMode.
    val proxyRawMode: Boolean = false,
    // Link share Xray (vmess://, vless://, trojan://), khusus modeIndex == 5.
    val xrayLink: String = "",
    // Header HTTP tambahan (satu per baris, format "Nama: Nilai") yang disisipkan
    // ke request CONNECT proxy & handshake WebSocket. Lihat ServerConfig.customHeaders.
    val customHeaders: String = "",
    // true = abaikan error sertifikat TLS (self-signed/kedaluwarsa/nama tidak cocok).
    // Lihat ServerConfig.ignoreCertErrors.
    val ignoreCertErrors: Boolean = false,
    // DNS custom untuk TUN interface. Kosong = pakai default "1.1.1.1" di MyVpnService.
    // Lihat ServerConfig.dns1/dns2.
    val dns1: String = "",
    val dns2: String = "",
    // FITUR BARU (permintaan user): nama akun custom, murni tampilan (daftar
    // "Akun Tersimpan" di ConfigActivity & pemilih akun di Dashboard) --
    // TIDAK memengaruhi logika koneksi sama sekali. Kosong = fallback ke
    // host:port (SSH) atau remark/address:port (Xray), sama seperti perilaku
    // sebelum fitur ini ada.
    val accountName: String = "",
    // FITUR BARU (permintaan user, "kunci akun"): kalau true, akun ini
    // tidak boleh diedit atau dihapus lewat UI (tombol pensil/hapus di
    // ConfigActivity dinonaktifkan & dipudarkan) sampai dibuka kuncinya
    // lagi lewat ikon gembok di baris yang sama -- murni proteksi UI dari
    // ketidaksengajaan, TIDAK mengenkripsi/menyembunyikan data & TIDAK
    // memengaruhi logika koneksi sama sekali.
    val isLocked: Boolean = false,
    // FITUR BARU (permintaan user, "kunci konfig saat ekspor seperti HTTP
    // Custom"): lihat dokumentasi lengkap di [ConfigLockMode]. NONE untuk
    // akun biasa yang dibuat langsung di app ini. Nilai selain NONE hanya
    // muncul di akun hasil IMPOR dari file/kode yang sengaja dikunci saat
    // diekspor -- menandakan akun ini "terkunci total" di UI (lihat
    // ConfigActivity.bindAccountRow): cuma nama akunnya yang ditampilkan,
    // layar Edit tidak bisa dibuka sama sekali. Beda dari [isLocked] di atas
    // yang murni proteksi ketidaksengajaan dan bisa dibuka lagi kapan pun
    // oleh pemilik HP -- lockMode TIDAK bisa "dibuka" dari UI karena memang
    // dimaksudkan supaya penerima akun (mis. dari penjual konfig) tidak bisa
    // mengintip/menyalin kredensial & trik aslinya.
    val lockMode: ConfigLockMode = ConfigLockMode.NONE,
    // FITUR BARU (permintaan user, "form nama & catatan sebelum ekspor" +
    // "catatan otomatis tampil di menu Catatan Dashboard saat diimpor"):
    // catatan bebas (boleh berisi HTML) yang diisi lewat
    // ConfigActivity.showExportDetailsDialog saat akun ini diekspor --
    // ikut tersimpan di JSON ekspor (lihat ConfigIO.toConfigJson field
    // "note") supaya saat akun ini DIIMPOR di HP lain, catatannya ikut
    // terbawa & otomatis ditampilkan di kartu "Catatan" Dashboard
    // (DashboardMainFragment.renderCatatan) MENGGANTIKAN tampilan log
    // koneksi -- tapi HANYA kalau field ini terisi; kosong = kartu
    // Catatan tetap menampilkan log koneksi seperti biasa. Kosong = akun
    // biasa yang belum pernah diberi catatan saat ekspor.
    val note: String = "",
    // ==== FITUR BARU: Editor JSON Xray manual (parity dengan V2RayNG) ====
    // Lihat dokumentasi lengkap di [ServerConfig.useRawXrayJson]/[ServerConfig.rawXrayJson]
    // -- dua field ini murni cerminan penyimpanannya di [SavedConfig], TIDAK
    // ada logika tambahan di sini (logikanya semua di
    // [com.example.tunnelapp.tunnel.XrayTunnelManager] & [toServerConfigOrNull]).
    // Kosong/false = perilaku lama sama sekali tidak berubah (akun Xray
    // biasa tetap dibangun dari [xrayLink] seperti sebelumnya).
    val useRawXrayJson: Boolean = false,
    val rawXrayJson: String = ""
)

object ConfigStore {
    private const val PREFS_NAME = "tunnelapp_prefs"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_MODE_INDEX = "mode_index"
    private const val KEY_SNI = "sni"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_TLS_VERSION = "tls_version"
    private const val KEY_WEBSOCKET_ENABLED = "websocket_enabled"
    private const val KEY_WS_PATH = "ws_path"
    private const val KEY_PROXY_RAW_MODE = "proxy_raw_mode"
    private const val KEY_XRAY_LINK = "xray_link"
    private const val KEY_CUSTOM_HEADERS = "custom_headers"
    private const val KEY_IGNORE_CERT_ERRORS = "ignore_cert_errors"
    private const val KEY_DNS1 = "dns1"
    private const val KEY_DNS2 = "dns2"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_LOCK_MODE = "lock_mode"

    fun save(context: Context, config: SavedConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putInt(KEY_MODE_INDEX, config.modeIndex)
            .putString(KEY_SNI, config.sni)
            .putString(KEY_PAYLOAD, config.payload)
            .putString(KEY_PROXY_HOST, config.proxyHost)
            .putString(KEY_PROXY_PORT, config.proxyPort)
            .putString(KEY_TLS_VERSION, config.tlsVersion)
            .putBoolean(KEY_WEBSOCKET_ENABLED, config.useWebSocket)
            .putString(KEY_WS_PATH, config.wsPath)
            .putBoolean(KEY_PROXY_RAW_MODE, config.proxyRawMode)
            .putString(KEY_XRAY_LINK, config.xrayLink)
            .putString(KEY_CUSTOM_HEADERS, config.customHeaders)
            .putBoolean(KEY_IGNORE_CERT_ERRORS, config.ignoreCertErrors)
            .putString(KEY_DNS1, config.dns1)
            .putString(KEY_DNS2, config.dns2)
            .putString(KEY_ACCOUNT_NAME, config.accountName)
            .putString(KEY_LOCK_MODE, config.lockMode.name)
            .apply()
    }

    fun load(context: Context): SavedConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null) ?: return null
        return SavedConfig(
            host = host,
            port = prefs.getInt(KEY_PORT, 22),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            modeIndex = prefs.getInt(KEY_MODE_INDEX, 0),
            sni = prefs.getString(KEY_SNI, "").orEmpty(),
            payload = prefs.getString(KEY_PAYLOAD, "").orEmpty(),
            proxyHost = prefs.getString(KEY_PROXY_HOST, "").orEmpty(),
            proxyPort = prefs.getString(KEY_PROXY_PORT, "").orEmpty(),
            tlsVersion = prefs.getString(KEY_TLS_VERSION, "").orEmpty(),
            useWebSocket = prefs.getBoolean(KEY_WEBSOCKET_ENABLED, false),
            wsPath = prefs.getString(KEY_WS_PATH, "").orEmpty(),
            proxyRawMode = prefs.getBoolean(KEY_PROXY_RAW_MODE, false),
            xrayLink = prefs.getString(KEY_XRAY_LINK, "").orEmpty(),
            customHeaders = prefs.getString(KEY_CUSTOM_HEADERS, "").orEmpty(),
            ignoreCertErrors = prefs.getBoolean(KEY_IGNORE_CERT_ERRORS, false),
            dns1 = prefs.getString(KEY_DNS1, "").orEmpty(),
            dns2 = prefs.getString(KEY_DNS2, "").orEmpty(),
            accountName = prefs.getString(KEY_ACCOUNT_NAME, "").orEmpty(),
            lockMode = ConfigLockMode.fromName(prefs.getString(KEY_LOCK_MODE, null))
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
