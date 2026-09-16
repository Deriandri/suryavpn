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
 *    ditempel di chat/WhatsApp (lihat [buildShareCode]). FORMAT BARU
 *    (permintaan user, "double enkripsi") -- "SVPN2:<base64 dari SELURUH
 *    JSON satu akun, dienkripsi AES-256/GCM+PBKDF2 lewat
 *    [encryptWholeFileBytes]>". Ini LAPIS KE-2: field yang sudah dikunci
 *    lewat [ConfigLockMode] (LOCK_ALL/LOCK_PAYLOAD_PROXY) sudah
 *    terenkripsi per-field lebih dulu di dalam JSON-nya (lihat
 *    [applyLockMode], lapis ke-1) -- lapis ke-2 ini mengenkripsi SELURUH
 *    JSON tadi SEKALI LAGI dengan salt/IV terpisah, jadi kode bagikan
 *    TIDAK PERNAH berupa Base64 polos yang bisa langsung dibaca lewat
 *    decode base64 manual, bahkan kalau mode kunci = NONE (tanpa field
 *    yang dikunci sama sekali). Format LAMA "SVPN1:<base64 dari JSON
 *    polos>" (satu lapis Base64 saja, tanpa enkripsi tambahan) TETAP
 *    didukung untuk IMPOR demi kompatibilitas mundur (kode lama yang
 *    sudah terlanjur dibagikan), lihat [importConfigsFromText].
 *
 * [importConfigsFromText] adalah pintu masuk TUNGGAL untuk kedua arah impor
 * (paste kode "SVPN2:..." baru / "SVPN1:..." lama ATAU paste/pilih file
 * JSON hasil Ekspor Semua) -- ConfigActivity tidak perlu tahu bedanya,
 * cukup lempar teks mentah ke sini.
 */

/** Prefix kode bagikan FORMAT BARU (double-enkripsi), lihat dokumentasi di atas. */
private const val SHARE_CODE_PREFIX_V2 = "SVPN2:"

/** Prefix kode bagikan format LAMA (Base64 polos, satu lapis) -- masih didukung untuk impor saja. */
private const val SHARE_CODE_PREFIX = "SVPN1:"

private const val ENVELOPE_APP = "SuryaVPN"
private const val ENVELOPE_FORMAT = "suryavpn-config"
private const val ENVELOPE_VERSION = 1

/**
 * Ubah satu [SavedConfig] jadi [JSONObject], field-nya 1:1 sama nama & tipe.
 *
 * FITUR BARU (permintaan user, "kunci konfig saat ekspor"): [exportLockMode]
 * menentukan apakah (dan kelompok field mana yang) disamarkan di hasil JSON
 * ini -- lihat dokumentasi lengkap di [ConfigLockMode] & [applyLockMode].
 * Default-nya [SavedConfig.lockMode] milik config ini sendiri, supaya akun
 * yang SUDAH terkunci (mis. hasil impor dari orang lain) otomatis tetap
 * terkunci dengan mode yang SAMA kalau di-ekspor/dibagikan ulang -- pemanggil
 * (lihat [ConfigActivity.onShareRowClicked]/[ConfigActivity.onExportAllClicked])
 * cuma perlu menawarkan pilihan mode ke user untuk akun yang BELUM terkunci
 * ([ConfigLockMode.NONE]), karena penerima akun terkunci tidak berhak
 * mengubah/melonggarkan kuncinya sendiri.
 *
 * FITUR BARU (permintaan user, "catatan otomatis tampil di menu Catatan
 * Dashboard saat diimpor"): field "note" SELALU ditulis ke JSON ini --
 * defaultnya [SavedConfig.note] milik akun ini sendiri (supaya catatan yang
 * sudah tersimpan ikut terbawa kalau akun ini di-ekspor/dibagikan ulang
 * TANPA mengisi ulang kolom Catatan), TAPI kalau [noteOverride] diisi
 * (tidak null -- dikirim [ConfigActivity] dari form "Detail Ekspor" saat
 * user MENGISI kolom Catatan untuk ekspor kali ini), nilai itu yang dipakai
 * & ikut TERSIMPAN sebagai catatan baru akun ini kalau nanti diimpor
 * kembali (lihat [configFromJson]).
 */
fun SavedConfig.toConfigJson(exportLockMode: ConfigLockMode = lockMode, noteOverride: String? = null): JSONObject {
    val json = JSONObject().apply {
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
        put("note", noteOverride ?: note)
        // FITUR BARU: editor JSON Xray manual -- lihat kdoc
        // [SavedConfig.useRawXrayJson]/[SavedConfig.rawXrayJson].
        put("useRawXrayJson", useRawXrayJson)
        put("rawXrayJson", rawXrayJson)
    }
    return applyLockMode(json, exportLockMode)
}

