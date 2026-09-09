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
     * Log mentah real-time ala terminal (gaya DarkTunnel): setiap baris di sini
     * berasal dari event ASLI yang benar-benar terjadi di socket (payload yang
     * betul-betul ditulis ke stream, baris respons yang betul-betul dibaca dari
     * server, banner SSH asli, dll) -- lihat pemanggil [log] di ConnectRelay.kt
     * dan SshTunnelManager.kt. Bukan teks statis/hiasan.
     */
    private const val MAX_LOG_LINES = 300
    val liveLog = MutableStateFlow<List<String>>(emptyList())
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /** Tambah satu baris log mentah, diberi timestamp "[HH:mm:ss]" sama seperti DarkTunnel. */
    fun log(line: String) {
        val stamped = "[${timeFormat.format(java.util.Date())}] $line"
        val next = liveLog.value + stamped
        liveLog.value = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
    }

    fun clearLog() {
        liveLog.value = emptyList()
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
