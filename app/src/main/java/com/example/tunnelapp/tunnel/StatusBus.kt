package com.example.tunnelapp.tunnel

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Jembatan status antara MyVpnService (background) dan Activity UI (Dashboard/Log).
 * Berlaku karena keduanya jalan di process yang sama (tidak ada android:process
 * terpisah di manifest), jadi cukup in-memory singleton, tidak perlu broadcast.
 *
 * `steps` berisi rincian tahap koneksi satu per satu (dipakai untuk log/menu
 * yang menunjukkan tepat di tahap mana proses koneksi berhenti/gagal),
 * sedangkan `state` tetap dipakai sebagai ringkasan satu baris (judul
 * notifikasi & headline status di UI).
 */
object StatusBus {
    val state = MutableStateFlow("Belum tersambung")
    val steps = MutableStateFlow<List<ConnectionStep>>(emptyList())

    /**
     * FITUR BARU (permintaan user, "kunci edit config saat tunnel terhubung"):
     * pengecekan murni dari teks [state] apakah tunnel LAGI BENAR-BENAR aktif
     * (bukan idle/gagal/lagi proses connect-disconnect). Logikanya SENGAJA
     * dipindah ke sini (dari yang sebelumnya cuma ada inline di
     * DashboardMainFragment.applyConnectButtonState) supaya ConfigActivity
     * (buat memblokir tombol Edit akun aktif) bisa pakai definisi "terhubung"
     * yang PERSIS SAMA dengan yang menentukan tombol Connect/Disconnect di
     * Dashboard -- tidak ada dua sumber kebenaran yang bisa berbeda.
     */
    fun isConnected(status: String): Boolean {
        val isIdle = status == "Belum tersambung" || status == "Terputus"
        val isFailed = !isIdle && status.startsWith("Gagal")
        val isTransitioning = !isIdle && !isFailed && (
            status.contains("Menghubungkan") ||
                status.contains("Membuat") ||
                status.contains("tersambung") ||
                status.contains("Memutuskan") ||
                status.contains("reconnect otomatis", ignoreCase = true)
            )
        return !isIdle && !isFailed && !isTransitioning && status.contains("aktif", ignoreCase = true)
    }