/**
 * Kebalikan dari [toConfigJson]. Null kalau field wajibnya tidak ada/kosong
 * (host untuk akun SSH, xrayLink untuk akun Xray/modeIndex 5) -- silent,
 * biar pemanggil (lihat [importConfigsFromText]) tinggal skip entry yang
 * rusak/tidak dikenal tanpa bikin keseluruhan impor gagal.
 *
 * FITUR BARU (permintaan user, "kunci konfig saat ekspor"): [resolveLockedFields]
 * dipanggil LEBIH DULU -- kalau [o] hasil ekspor yang dikunci (lihat
 * [applyLockMode]), field yang disamarkan dibongkar balik ke [o] di sini
 * (jadi validasi host/xrayLink kosong di bawah tetap jalan pakai nilai
 * ASLI, bukan nilai yang sudah dihapus/kosong), lalu [lockMode] hasilnya
 * disimpan apa adanya ke [SavedConfig] & memaksa [SavedConfig.isLocked]
 * true kalau bukan [ConfigLockMode.NONE] -- lihat dokumentasi
 * [SavedConfig.lockMode].
 */
private fun configFromJson(o: JSONObject): SavedConfig? {
    val lockMode = resolveLockedFields(o)
    val modeIndex = o.optInt("modeIndex", 0)
    val host = o.optString("host", "")
    val xrayLink = o.optString("xrayLink", "")
    // FITUR BARU: editor JSON Xray manual -- akun mode Xray (modeIndex 5)
    // sekarang VALID kalau SALAH SATU dari xrayLink ATAU rawXrayJson terisi,
    // bukan cuma xrayLink lagi.
    val useRawXrayJson = o.optBoolean("useRawXrayJson", false)
    val rawXrayJson = o.optString("rawXrayJson", "")

    if (modeIndex == 5) {
        if (xrayLink.isBlank() && rawXrayJson.isBlank()) return null
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
        // FITUR BARU (permintaan user, "catatan otomatis tampil di menu
        // Catatan Dashboard saat diimpor"): baca balik field "note" (lihat
        // [toConfigJson]) -- fallback ke "exportNote" untuk kompatibilitas
        // dengan hasil ekspor sebelum field "note" ada di sini (versi awal
        // fitur ini sempat menulis catatan HANYA di level envelope/kode
        // bagikan sebagai "exportNote", belum di tiap akun).
        note = o.optString("note", o.optString("exportNote", "")),
        useRawXrayJson = useRawXrayJson,
        rawXrayJson = rawXrayJson,
        // Akun hasil impor cuma otomatis ikut isLocked=true (gembok
        // "proteksi tidak sengaja" biasa) kalau lockMode-nya LOCK_ALL --
        // lihat dokumentasi [SavedConfig.lockMode] soal beda isLocked
        // (bisa dibuka lagi manual) dengan lockMode (tidak bisa).
        //
        // PERBAIKAN (permintaan user, "payload & remote proxy malah
        // dikunci semuanya, seharusnya akun server dibiarkan tetap bisa
        // diedit"): LOCK_PAYLOAD_PROXY TIDAK ikut memicu isLocked=true di
        // sini -- akun dengan mode ini harus langsung bisa diedit begitu
        // diimpor, tanpa perlu buka gembok manual dulu.
        isLocked = lockMode == ConfigLockMode.LOCK_ALL,
        lockMode = lockMode
    )
}

/**
 * PERBAIKAN (permintaan user, "logika ekspor konfig X-ray langsung dibikin
 * all lock aja"): tentukan mode kunci EFEKTIF yang benar-benar dipakai saat
 * ekspor/bagikan satu [config], dengan urutan prioritas:
 *
 *  1. Akun yang SUDAH terkunci sebelumnya (config.lockMode != NONE, mis.
 *     hasil impor dari orang lain) selalu mempertahankan mode kuncinya
 *     SENDIRI apa adanya -- penerima tidak berhak melonggarkan kunci akun
 *     yang bukan miliknya.
 *  2. Akun Xray (modeIndex == 5) yang BELUM terkunci SELALU dipaksa
 *     [ConfigLockMode.LOCK_ALL], apa pun [exportLockMode] yang
 *     diminta/dipilih pemanggil (termasuk kalau user sempat memilih "Tanpa
 *     Kunci" di [ConfigActivity.showLockModePicker]) -- link/host/username/
 *     password Xray tidak boleh pernah ke-ekspor polos.
 *  3. Selain itu (akun SSH yang belum terkunci) -- pakai [exportLockMode]
 *     apa adanya seperti sebelumnya, user bebas pilih lewat dialog.
 */
