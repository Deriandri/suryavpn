package com.example.tunnelapp.model

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FITUR BARU (permintaan user, "maksimalkan fungsi enkripsi" + "kunci saat
 * menyimpan konfig"): mengenkripsi ISI field yang dikunci (lihat
 * [ShareLockMode] di ConfigIO.kt) di dalam kode bagikan ("SVPN1:...", lihat
 * [buildShareCode]) memakai AES-256-GCM -- supaya field yang dikunci (mis.
 * host & payload server) TIDAK bisa dibaca cuma dengan decode Base64 manual
 * oleh siapa pun yang menerima kodenya. Beda dari [SavedConfig.isLocked]
 * (kunci UI lama) yang cuma menahan tombol Edit/Hapus -- datanya sendiri
 * tetap teks biasa kalau kode/file-nya dibongkar manual. Di sini datanya
 * betul-betul TIDAK terbaca tanpa didekripsi dulu.
 *
 * PENTING -- beda mendasar dari [SecurePrefsFactory] (kuncinya hidup di
 * ANDROID KEYSTORE perangkat, SENGAJA tidak bisa dipindah/dibuka di
 * perangkat lain): kode bagikan di sini justru HARUS bisa didekripsi ulang
 * di perangkat LAIN (siapa pun yang menerima & menempelkannya lewat tombol
 * "Impor" di app SuryaVPN yang sama) -- jadi kuncinya TIDAK BISA terikat ke
 * Keystore satu perangkat, harus berupa kunci simetris yang SAMA, tertanam
 * di dalam APK app ini sendiri (bukan per-instalasi).
 *
 * Konsekuensinya jujur perlu dicatat: enkripsi ini melindungi dari orang
 * iseng yang cuma decode Base64/baca JSON mentah-mentah (mis. kalau kode
 * bagikan ke-screenshot atau nyasar ke orang yang salah sebelum sempat
 * dipakai) -- BUKAN dari orang yang sanggup membongkar/reverse-engineer APK
 * app ini sendiri untuk menemukan kunci tertanamnya. Untuk proteksi
 * setingkat itu, satu-satunya cara yang benar-benar aman adalah server
 * verifikasi online (di luar cakupan app offline ini).
 */
private object ConfigCryptoKey {
    // Kunci AES-256 tertanam, disimpan terpisah jadi dua "separuh" (bukan
    // satu array byte utuh yang gampang ke-grep sebagai satu string mentah
    // kalau APK dibongkar) lalu digabung & di-hash ulang di [combined] --
    // proteksinya ringan (obfuscation, bukan "enkripsi" sungguhan buat
    // kunci itu sendiri), sesuai batasan yang dijelaskan di atas.
    private val partA = byteArrayOf(
        0x4A, 0x2E, 0x71.toByte(), 0x0C, 0x9B.toByte(), 0x55, 0x18, 0xE3.toByte(),
        0x77, 0x04, 0xAF.toByte(), 0x3D, 0x62, 0xC9.toByte(), 0x1B, 0x88.toByte()
    )
    private val partB = byteArrayOf(
        0xD1.toByte(), 0x40, 0x5C, 0x93.toByte(), 0x27, 0xFE.toByte(), 0x11, 0x6A,
        0x84.toByte(), 0x39, 0xB7.toByte(), 0x02, 0xEE.toByte(), 0x5D, 0xA0.toByte(), 0x16
    )

    /** Kunci AES-256 (32 byte) hasil gabung [partA]+[partB], di-hash ulang
     *  lewat SHA-256 supaya panjang & distribusi bit-nya rapi 256-bit walau
     *  isi asalnya cuma nilai tertanam statis. */
    val combined: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(partA + partB)
    }
}

object ConfigCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private fun secretKey() = SecretKeySpec(ConfigCryptoKey.combined, "AES")

    /**
     * Enkripsi [plainText] -> Base64(iv + ciphertext+tag), NO_WRAP (satu
     * baris, aman disisipkan langsung ke dalam value field JSON string).
     * IV baru di-random tiap panggilan (GCM WAJIB IV unik per enkripsi
     * supaya tetap aman), makanya hasil enkripsi teks yang sama akan beda
     * tiap kali dipanggil -- itu normal, bukan bug.
     */
    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    /**
     * Kebalikan [encrypt]. Melempar exception kalau [encoded] rusak/bukan
     * hasil [encrypt] fungsi ini (mis. kode bagikan diotak-atik manual, atau
     * tag GCM tidak cocok) -- sengaja TIDAK ditangkap di sini, biar
     * pemanggil ([ConfigIO]) yang memutuskan mau di-skip sebagai entry
     * tidak valid.
     */
    fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        require(raw.size > IV_BYTES) { "Data terenkripsi tidak valid" }
        val iv = raw.copyOfRange(0, IV_BYTES)
        val ciphertext = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, Charsets.UTF_8)
    }
}
