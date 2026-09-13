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

/**
 * FITUR BARU (permintaan user, "kunci saat mau menyimpan konfig -- lock all,
 * lock payload, unlock server, unlock user & password"): dipilih user lewat
 * dialog saat menekan Simpan di [com.example.tunnelapp.SshConfigActivity]/
 * [com.example.tunnelapp.XrayConfigActivity], disimpan sebagai bagian dari
 * [SavedConfig.lockMode]. Menentukan GRUP field mana yang:
 *   1) dipudarkan/tidak bisa diedit lagi di form (lihat pemanggil
 *      `lockedGroupsFor` di kedua Activity itu), DAN
 *   2) dienkripsi (bukan cuma disembunyikan) di dalam kode bagikan satu akun
 *      -- lihat [buildShareCode] & [ConfigCrypto] -- supaya penerima kode
 *      TIDAK bisa membaca isinya walau kodenya di-decode Base64 manual.
 *
 * Nama tiap nilai sengaja dibuat SIMETRIS dengan istilah yang diminta user:
 *   - LOCK_ALL             : server + payload + user&password, SEMUA dikunci.
 *   - LOCK_PAYLOAD         : cuma payload (& field sejenisnya) yang dikunci;
 *                            server serta user/password tetap bebas diedit.
 *   - UNLOCK_SERVER        : cuma server (host/port) yang TETAP bebas diedit;
 *                            payload & user/password dikunci.
 *   - UNLOCK_USER_PASSWORD : cuma username & password yang TETAP bebas
 *                            diedit; server & payload dikunci.
 * NONE (default) berarti tidak ada yang dikunci sama sekali -- perilaku lama
 * sebelum fitur ini ada.
 */
enum class ShareLockMode {
    NONE, LOCK_ALL, LOCK_PAYLOAD, UNLOCK_SERVER, UNLOCK_USER_PASSWORD
}

/** Label yang ditampilkan di dialog pemilihan mode kunci saat Simpan. */
val ShareLockMode.label: String
    get() = when (this) {
        ShareLockMode.NONE -> "Tidak dikunci (semua field bebas diedit/dibagikan)"
        ShareLockMode.LOCK_ALL -> "Kunci Semua (server, payload, user & password disembunyikan)"
        ShareLockMode.LOCK_PAYLOAD -> "Kunci Payload saja"
        ShareLockMode.UNLOCK_SERVER -> "Buka Kunci Server saja (payload & user/password dikunci)"
        ShareLockMode.UNLOCK_USER_PASSWORD -> "Buka Kunci User & Password saja (server & payload dikunci)"
    }

/**
 * Grup field yang ikut dikunci untuk tiap [ShareLockMode]. Nilai grup:
 * "server" (host, port), "payload" (payload/sni/proxy/tls/header/xrayLink --
 * semua yang termasuk "trik" koneksi ke server), "user" (username, password).
 */
fun lockedGroupsFor(mode: ShareLockMode): Set<String> = when (mode) {
    ShareLockMode.NONE -> emptySet()
    ShareLockMode.LOCK_ALL -> setOf("server", "payload", "user")
    ShareLockMode.LOCK_PAYLOAD -> setOf("payload")
    ShareLockMode.UNLOCK_SERVER -> setOf("payload", "user")
    ShareLockMode.UNLOCK_USER_PASSWORD -> setOf("server", "payload")
}

/**
 * Field [SavedConfig]/JSON -> grup pemiliknya (lihat [lockedGroupsFor]).
 * Field yang TIDAK ada di peta ini (modeIndex, useWebSocket, dns1, dns2,
 * accountName, lockMode sendiri) selalu tetap bebas diedit & tetap teks
 * biasa di kode bagikan apa pun mode kuncinya -- field-field itu tidak
 * berisi rahasia server/akun, cuma metadata/preferensi.
 */
private val FIELD_GROUPS: Map<String, String> = mapOf(
    "host" to "server", "port" to "server",
    "payload" to "payload", "sni" to "payload", "proxyHost" to "payload",
    "proxyPort" to "payload", "proxyRawMode" to "payload", "tlsVersion" to "payload",
    "customHeaders" to "payload", "wsPath" to "payload", "ignoreCertErrors" to "payload",
    "xrayLink" to "payload",
    "username" to "user", "password" to "user"
)

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
    put("lockMode", lockMode.name)
}