private fun effectiveExportLockMode(config: SavedConfig, exportLockMode: ConfigLockMode): ConfigLockMode = when {
    config.lockMode != ConfigLockMode.NONE -> config.lockMode
    config.modeIndex == 5 -> ConfigLockMode.LOCK_ALL
    else -> exportLockMode
}

/**
 * Bangun isi file untuk "Ekspor Semua" (lihat [ConfigActivity]) -- satu
 * amplop JSON berisi SEMUA akun tersimpan di [ProfileStore], rapi
 * (indent 2 spasi) supaya enak dibaca manual kalau perlu.
 */
/**
 * FITUR BARU (permintaan user, "form nama & catatan sebelum pemilihan jenis
 * konfig"): [exportName]/[exportNote] adalah metadata OPSIONAL yang diisi
 * user lewat [ConfigActivity.showExportDetailsDialog] SEBELUM memilih jenis
 * ekspor (file/.spn atau clipboard) -- ikut ditulis apa adanya ke envelope
 * (cuma kalau tidak kosong) sebagai "exportName"/"exportNote", TIDAK pernah
 * diformat ulang atau di-escape sama sekali di sini. [exportNote] SENGAJA
 * boleh berisi markup HTML mentah (atau apa pun, termasuk PHP) -- field ini
 * cuma teks biasa buat [importConfigsFromText]/[configFromJson] (yang pakai
 * `optString`, otomatis mengabaikan field tak dikenal), jadi tetap aman
 * dibaca balik oleh versi app lama yang belum tahu field ini; perendahan
 * jadi HTML (kalau memang dipakai begitu) adalah urusan UI penampil, bukan
 * bagian dari fungsi ini.
 */
fun profilesToJson(
    profiles: List<SavedProfile>,
    exportLockMode: ConfigLockMode = ConfigLockMode.NONE,
    exportName: String = "",
    exportNote: String = ""
): String {
    val arr = JSONArray()
    // Lihat dokumentasi [effectiveExportLockMode]: akun Xray belum-terkunci
    // dipaksa LOCK_ALL di sini, terlepas dari [exportLockMode] yang dipilih
    // user untuk keseluruhan ekspor.
    //
    // [exportNote] (kalau diisi user lewat form "Detail Ekspor") dipakai
    // sebagai [noteOverride] untuk SEMUA akun di [profiles] -- menimpa
    // catatan lama masing-masing akun (kalau ada) dengan catatan baru yang
    // sama untuk seluruh ekspor ini. Kosong ("") -> null -> tiap akun tetap
    // pakai catatannya sendiri-sendiri ([SavedConfig.note]) apa adanya,
    // tidak ada yang ditimpa.
    val noteOverride = exportNote.ifBlank { null }
    profiles.forEach { profile ->
        val mode = effectiveExportLockMode(profile.config, exportLockMode)
        arr.put(profile.config.toConfigJson(mode, noteOverride))
    }

    val envelope = JSONObject()
    envelope.put("app", ENVELOPE_APP)
    envelope.put("format", ENVELOPE_FORMAT)
    envelope.put("version", ENVELOPE_VERSION)
    envelope.put("exportedAt", System.currentTimeMillis())
    if (exportName.isNotBlank()) envelope.put("exportName", exportName)
    if (exportNote.isNotBlank()) envelope.put("exportNote", exportNote)
    envelope.put("profiles", arr)
    return envelope.toString(2)
}

/**
 * Kode bagikan SATU akun (lihat dokumentasi di atas), dipakai tombol
 * "Bagikan" per-baris akun di [ConfigActivity]. DOUBLE ENKRIPSI: lapis-1
 * ([applyLockMode], lewat [config.toConfigJson]) mengunci field-field
 * tertentu SESUAI [mode] yang dipilih user; lapis-2 ([encryptWholeFileBytes])
 * mengenkripsi SELURUH hasil JSON tadi sekali lagi (AES-256/GCM, salt+IV
 * acak sendiri, beda dari salt+IV lapis-1) -- jadi kode bagikan yang keluar
 * SELALU dalam bentuk terenkripsi, tidak pernah Base64 polos yang bisa
 * langsung dibaca lewat decode base64 manual, terlepas dari [mode] yang
 * dipilih (termasuk saat [ConfigLockMode.NONE]).
 */
