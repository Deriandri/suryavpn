package com.example.tunnelapp.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ringkasan hasil satu kali [CloudConfigSync.sync]. [summaryText] dipakai
 * langsung sebagai teks yang ditampilkan ke user (Toast/label "Sinkron
 * terakhir") di [com.example.tunnelapp.ConfigActivity].
 */
data class CloudSyncResult(
    val success: Boolean,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val errorMessage: String? = null
) {
    fun summaryText(): String = if (!success) {
        "Gagal: ${errorMessage ?: "kesalahan tidak diketahui"}"
    } else {
        "Ditambah $added, diperbarui $updated, dihapus $removed"
    }
}

/**
 * FITUR BARU (permintaan user, "cloud config, jadi konfig saya update
 * online"): mesin sinkronisasi akun dari URL online.
 *
 * Sengaja TIDAK menambah format data baru sama sekali -- URL yang diisi
 * user cukup mengembalikan teks dengan format yang SAMA PERSIS dengan hasil
 * "Ekspor Semua" ([profilesToJson]) atau satu kode bagikan "SVPN1:..."
 * ([buildShareCode]), lalu diurai lewat [importConfigsFromText] yang sudah
 * ada dan dipakai jalur impor manual. Jadi pemilik server config tinggal
 * meng-host file hasil "Ekspor Semua" (mis. di GitHub raw atau hosting
 * sendiri) dan cukup TIMPA isinya kapan saja server/akun berubah -- app
 * akan menarik versi TERBARU setiap kali disinkron, tanpa user perlu impor
 * ulang manual.
 *
 * Sengaja pakai [HttpURLConnection] bawaan JDK (bukan menambah dependency
 * OkHttp baru) karena kebutuhannya cuma satu GET request sederhana.
 */
object CloudConfigSync {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    // Jaga-jaga kalau URL yang diisi user salah/nyasar ke halaman besar --
    // batas wajar untuk file JSON daftar akun (jauh lebih dari cukup untuk
    // ratusan akun sekalipun).
    private const val MAX_RESPONSE_CHARS = 5 * 1024 * 1024

    /**
     * Kunci identitas satu akun, dipakai mencocokkan hasil fetch TERBARU
     * dengan akun "milik cloud" hasil fetch SEBELUMNYA yang sudah tersimpan
     * di [ProfileStore] -- supaya sync berikutnya MEMPERBARUI baris yang
     * sama (bukan menambah duplikat terus-menerus) selama identitasnya
     * tidak berubah, dan supaya bisa mendeteksi akun yang sudah dihapus
     * dari sisi cloud (identitasnya lenyap dari hasil fetch terbaru).
     *
     * Xray dibedakan lewat xrayLink apa adanya (satu link berisi semua
     * detail koneksi). SSH dibedakan lewat kombinasi mode+host+port+
     * username -- password/payload/SNI/dll SENGAJA TIDAK ikut jadi kunci,
     * supaya perubahan password di sisi cloud tetap dianggap akun YANG
     * SAMA (di-update isinya), bukan dianggap akun baru yang berbeda.
     */
    private fun identityKey(c: SavedConfig): String = if (c.modeIndex == 5) {
        "xray|${c.xrayLink}"
    } else {
        "ssh|${c.modeIndex}|${c.host}|${c.port}|${c.username}"
    }

    /** Ambil teks mentah dari [urlString]. Selalu dijalankan di Dispatchers.IO. */
    private suspend fun fetchText(urlString: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            if (url.protocol != "http" && url.protocol != "https") {
                return@withContext Result.failure(
                    IllegalArgumentException("URL harus dimulai dengan http:// atau https://")
                )
            }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SuryaVPN-CloudSync/1.0")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    return@withContext Result.failure(Exception("Server membalas kode $code"))
                }
                val text = connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_RESPONSE_CHARS) {
                                throw Exception("Isi dari URL terlalu besar")
                            }
                            sb.append(buffer, 0, n)
                        }
                        sb.toString()
                    }
                }
                Result.success(text)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Jalankan satu putaran sinkron penuh terhadap URL yang tersimpan di
     * [CloudSyncStore]:
     *  1. Ambil teks dari [CloudSyncSettings.cloudUrl].
     *  2. Urai lewat [importConfigsFromText] (format sama seperti impor
     *     manual -- lihat dokumentasi kelas di atas).
     *  3. Cocokkan dengan akun "milik cloud" hasil sinkron SEBELUMNYA
     *     ([CloudSyncSettings.managedProfileIds]):
     *      - identitas baru yang cocok dengan akun lama -> UPDATE isinya di
     *        [ProfileStore] (id, [SavedConfig.isLocked] & [SavedConfig.lockMode]
     *        akun lama TETAP dipertahankan apa adanya).
     *      - identitas baru yang belum pernah ada -> TAMBAH sebagai akun baru.
     *      - akun lama "milik cloud" yang identitasnya sudah tidak muncul lagi
     *        di hasil fetch terbaru -> DIHAPUS otomatis, KECUALI sempat dikunci
     *        manual oleh user ([SavedConfig.isLocked] true) -- akun yang
     *        dikunci dibiarkan apa adanya supaya tidak ada kejutan kehilangan
     *        akun yang sengaja dilindungi user.
     * Akun yang ditambahkan user SENDIRI (bukan hasil sinkron sebelumnya)
     * TIDAK PERNAH disentuh sama sekali, karena hanya id yang tercatat di
     * [CloudSyncSettings.managedProfileIds] yang jadi kandidat update/hapus.
     *
     * FITUR BARU (permintaan user, "konfig cloud jadi lock all"): SETIAP
     * akun hasil sinkron ini (baru maupun update) SELALU dipaksa
     * [ConfigLockMode.LOCK_ALL] + [SavedConfig.isLocked] true, APA PUN
     * lockMode asli yang tertulis di JSON sumbernya -- lihat
     * [forceCloudLock]. Efeknya di [com.example.tunnelapp.ConfigActivity]:
     * baris akun cuma menampilkan nama & jenisnya, host/username/password/
     * payload/xrayLink dkk TIDAK ditampilkan sama sekali & layar Edit tidak
     * bisa dibuka -- persis akun hasil impor "Kunci Semua" biasa, cocok
     * untuk penyedia config yang tidak mau kredensial/trik aslinya
     * terlihat oleh pemakai app.
     *
     * KONSEKUENSI PENTING: karena [ConfigLockMode.LOCK_ALL] memang didesain
     * TIDAK BISA dibuka lewat UI sama sekali (lihat dokumentasi
     * [ConfigLockMode]), gembok [SavedConfig.isLocked] biasa (ikon gembok
     * per-baris) jadi tidak relevan lagi untuk akun-akun ini -- makanya
     * loop penghapusan di bawah TIDAK LAGI mengecualikan akun yang
     * isLocked=true seperti sebelumnya (pengecekan itu percuma sekarang,
     * isLocked SELALU true untuk semua akun cloud). Akun yang sudah
     * dihapus dari sisi cloud akan SELALU ikut terhapus di lokal, tanpa
     * pengecualian.
     */
    suspend fun sync(context: Context): CloudSyncResult {
        val settings = CloudSyncStore.load(context)
        val url = settings.cloudUrl.trim()
        if (url.isEmpty()) {
            return CloudSyncResult(success = false, errorMessage = "URL cloud config belum diatur")
        }

        val rawText = fetchText(url).getOrElse { e ->
            return CloudSyncResult(success = false, errorMessage = e.message ?: "Gagal mengambil data")
        }

        val fetchedConfigs = withContext(Dispatchers.Default) { importConfigsFromText(rawText) }
        if (fetchedConfigs.isEmpty()) {
            return CloudSyncResult(success = false, errorMessage = "Tidak ada konfigurasi valid di URL tersebut")
        }
        // Paksa LOCK_ALL di sini (bukan di [identityKey]/parsing) supaya
        // identitas pencocokan tetap dihitung dari field ASLI (host/xrayLink/
        // dst), tidak terpengaruh forcing ini.
        val newConfigs = fetchedConfigs.map(::forceCloudLock)

        // Peta identitas -> profil lama, HANYA untuk akun yang memang tercatat
        // sebagai hasil sinkron sebelumnya (lihat dokumentasi di atas) --
        // akun manual user tidak pernah masuk peta ini sama sekali.
        val oldManagedById = settings.managedProfileIds
            .mapNotNull { id -> ProfileStore.get(context, id) }
            .associateBy { it.id }
        val oldKeyToId = oldManagedById.values.associate { identityKey(it.config) to it.id }
        val consumedIds = mutableSetOf<String>()

        var added = 0
        var updated = 0
        val newManagedIds = mutableListOf<String>()

        for (config in newConfigs) {
            val existingId = oldKeyToId[identityKey(config)]
            if (existingId != null) {
                ProfileStore.upsert(context, id = existingId, config = config)
                consumedIds.add(existingId)
                newManagedIds.add(existingId)
                updated++
            } else {
                val newId = ProfileStore.upsert(context, id = null, config = config)
                newManagedIds.add(newId)
                added++
            }
        }

        // Akun lama "milik cloud" yang TIDAK ikut ter-konsumsi di atas berarti
        // sudah dihapus dari sisi cloud -- hapus juga di lokal. Lihat catatan
        // "KONSEKUENSI PENTING" di dokumentasi fungsi ini: tidak ada lagi
        // pengecualian untuk akun isLocked, karena SEMUA akun cloud memang
        // selalu isLocked=true sekarang (LOCK_ALL).
        var removed = 0
        for (profile in oldManagedById.values) {
            if (profile.id in consumedIds) continue
            ProfileStore.delete(context, profile.id)
            removed++
        }

        val result = CloudSyncResult(success = true, added = added, updated = updated, removed = removed)
        CloudSyncStore.saveSyncResult(
            context = context,
            managedProfileIds = newManagedIds,
            summary = result.summaryText(),
            timeMillis = System.currentTimeMillis()
        )
        return result
    }

    /**
     * Paksa satu [SavedConfig] hasil fetch cloud jadi "terkunci total" --
     * lihat dokumentasi "FITUR BARU (permintaan user, konfig cloud jadi
     * lock all)" di [sync]. Dipanggil SEBELUM disimpan ke [ProfileStore],
     * jadi apa pun lockMode/isLocked yang kebetulan tertulis di JSON
     * sumbernya (mis. admin sempat pakai fitur ekspor "Tanpa Kunci") selalu
     * ditimpa jadi [ConfigLockMode.LOCK_ALL] di sini.
     */
    private fun forceCloudLock(config: SavedConfig): SavedConfig =
        config.copy(isLocked = true, lockMode = ConfigLockMode.LOCK_ALL)
}