    /**
     * Log mentah real-time ala terminal (gaya DarkTunnel): setiap baris di sini
     * berasal dari event ASLI yang benar-benar terjadi di socket (payload yang
     * betul-betul ditulis ke stream, baris respons yang betul-betul dibaca dari
     * server, banner SSH asli, dll) -- lihat pemanggil [log] di ConnectRelay.kt
     * dan SshjTunnelManager.kt. Bukan teks statis/hiasan.
     */
    private const val MAX_LOG_LINES = 300
    val liveLog = MutableStateFlow<List<String>>(emptyList())
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /** Tambah satu baris log mentah, diberi timestamp "[HH:mm:ss]" sama seperti DarkTunnel. */
    fun log(line: String) {
        // synchronized: dua thread pump bisa memanggil log() hampir bersamaan,
        // tanpa ini salah satu baris bisa hilang (baca-tulis liveLog tidak atomik).
        synchronized(this) {
            val stamped = "[${timeFormat.format(java.util.Date())}] $line"
            val next = liveLog.value + stamped
            liveLog.value = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
    }

    fun clearLog() {
        liveLog.value = emptyList()
    }

    /**
     * FITUR BARU (permintaan user, "log kepanjangan, ringkas baris respons
     * mentah dari server jadi detail pastinya aja"): dipakai SEBELUM baris
     * respons mentah dari server (mis. banner SSH_MSG_USERAUTH_BANNER a.k.a.
     * "Server Message" di SshjTunnelManager, yang kadang berisi ASCII art
     * panjang/rules/iklan dari penjual konfig, lihat contoh di screenshot
     * user) ditulis ke [log] -- BUKAN untuk baris log biasa (status koneksi,
     * error singkat, dll) yang memang sudah singkat dari sononya, itu tetap
     * dilog apa adanya seperti sebelumnya.
     *
     * Aturan ringkas: ambil maksimal [maxLines] baris pertama yang tidak
     * kosong, masing-masing dipotong ke [maxCharsPerLine] karakter kalau
     * lebih panjang -- sisanya (baris & karakter yang dibuang) diringkas jadi
     * SATU baris penutup "... (+N baris lagi)" supaya user tetap tahu ada
     * bagian yang dipotong (bukan cuma hilang diam-diam tanpa jejak), tanpa
     * bikin layar Log penuh ASCII art/teks panjang yang tidak penting.
     */
    fun summarizeLongText(raw: String, maxLines: Int = 4, maxCharsPerLine: Int = 80): String {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return raw.trim()
        val kept = lines.take(maxLines).map {
            if (it.length > maxCharsPerLine) it.take(maxCharsPerLine) + "..." else it
        }
        val remainingLines = lines.size - kept.size
        return if (remainingLines > 0) {
            (kept + "... (+$remainingLines baris lagi)").joinToString("\n")
        } else {
            kept.joinToString("\n")
        }
    }

    fun initSteps(newSteps: List<ConnectionStep>) {
        steps.value = newSteps
    }

    fun start(id: String) = updateStep(id, StepStatus.RUNNING)

    fun success(id: String, detail: String? = null) = updateStep(id, StepStatus.SUCCESS, detail)

    fun fail(id: String, detail: String?) = updateStep(id, StepStatus.ERROR, detail)

    /** Tandai satu tahap tertentu SKIPPED (beda dari [skipRemainingPending] yang menandai semua sisa PENDING). */
    fun skip(id: String, detail: String? = null) = updateStep(id, StepStatus.SKIPPED, detail)

    /** Tahap-tahap yang belum sempat dicoba (masih PENDING) ditandai SKIPPED. */
    fun skipRemainingPending() {
        steps.value = steps.value.map {
            if (it.status == StepStatus.PENDING) it.copy(status = StepStatus.SKIPPED) else it
        }
    }

    /**
     * Dipanggil saat user memutus koneksi secara manual SEBELUM tahapan
     * selesai semua (misal masih di tengah SSH handshake). Tanpa ini,
     * tahap yang lagi RUNNING (spinner) nyangkut selamanya di layar Log
     * walau koneksi sudah benar-benar mati -- karena tidak ada status
     * lanjutan (SUCCESS/ERROR) yang pernah masuk untuk tahap itu.
     * Tahap yang belum sempat dicoba (PENDING) juga ikut ditandai SKIPPED.
     */
    fun markInterrupted() {
        steps.value = steps.value.map {
            if (it.status == StepStatus.RUNNING || it.status == StepStatus.PENDING) {
                it.copy(status = StepStatus.SKIPPED, detail = "Diputuskan")
            } else it
        }
    }

    /**
     * Dipanggil saat user memutus koneksi secara manual SETELAH tunnel
     * sempat aktif penuh (semua/sebagian tahap sudah SUCCESS).
     *
     * FIX (laporan user): "Tahapan Koneksi" di layar Main tetap hijau semua
     * walau VPN sudah dimatikan dan Log sudah bilang "Terputus". Sebabnya:
     * markInterrupted() di atas cuma menyentuh tahap yang masih
     * RUNNING/PENDING -- begitu tunnel sudah connect penuh, semua tahap
     * sudah SUCCESS, jadi tidak ada yang disentuh sama sekali dan titik
     * hijau itu nyangkut selamanya sampai user connect ulang.
     * Di sini tahap yang sudah SUCCESS ikut dikembalikan ke PENDING supaya
     * kartu "Tahapan Koneksi" benar-benar merefleksikan bahwa tunnel sudah
     * tidak aktif, bukan cuma menampilkan snapshot terakhir saat connect.
     */
    fun markDisconnected() {
        steps.value = steps.value.map {
            when (it.status) {
                StepStatus.RUNNING, StepStatus.PENDING ->
                    it.copy(status = StepStatus.SKIPPED, detail = "Diputuskan")
                StepStatus.SUCCESS ->
                    it.copy(status = StepStatus.PENDING, detail = null)
                else -> it
            }
        }
    }

    /** Ambil detail tahap pertama yang gagal -- ini alasan paling akurat kenapa koneksi putus. */
    fun firstErrorDetail(): String? =
        steps.value.firstOrNull { it.status == StepStatus.ERROR }?.let { "${it.label}: ${it.detail ?: "gagal"}" }

    private fun updateStep(id: String, status: StepStatus, detail: String? = null) {
        steps.value = steps.value.map { if (it.id == id) it.copy(status = status, detail = detail) else it }
    }

    fun reset() {
        steps.value = emptyList()
        state.value = "Belum tersambung"
        clearLog()
    }
}