fun buildShareCode(
    config: SavedConfig,
    exportLockMode: ConfigLockMode = config.lockMode,
    exportName: String = "",
    exportNote: String = ""
): String {
    // Lihat dokumentasi [effectiveExportLockMode]: akun Xray belum-terkunci
    // dipaksa LOCK_ALL di sini juga, terlepas dari mode yang dipilih user
    // lewat [ConfigActivity.showLockModePicker].
    val mode = effectiveExportLockMode(config, exportLockMode)
    // Sama seperti [profilesToJson]: [exportNote] (kalau diisi lewat form
    // "Detail Ekspor") menimpa catatan lama akun ini untuk hasil ekspor
    // kali ini -- kosong -> null -> tetap pakai [config.note] apa adanya.
    val jsonObject = config.toConfigJson(mode, exportNote.ifBlank { null })
    if (exportName.isNotBlank()) jsonObject.put("exportName", exportName)
    val json = jsonObject.toString()
    val doubleEncrypted = encryptWholeFileBytes(json)
    val b64 = Base64.encodeToString(doubleEncrypted, Base64.NO_WRAP)
    return SHARE_CODE_PREFIX_V2 + b64
}

/**
 * Pintu masuk impor TUNGGAL: terima teks mentah apa pun yang user
 * tempel/pilih dari file -- kode bagikan satu akun format baru
 * double-enkripsi ("SVPN2:...") ATAU format lama Base64 polos
 * ("SVPN1:...", kompatibilitas mundur), teks Base64 hasil "Salin ke
 * Clipboard" (Base64 dari file .spn terenkripsi utuh, lihat
 * [encryptWholeFileBytes]/[decryptWholeFileBytes]), JSON amplop hasil
 * "Ekspor Semua" (banyak akun), ATAU JSON satu akun polos (tanpa amplop,
 * mis. hasil edit manual) -- lalu kembalikan daftar [SavedConfig] yang
 * berhasil dibaca. Entry yang rusak/tidak dikenal di dalam array cukup
 * di-skip (lihat [configFromJson]), tidak bikin seluruh impor gagal. List
 * kosong (bukan exception) kalau teksnya sama sekali tidak bisa dibaca
 * dalam format apa pun -- pemanggil cukup cek `.isEmpty()` untuk tahu
 * impor gagal total.
 */
fun importConfigsFromText(rawText: String): List<SavedConfig> {
    val text = rawText.trim()
    if (text.isEmpty()) return emptyList()

    if (text.startsWith(SHARE_CODE_PREFIX_V2, ignoreCase = true)) {
        val b64 = text.substring(SHARE_CODE_PREFIX_V2.length).trim()
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            val json = decryptWholeFileBytes(raw) ?: return emptyList()
            listOfNotNull(configFromJson(JSONObject(json)))
        } catch (e: Exception) {
            emptyList()
        }
    }

    if (text.startsWith(SHARE_CODE_PREFIX, ignoreCase = true)) {
        val b64 = text.substring(SHARE_CODE_PREFIX.length).trim()
        return try {
            val json = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            listOfNotNull(configFromJson(JSONObject(json)))
        } catch (e: Exception) {
            emptyList()
        }
    }

    // PERBAIKAN (permintaan user, "hasil salin clipboard ngk bisa
    // diimpor lagi"): teks hasil tombol "Salin ke Clipboard" (lihat
    // [ConfigActivity.onExportToClipboardClicked]) BUKAN JSON polos
    // ataupun kode "SVPN1:..." -- itu Base64 dari BYTE BINER terenkripsi
    // UTUH, format yang SAMA persis dengan isi file .spn (lihat
    // [encryptWholeFileBytes]), cuma dibungkus Base64 supaya bisa
    // disalin sebagai teks biasa alih-alih file. Sebelum fitur ini,
    // [importConfigsFromText] cuma tahu cara baca "SVPN1:..." atau JSON
    // mentah -- teks Base64 terenkripsi ini gagal di-parse JSON sama
    // sekali & diam-diam dianggap "tidak ada konfigurasi valid".
    //
    // Coba jalur ini DULU: decode Base64-nya, lalu coba dekripsi lewat
    // [decryptWholeFileBytes] (SAMA seperti pembacaan file .spn di
    // [ConfigActivity]'s importFileLauncher). Kalau berhasil, hasilnya
    // pasti JSON amplop "profiles" yang sama seperti hasil Ekspor Semua
    // biasa -- tinggal diteruskan ke parsing JSON yang sudah ada di
    // bawah. Kalau GAGAL (bukan hasil ekspor clipboard sama sekali,
    // mis. teks JSON envelope polos yang ditempel manual), fallback diam-
    // diam ke [text] apa adanya -- tidak mengubah perilaku lama sama
    // sekali untuk kasus itu.
    val decodedFromClipboard = try {
        decryptWholeFileBytes(Base64.decode(text, Base64.DEFAULT))
    } catch (e: Exception) {
        null
    }
    val effectiveText = decodedFromClipboard ?: text

    return try {
        when (val root = JSONTokener(effectiveText).nextValue()) {
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
