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
 *  - [NONE]: tanpa kunci sama sekali, persis perilaku ekspor sebelum fitur
 *    ini ada.
 *
 * Begitu diimpor lagi (lihat [resolveLockedFields] & [configFromJson]),
 * akun hasil impor yang lockMode-nya [LOCK_ALL] otomatis ditandai "terkunci
 * total" di UI -- lihat [SavedConfig.lockMode] &
 * [com.example.tunnelapp.ConfigActivity.bindAccountRow]: baris akun cuma
 * menampilkan NAMA-nya saja (bukan host:port/detail), dan layar Edit tidak
 * bisa dibuka sama sekali (beda dari gembok [SavedConfig.isLocked] biasa
 * yang masih bisa dibuka user sendiri kapan saja) -- karena tujuannya
 * memang supaya orang yang menerima akun (mis. dari penjual konfig) tidak
 * bisa mengintip/menyalin trik & kredensial aslinya, cuma bisa
 * connect/hapus.
 *
 * [LOCK_PAYLOAD_PROXY] TIDAK memicu "terkunci total" itu -- cuma payload &
 * remote proxy yang disamarkan di dalam FILE hasil ekspornya (biar tidak
 * bisa diintip lewat teks editor biasa), tapi begitu diimpor lagi
 * [resolveLockedFields] sudah mengembalikan seluruh field aslinya (termasuk
 * payload & proxy) ke config -- jadi akun server (host/port/username/
 * password/SNI) maupun payload/proxy-nya tetap bisa dilihat & DIEDIT lagi
 * seperti akun biasa, sama sekali tidak diblokir.
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
    LOCK_PAYLOAD_PROXY("Kunci Payload & Remote Proxy");

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
        "enhancedSsl", "xrayLink", "customHeaders", "dns1", "dns2"
    )

    ConfigLockMode.LOCK_PAYLOAD_PROXY -> setOf(
        "payload", "proxyHost", "proxyPort", "proxyRawMode", "enhancedSsl"
    )
}

/** Key JSON tempat blob terenkripsi kelompok field yang dikunci disimpan. */
private const val KEY_LOCKED_BLOB = "_locked"
private const val KEY_LOCK_MODE = "lockMode"

/**
 * Cipher AES-256/GCM dengan kunci statis tertanam di APK -- SENGAJA tidak
 * diikat ke device (HWID/Android ID) supaya file/blob hasil ekspor tetap
 * bisa dibuka lagi di HP lain mana pun yang menjalankan app ini (permintaan
 * user: "config bisa dipakai di perangkat lain juga"). Lihat catatan
 * batasan keamanan di dokumentasi [ConfigLockMode] di atas -- ini tetap
 * SEKEDAR PENGHALANG (obfuscation) pakai kunci statis, bukan proteksi yang
 * tahan terhadap orang yang membongkar APK ini sendiri.
 *
 * Format blob v2 (Base64 dari seluruh byte berikut):
 *   [4 byte magic "SPN1"][1 byte versi = 2][12 byte GCM IV][ciphertext+tag]
 *
 * GCM dipilih (bukan CBC+HMAC terpisah) supaya sekali jalan sudah dapat
 * autentikasi -- blob yang diotak-atik manual (file diedit teks editor,
 * base64 dipotong, dst) GAGAL didekripsi (exception) alih-alih diam-diam
 * menghasilkan data korup yang tetap dipakai app.
 */
private object ConfigCipher {
    // FITUR BARU (permintaan user, "pindahkan generate key ke native code"):
    // passphrase statis TIDAK lagi disimpan (walau ter-XOR) di sisi
    // Kotlin -- direkonstruksi di kode native C (lihat
    // cpp/config_lock_jni.c, fungsi Java_..._ConfigCipher_nativePassphrase)
    // yang dikompilasi jadi libtunneljni.so, library native yang sama yang
    // sudah dipakai [com.example.tunnelapp.tunnel.HevSocks5Bridge] untuk
    // tunnel SSH/Xray. Reuse library yang sama supaya tidak nambah satu
    // .so terpisah cuma untuk satu fungsi kecil ini -- System.loadLibrary
    // aman dipanggil berkali-kali (no-op kalau sudah termuat).
    //
    // Efeknya: orang yang cuma decompile file .dex (jadx/apktool -- cara
    // paling umum) TIDAK akan menemukan potongan passphrase ini sama
    // sekali, karena secara fisik memang tidak ada di bytecode
    // Java/Kotlin. Batasannya SAMA seperti sebelumnya (lihat dokumentasi
    // lengkap di cpp/config_lock_jni.c): ini menaikkan biaya analisis
    // STATIS, bukan menutup celah RUNTIME (debugger/Frida hook tetap bisa
    // dump nilai balik native call ini).
    init {
        System.loadLibrary("tunneljni")
    }

    private external fun nativePassphrase(): String

