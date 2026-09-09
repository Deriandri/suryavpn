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
    val xrayLink: String = ""
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
            xrayLink = prefs.getString(KEY_XRAY_LINK, "").orEmpty()
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