/**
 * Kebalikan dari [toConfigJson]. Null kalau field wajibnya tidak ada/kosong
 * (host untuk akun SSH, xrayLink untuk akun Xray/modeIndex 5) -- silent,
 * biar pemanggil (lihat [importConfigsFromText]) tinggal skip entry yang
 * rusak/tidak dikenal tanpa bikin keseluruhan impor gagal.
 */
private fun configFromJson(oRaw: JSONObject): SavedConfig? {
    // FITUR BARU ("kunci saat menyimpan konfig" + enkripsi): kalau JSON ini
    // punya blob "locked" (lihat [buildShareCode]), dekripsi dulu & gabung
    // field hasil dekripsinya balik ke JSON sebelum diekstrak seperti biasa
    // di bawah -- pemanggil di luar fungsi ini tidak perlu tahu bedanya.
    // Gagal dekripsi (rusak/diotak-atik manual/bukan dari app ini) -> null,
    // konsisten dengan filosofi "skip entry rusak" di seluruh file ini.
    val o = mergeLockedFields(oRaw) ?: return null
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
        accountName = o.optString("accountName", ""),
        lockMode = runCatching { ShareLockMode.valueOf(o.optString("lockMode", ShareLockMode.NONE.name)) }
            .getOrDefault(ShareLockMode.NONE)
    )
}

/**
 * Kebalikan dari penguncian di [buildShareCode]: kalau [o] punya key
 * "locked", dekripsi isinya lewat [ConfigCrypto.decrypt] lalu gabungkan
 * field-field hasil dekripsinya balik ke salinan [o] (menimpa apa pun yang
 * mungkin ada di key yang sama, walau seharusnya tidak ada karena field
 * yang dikunci sudah DIHAPUS dari level atas saat dibangun -- lihat
 * [buildShareCode]). Null kalau dekripsinya gagal sama sekali (data
 * rusak/tidak dikenal), BUKAN exception -- biar pemanggil ([configFromJson])
 * cukup skip entry ini seperti entry rusak lainnya. Kalau [o] tidak punya
 * key "locked" sama sekali (kode lama/tidak terkunci), kembalikan [o] apa
 * adanya, tanpa perlu dekripsi apa pun.
 */
private fun mergeLockedFields(o: JSONObject): JSONObject? {
    if (!o.has("locked")) return o
    val decrypted = try {
        JSONObject(ConfigCrypto.decrypt(o.getString("locked")))
    } catch (e: Exception) {
        return null
    }
    val merged = JSONObject(o.toString())
    merged.remove("locked")
    decrypted.keys().forEach { key -> merged.put(key, decrypted.get(key)) }
    return merged
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
 *
 * FITUR BARU (permintaan user, "maksimalkan enkripsi" + "kunci saat
 * menyimpan konfig"): kalau akun ini disimpan dengan [SavedConfig.lockMode]
 * selain [ShareLockMode.NONE], field-field yang termasuk grup terkunci
 * (lihat [lockedGroupsFor]/[FIELD_GROUPS]) TIDAK ikut ditulis sebagai teks
 * biasa di JSON -- sebagai gantinya, semuanya dikumpulkan jadi satu blob,
 * dienkripsi AES-256-GCM lewat [ConfigCrypto.encrypt], lalu disisipkan
 * sebagai SATU key "locked" berisi ciphertext Base64. Penerima kode yang
 * tempel/impor tetap bisa CONNECT normal (app ini otomatis mendekripsinya
 * balik lewat [configFromJson]/[mergeLockedFields]) tapi field yang dikunci
 * tidak akan terbaca kalau kodenya di-decode Base64 manual di luar app ini,
 * dan begitu diimpor field itu tetap dipudarkan/tidak bisa diedit di form
 * (lihat pemakaian `lockedGroupsFor` di SshConfigActivity/XrayConfigActivity).
 */
fun buildShareCode(config: SavedConfig): String {
    val json = config.toConfigJson()
    val lockedGroups = lockedGroupsFor(config.lockMode)

    if (lockedGroups.isNotEmpty()) {
        val lockedFields = JSONObject()
        FIELD_GROUPS.forEach { (field, group) ->
            if (group in lockedGroups && json.has(field)) {
                lockedFields.put(field, json.get(field))
                json.remove(field)
            }
        }
        json.put("locked", ConfigCrypto.encrypt(lockedFields.toString()))
    }

    val b64 = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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
