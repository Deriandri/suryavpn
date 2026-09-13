package com.example.tunnelapp.model

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * FITUR BARU (permintaan user, "kunci konfig kayak aplikasi HTTP Custom saat
 * ekspor"): empat mode kunci yang bisa dipilih SAAT ekspor/bagikan satu akun
 * ([buildShareCode]) atau ekspor semua akun ([profilesToJson]) -- menentukan
 * KELOMPOK field mana yang disamarkan (dienkripsi jadi satu blob) di dalam
 * teks/file hasil ekspor, supaya orang yang cuma buka file JSON-nya atau
 * decode base64 kode bagikannya lewat teks editor biasa TIDAK bisa langsung
 * baca host/username/password/payload/proxy aslinya:
 *
 *  - [LOCK_ALL]: semua field teknis dikunci (host, port, username, password,
 *    sni, payload, proxy, TLS, WebSocket, header custom, DNS, link Xray).
 *    Cuma [SavedConfig.accountName] & [SavedConfig.modeIndex] yang tetap
 *    polos (supaya baris akunnya masih bisa ditampilkan namanya & jenisnya
 *    SSH/Xray di ConfigActivity tanpa perlu buka kunci dulu).
 *  - [LOCK_PAYLOAD_PROXY]: cuma payload & remote proxy (proxyHost/proxyPort/
 *    proxyRawMode) yang dikunci -- akun/host/username/password/SNI tetap
 *    polos & bisa dilihat/diedit lagi setelah diimpor.
 *  - [UNLOCK_ACCOUNT]: kebalikan dari atas -- host/port/username/password
 *    (akun server) SENGAJA dibiarkan polos (bisa dilihat/dipakai ulang untuk
 *    akun lain di server yang sama), tapi SEMUA trik teknis lainnya (payload,
 *    proxy, SNI, TLS, WebSocket, header, DNS, link Xray) dikunci.
 *  - [NONE]: tanpa kunci sama sekali, persis perilaku ekspor sebelum fitur
 *    ini ada.
 *
 * Begitu diimpor lagi (lihat [resolveLockedFields] & [configFromJson]),
 * akun hasil impor yang lockMode-nya bukan [NONE] otomatis ditandai
 * "terkunci total" di UI -- lihat [SavedConfig.lockMode] &
 * [com.example.tunnelapp.ConfigActivity.bindAccountRow]: baris akun cuma
 * menampilkan NAMA-nya saja (bukan host:port/detail), dan layar Edit tidak
 * bisa dibuka sama sekali (beda dari gembok [SavedConfig.isLocked] biasa
 * yang masih bisa dibuka user sendiri kapan saja) -- karena tujuannya
 * memang supaya orang yang menerima akun (mis. dari penjual konfig) tidak
 * bisa mengintip/menyalin trik & kredensial aslinya, cuma bisa
 * connect/hapus.
 *
 * PENTING, harus jujur soal batasannya: ini SEKEDAR PENGHALANG (obfuscation)
 * pakai kunci AES yang tertanam statis di dalam APK -- PERSIS seperti
 * mekanisme "Lock" di aplikasi sejenis (HTTP Custom/HTTP Injector/dst),
 * BUKAN enkripsi tingkat tinggi yang tahan terhadap orang yang mau membongkar
 * APK ini sendiri lewat reverse-engineering. Tujuannya cuma mencegah "intip
 * biasa" (baca file JSON/kode bagikan di teks editor, decode base64 manual),
 * sama seperti batasan yang sama-sama dimiliki app sejenis.
 */
enum class ConfigLockMode(val label: String) {
    NONE("Tanpa Kunci (No Lock)"),
    LOCK_ALL("Kunci Semua (Lock All)"),
    LOCK_PAYLOAD_PROXY("Kunci Payload & Remote Proxy"),
    UNLOCK_ACCOUNT("Buka Kunci Akun Server");

    companion object {
        fun fromName(name: String?): ConfigLockMode =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Nama key JSON (lihat [SavedConfig.toConfigJson]) yang disamarkan per mode. */
private fun lockedFieldsFor(mode: ConfigLockMode): Set<String> = when (mode) {
    ConfigLockMode.NONE -> emptySet()

    ConfigLockMode.LOCK_ALL -> setOf(
        "host", "port", "username", "password", "sni", "payload",
        "proxyHost", "proxyPort", "tlsVersion", "wsPath", "proxyRawMode",
        "xrayLink", "customHeaders", "dns1", "dns2"
    )

    ConfigLockMode.LOCK_PAYLOAD_PROXY -> setOf(
        "payload", "proxyHost", "proxyPort", "proxyRawMode"
    )

    ConfigLockMode.UNLOCK_ACCOUNT -> setOf(
        "sni", "payload", "proxyHost", "proxyPort", "tlsVersion", "wsPath",
        "proxyRawMode", "xrayLink", "customHeaders", "dns1", "dns2"
    )
}

/** Key JSON tempat blob terenkripsi kelompok field yang dikunci disimpan. */
private const val KEY_LOCKED_BLOB = "_locked"
private const val KEY_LOCK_MODE = "lockMode"

/**
 * Cipher AES-256/CBC dengan kunci statis tertanam di APK -- lihat catatan
 * batasan keamanan di dokumentasi [ConfigLockMode] di atas.
 */
private object ConfigCipher {
    private const val PASSPHRASE = "SuryaVPN-ConfigLock-v1"

    private fun key(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(PASSPHRASE.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(16).also { Random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 16)
        val encrypted = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key(), IvParameterSpec(iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/**
 * Terapkan [mode] ke [json] (hasil [SavedConfig.toConfigJson] apa adanya,
 * semua field masih polos) -- field yang termasuk kelompok [lockedFieldsFor]
 * dipindah jadi SATU blob terenkripsi di key [KEY_LOCKED_BLOB], lalu bentuk
 * polosnya DIHAPUS dari [json]. Dipanggil dari dalam [SavedConfig.toConfigJson]
 * sendiri -- lihat model/ConfigIO.kt.
 */
internal fun applyLockMode(json: JSONObject, mode: ConfigLockMode): JSONObject {
    json.put(KEY_LOCK_MODE, mode.name)
    val fields = lockedFieldsFor(mode)
    if (fields.isEmpty()) return json

    val secret = JSONObject()
    fields.forEach { field ->
        if (json.has(field)) {
            secret.put(field, json.get(field))
            json.remove(field)
        }
    }
    json.put(KEY_LOCKED_BLOB, ConfigCipher.encrypt(secret.toString()))
    return json
}

/**
 * Kebalikan dari [applyLockMode]: kalau [json] punya blob [KEY_LOCKED_BLOB]
 * (hasil ekspor yang dikunci), buka & satukan kembali field-field asalnya ke
 * [json] SEBELUM diteruskan ke pembacaan field biasa di [configFromJson] --
 * supaya app tetap bisa connect pakai nilai aslinya walau tampilannya
 * tetap "terkunci". Mengembalikan [ConfigLockMode] yang tersimpan di [json]
 * (bisa [ConfigLockMode.NONE] kalau memang tidak dikunci / file lama sebelum
 * fitur ini ada).
 *
 * Gagal decode (blob rusak/diotak-atik manual) -> field terkait dibiarkan
 * kosong/default (silent, sama seperti filosofi error handling
 * [importConfigsFromText] yang lain), TAPI mode kuncinya tetap dikembalikan
 * apa adanya supaya akun hasil impor tetap tampil "terkunci" di UI walau
 * isinya gagal dibongkar sepenuhnya -- lebih aman daripada diam-diam jatuh
 * balik ke tidak terkunci.
 */
internal fun resolveLockedFields(json: JSONObject): ConfigLockMode {
    val mode = ConfigLockMode.fromName(json.optString(KEY_LOCK_MODE, ConfigLockMode.NONE.name))
    val blob = json.optString(KEY_LOCKED_BLOB, "")
    if (mode != ConfigLockMode.NONE && blob.isNotEmpty()) {
        val decrypted = ConfigCipher.decrypt(blob)
        if (decrypted != null) {
            try {
                val secret = JSONObject(decrypted)
                secret.keys().forEach { key -> json.put(key, secret.get(key)) }
            } catch (e: Exception) {
                // Biarkan field terkait kosong/default, lihat dokumentasi di atas.
            }
        }
    }
    return mode
}