    private fun passphrase(): String = nativePassphrase()

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte())
    private const val VERSION_V2: Byte = 2 // GCM, kunci = SHA-256(passphrase) langsung, tanpa salt
    private const val VERSION_V3: Byte = 3 // GCM, kunci = PBKDF2(passphrase, salt acak, 150rb iterasi)
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_SALT_LEN = 16
    private const val PBKDF2_ITERATIONS = 150_000
    private const val PBKDF2_KEY_BITS = 256

    /** v2 lama: kunci statis langsung dari 1x SHA-256, tanpa salt/stretching. */
    private fun keyV2(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(passphrase().toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    /**
     * v3 baru (permintaan user, "biar lebih kuat"): kunci di-"stretch" lewat
     * PBKDF2-HMAC-SHA256 150.000 iterasi + [salt] ACAK PER-ENKRIPSI (beda
     * setiap kali export, walau passphrase statisnya sama). Ini yang benar2
     * menaikkan biaya brute-force/precompute-table dibanding cuma menjalankan
     * AES dua kali dengan kunci yang sama persis (yang TIDAK menambah
     * kekuatan apa pun -- kalau kuncinya bocor dari APK, berapa pun jumlah
     * lapisnya tetap sama-sama langsung terbuka).
     */
    private fun keyV3(salt: ByteArray): SecretKeySpec {
        val spec = javax.crypto.spec.PBEKeySpec(
            passphrase().toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS
        )
        val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Enkripsi mentah -> byte array [magic][versi=3][salt 16 byte][iv 12
     * byte][ciphertext+tag], TANPA Base64 -- dipakai baik untuk blob
     * per-field (lewat [encrypt], dibungkus Base64 supaya muat sebagai satu
     * value string di JSON) maupun untuk enkripsi SATU FILE UTUH (lewat
     * [encryptWholeFileBytes] di bawah, ditulis apa adanya sebagai file
     * biner .spn). Selalu pakai format v3 (PBKDF2) untuk enkripsi BARU.
     */
    fun encryptRaw(plainText: String): ByteArray {
        val salt = ByteArray(PBKDF2_SALT_LEN).also { Random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LEN).also { Random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyV3(salt), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return MAGIC + byteArrayOf(VERSION_V3) + salt + iv + encrypted
    }

    /** Format baru (v3, PBKDF2+GCM+magic header), dibungkus Base64 -- dipakai untuk blob per-field di dalam JSON. */
    fun encrypt(plainText: String): String = Base64.encodeToString(encryptRaw(plainText), Base64.NO_WRAP)

    /**
     * Dekripsi byte mentah (belum di-Base64-decode) -- baca byte versi
     * setelah magic buat pilih jalur yang benar: v3 (PBKDF2, salt disimpan
     * di file), v2 (kunci langsung, tanpa salt -- format transisi
     * sebelumnya), atau v1 legacy (CBC polos, tanpa magic header sama
     * sekali, dari sebelum semua fitur enkripsi ini ada). Kalau [raw] sama
     * sekali bukan hasil enkripsi format mana pun (mis. file JSON polos
     * lama yang belum pernah dikunci), SEMUA percobaan gagal dan fungsi ini
     * mengembalikan null -- pemanggil ([decryptWholeFileBytes]) tinggal
     * fallback anggap itu teks polos.
     */
    fun decryptRaw(raw: ByteArray): String? =
        if (raw.size > MAGIC.size + 1 && raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            when (raw[MAGIC.size]) {
                VERSION_V3 -> decryptV3(raw)
                VERSION_V2 -> decryptV2(raw)
                else -> null
            }
        } else {
            decryptV1Legacy(raw)
        }

    /** Dekripsi blob Base64 (dipakai untuk blob per-field lama & baru di dalam JSON). */
    fun decrypt(blob: String): String? = try {
        decryptRaw(Base64.decode(blob, Base64.NO_WRAP))
    } catch (e: Exception) {
        null
    }

    private fun decryptV3(raw: ByteArray): String? = try {
        val saltStart = MAGIC.size + 1 // lewati magic + 1 byte versi
        val salt = raw.copyOfRange(saltStart, saltStart + PBKDF2_SALT_LEN)
        val ivStart = saltStart + PBKDF2_SALT_LEN
        val iv = raw.copyOfRange(ivStart, ivStart + GCM_IV_LEN)
        val encrypted = raw.copyOfRange(ivStart + GCM_IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyV3(salt), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Kompatibilitas mundur untuk blob v2 (GCM tanpa salt/PBKDF2, format transisi sebelumnya). */
    private fun decryptV2(raw: ByteArray): String? = try {
        val ivStart = MAGIC.size + 1 // lewati magic + 1 byte versi
        val iv = raw.copyOfRange(ivStart, ivStart + GCM_IV_LEN)
        val encrypted = raw.copyOfRange(ivStart + GCM_IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyV2(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /** Kompatibilitas mundur untuk blob v1 (CBC, sebelum magic header ada). */
    private fun decryptV1Legacy(raw: ByteArray): String? = try {
        val iv = raw.copyOfRange(0, 16)
        val encrypted = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyV2(), IvParameterSpec(iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/**
 * FITUR BARU (permintaan user, "sekalian ganti biner"): enkripsi SELURUH
 * isi file ekspor (bukan cuma field yang dikunci per-akun lewat
 * [applyLockMode]) jadi satu file BINER -- dipakai [profilesToJson]
 * ([ConfigActivity.onExportAllClicked]) sebelum ditulis ke file .spn lewat
 * SAF, supaya orang yang buka file .spn pakai text editor biasa TIDAK
 * melihat struktur JSON-nya sama sekali (nama field, daftar akun, dst),
 * bukan cuma nilai field yang dikunci per-akun. Reuse kunci statis & format
 * v2 yang sama dengan [ConfigCipher] di atas.
 */
fun encryptWholeFileBytes(plainText: String): ByteArray = ConfigCipher.encryptRaw(plainText)

/**
 * Kebalikan dari [encryptWholeFileBytes]. Null kalau [raw] bukan hasil
 * enkripsi format ini sama sekali (mis. file .spn yang diotak-atik manual,
 * ATAU file JSON polos lama dari SEBELUM fitur enkripsi-seluruh-file ini
 * ada) -- pemanggil ([ConfigActivity]'s importFileLauncher) tinggal
 * fallback baca [raw] sebagai teks UTF-8 polos kalau ini null, supaya file
 * ekspor LAMA yang sudah beredar tetap bisa diimpor.
 */
fun decryptWholeFileBytes(raw: ByteArray): String? = ConfigCipher.decryptRaw(raw)

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
