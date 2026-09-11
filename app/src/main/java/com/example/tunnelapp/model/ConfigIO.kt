package com.example.tunnelapp.model

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * FITUR BARU (permintaan user): Impor & Ekspor konfigurasi akun (SSH & Xray,
 * campur), supaya akun yang sudah dikonfigurasi bisa di-backup, dipindah ke
 * HP lain, atau dibagikan ke orang lain -- tanpa perlu isi ulang manual satu
 * per satu lewat [SshConfigActivity]/[XrayConfigActivity].
 *
 * DUA cara pakai, saling melengkapi:
 *
 * 1) EKSPOR SEMUA AKUN -> satu file JSON (lihat [profilesToJson]/[ConfigActivity]
 *    tombol "Ekspor Semua"), dipakai untuk backup/pindah HP. Isinya "amplop"
 *    JSON berisi daftar semua akun tersimpan, field-nya sama persis dengan
 *    [SavedConfig] (lihat [SavedConfig.toConfigJson]).
 *
 * 2) BAGIKAN SATU AKUN -> satu baris "kode" teks pendek yang gampang
 *    ditempel di chat/WhatsApp (lihat [buildShareCode]), formatnya
 *    "SVPN1:<base64 dari JSON satu akun>" -- mirip konsepnya sama seperti
 *    link share Xray (vmess://, vless://, dst) yang sudah ada di app ini,
 *    cuma dibungkus Base64 polos (bukan format biner terenkripsi
 *    proprietary macam file .hc HTTP Custom) supaya:
 *      - gampang dibaca ulang oleh app ini sendiri (skema field-nya beda
 *        total dari HTTP Custom -- app ini punya [ConnectionMode] sendiri),
 *      - tidak menyamar sebagai format app pihak ketiga lain,
 *      - tetap gampang disalin/ditempel di aplikasi chat apa pun sebagai
 *        satu baris teks (bukan lampiran file).
 *
 * [importConfigsFromText] adalah pintu masuk TUNGGAL untuk kedua arah impor
 * (paste kode "SVPN1:..." ATAU paste/pilih file JSON hasil Ekspor Semua) --
 * ConfigActivity tidak perlu tahu bedanya, cukup lempar teks mentah ke sini.
 */

/** Prefix penanda "kode bagikan" satu akun, lihat dokumentasi di atas. */
private const val SHARE_CODE_PREFIX = "SVPN1:"

private const val ENVELOPE_APP = "SuryaVPN"
private const val ENVELOPE_FORMAT = "suryavpn-config"
private const val ENVELOPE_VERSION = 1

/** Ubah satu [SavedConfig] jadi [JSONObject], field-nya 1:1 sama nama & tipe. */
fun SavedConfig.toConfigJson(): JSONObject = JSONObject().apply {
    put("host", host)
    put("port", port)
    put("username", username)
    put("password", password)
    put("modeIndex", modeIndex)
    put("sni", sni)
    put("payload", payload)
    put("proxyHost", proxyHost)
    put("proxyPort", proxyPort)
    put("tlsVersion", tlsVersion)
    put("useWebSocket", useWebSocket)
    put("wsPath", wsPath)
    put("proxyRawMode", proxyRawMode)
    put("xrayLink", xrayLink)
    put("customHeaders", customHeaders)
    put("ignoreCertErrors", ignoreCertErrors)
    put("dns1", dns1)
    put("dns2", dns2)
    put("accountName", accountName)
}

/**
 * Kebalikan dari [toConfigJson]. Null kalau field wajibnya tidak ada/kosong
 * (host untuk akun SSH, xrayLink untuk akun Xray/modeIndex 5) -- silent,
 * biar pemanggil (lihat [importConfigsFromText]) tinggal skip entry yang
 * rusak/tidak dikenal tanpa bikin keseluruhan impor gagal.
 */
private fun configFromJson(o: JSONObject): SavedConfig? {
    val modeIndex = o.optInt("modeIndex", 0)
    val host = o.optString("host", "")
    val xrayLink = o.optString("xrayLink", "")

    if (modeIndex == 5) {
        if (xrayLink.isBlank()) return null
    } else if (host.isBlank()) {
        return null
    }

    return SavedConfig(
        host = host,
        port = o.optInt("port", 22),
        username = o.optString("username", ""),
        password = o.optString("password", ""),
        modeIndex = modeIndex,
        sni = o.optString("sni", ""),
        payload = o.optString("payload", ""),
        proxyHost = o.optString("proxyHost", ""),
        proxyPort = o.optString("proxyPort", ""),
        tlsVersion = o.optString("tlsVersion", ""),
        useWebSocket = o.optBoolean("useWebSocket", false),
        wsPath = o.optString("wsPath", ""),
        proxyRawMode = o.optBoolean("proxyRawMode", false),
        xrayLink = xrayLink,
        customHeaders = o.optString("customHeaders", ""),
        ignoreCertErrors = o.optBoolean("ignoreCertErrors", false),
        dns1 = o.optString("dns1", ""),
        dns2 = o.optString("dns2", ""),
        accountName = o.optString("accountName", "")
    )
}

/**
 * Bangun isi file untuk "Ekspor Semua" (lihat [ConfigActivity]) -- satu
 * amplop JSON berisi SEMUA akun tersimpan di [ProfileStore], rapi
 * (indent 2 spasi) supaya enak dibaca manual kalau perlu.
 */
fun profilesToJson(profiles: List<SavedProfile>): String {
    val arr = JSONArray()
    profiles.forEach { arr.put(it.config.toConfigJson()) }

    val envelope = JSONObject()
    envelope.put("app", ENVELOPE_APP)
    envelope.put("format", ENVELOPE_FORMAT)
    envelope.put("version", ENVELOPE_VERSION)
    envelope.put("exportedAt", System.currentTimeMillis())
    envelope.put("profiles", arr)
    return envelope.toString(2)
}

/**
 * Kode bagikan SATU akun (lihat dokumentasi di atas), dipakai tombol
 * "Bagikan" per-baris akun di [ConfigActivity].
 */
fun buildShareCode(config: SavedConfig): String {
    val json = config.toConfigJson().toString()
    val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return SHARE_CODE_PREFIX + b64
}

/**
 * Pintu masuk impor TUNGGAL: terima teks mentah apa pun yang user
 * tempel/pilih dari file -- kode bagikan satu akun ("SVPN1:..."), JSON
 * amplop hasil "Ekspor Semua" (banyak akun), ATAU JSON satu akun polos
 * (tanpa amplop, mis. hasil edit manual) -- lalu kembalikan daftar
 * [SavedConfig] yang berhasil dibaca. Entry yang rusak/tidak dikenal di
 * dalam array cukup di-skip (lihat [configFromJson]), tidak bikin seluruh
 * impor gagal. List kosong (bukan exception) kalau teksnya sama sekali
 * tidak bisa dibaca dalam format apa pun -- pemanggil cukup cek
 * `.isEmpty()` untuk tahu impor gagal total.
 */
fun importConfigsFromText(rawText: String): List<SavedConfig> {
    val text = rawText.trim()
    if (text.isEmpty()) return emptyList()

    if (text.startsWith(SHARE_CODE_PREFIX, ignoreCase = true)) {
        val b64 = text.substring(SHARE_CODE_PREFIX.length).trim()
        return try {
            val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            listOfNotNull(configFromJson(JSONObject(json)))
        } catch (e: Exception) {
            emptyList()
        }
    }

    return try {
        when (val root = JSONTokener(text).nextValue()) {
            is JSONObject -> {
                if (root.has("profiles")) {
                    val arr = root.getJSONArray("profiles")
                    (0 until arr.length()).mapNotNull { i ->
                        (arr.opt(i) as? JSONObject)?.let(::configFromJson)
                    }
                } else {
                    listOfNotNull(configFromJson(root))
                }
            }
            is JSONArray -> (0 until root.length()).mapNotNull { i ->
                (root.opt(i) as? JSONObject)?.let(::configFromJson)
            }
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}
