package com.example.tunnelapp.tunnel

import android.content.Context
import android.util.Log
import com.example.tunnelapp.model.DebugLogStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * FITUR BARU (permintaan user, "fungsi log debug di menu Pengaturan, default
 * off, berfungsi sebagai pengamat log dengan detail error yang tinggi"):
 * observer log ala "verbose crash reporter" LOKAL (tidak dikirim ke server
 * mana pun) -- beda dari [StatusBus.log] yang memang SELALU aktif dan cuma
 * mencatat baris ringkas tahap koneksi (untuk layar "Log Koneksi" biasa),
 * [DebugLog] ini:
 *  - HANYA menulis apa pun ke disk kalau [enabled] true (toggle "Log Debug"
 *    di Pengaturan -> Debug, default OFF -- lihat [DebugLogStore]),
 *  - saat aktif, mencatat detail JAUH lebih tinggi: nama thread, tag,
 *    pesan, DAN seluruh stack trace exception (bukan cuma satu baris
 *    ringkas), untuk error/warning DI MANA PUN di app yang memanggil
 *    [e]/[w], PLUS setiap crash fatal yang tidak tertangkap (lihat
 *    [installUncaughtExceptionHandler]) -- persis kebutuhan "pengamat log
 *    dengan detail error tinggi".
 *  - [e]/[w]/[d] TETAP selalu meneruskan ke [android.util.Log] apa pun
 *    status [enabled]-nya, jadi alur debug lewat `adb logcat` yang sudah
 *    ada sebelumnya SAMA SEKALI tidak berubah -- toggle ini cuma menambah
 *    SATU LAPIS tambahan: salinan persisten di file internal app yang bisa
 *    dilihat/dibagikan lewat UI (lihat SettingsActivity), berguna kalau
 *    error terjadi saat user TIDAK sedang disambungkan ke `adb`.
 *
 * File ditulis ke [Context.filesDir] (private ke app ini, tidak butuh izin
 * storage apa pun), dibatasi [MAX_FILE_BYTES] -- begitu lebih besar, separuh
 * AWAL file (baris tertua) dibuang supaya file tidak tumbuh tanpa batas
 * kalau toggle dibiarkan lama.
 */
object DebugLog {
    private const val TAG = "DebugLog"
    private const val FILE_NAME = "debug_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024 // 512 KB

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val writeLock = Any()

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var appContext: Context? = null

    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Dipanggil SEKALI dari [com.example.tunnelapp.TunnelApplication.onCreate]
     * -- sedini mungkin di lifecycle proses, supaya
     * [installUncaughtExceptionHandler] terpasang SEBELUM Activity mana pun
     * sempat crash. Aman dipanggil berkali-kali (no-op setelah yang pertama).
     */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        enabled = DebugLogStore.isEnabled(app)
        installUncaughtExceptionHandler()
    }

    /**
     * Dipanggil dari SettingsActivity begitu user menekan switch "Log
     * Debug" -- efeknya LANGSUNG (tidak perlu restart app), sama seperti
     * toggle tema/bahasa di layar yang sama.
     */
    fun setEnabled(context: Context, isEnabled: Boolean) {
        DebugLogStore.setEnabled(context, isEnabled)
        enabled = isEnabled
        if (isEnabled) {
            writeEntry("INFO", TAG, null, "Log Debug diaktifkan dari Pengaturan")
        }
    }

    fun isEnabled(): Boolean = enabled

    /** Error tingkat tinggi -- SELALU ke Logcat, PLUS ke file (dengan stack trace lengkap) kalau [enabled]. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (enabled) writeEntry("ERROR", tag, throwable, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        if (enabled) writeEntry("WARN", tag, throwable, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        if (enabled) writeEntry("DEBUG", tag, null, message)
    }

    /**
     * Bungkus [Thread.defaultUncaughtExceptionHandler] yang SUDAH ada
     * (jangan sampai menggantikan handler bawaan Android yang menampilkan
     * dialog "App berhenti"/menulis ANR trace) -- di sini CUMA menyisipkan
     * pencatatan detail SEBELUM diteruskan ke handler aslinya, supaya
     * perilaku crash app (force-close, dst) TIDAK BERUBAH SAMA SEKALI,
     * cuma detailnya sempat tercatat dulu ke file kalau [enabled] true.
     */
    private fun installUncaughtExceptionHandler() {
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (enabled) {
                    writeEntry("FATAL", "UncaughtException", throwable, "Thread: ${thread.name}")
                }
            } catch (_: Throwable) {
                // Jangan sampai logger sendiri yang bikin crash-handler-nya gagal jalan.
            }
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeEntry(level: String, tag: String, throwable: Throwable?, message: String) {
        val ctx = appContext ?: return
        val sb = StringBuilder()
        sb.append('[').append(timeFormat.format(java.util.Date())).append("] ")
        sb.append(level).append('/').append(tag)
        sb.append(" [thread=").append(Thread.currentThread().name).append("]: ")
        sb.append(message).append('\n')
        if (throwable != null) {
            sb.append(Log.getStackTraceString(throwable)).append('\n')
        }
        synchronized(writeLock) {
            try {
                val file = logFile(ctx)
                file.appendText(sb.toString())
                trimIfTooLarge(file)
            } catch (_: Exception) {
                // Gagal tulis file tidak boleh sampai mengganggu alur app normal.
            }
        }
    }

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Buang separuh AWAL file (baris tertua) begitu ukurannya lewat [MAX_FILE_BYTES]. */
    private fun trimIfTooLarge(file: File) {
        if (file.length() <= MAX_FILE_BYTES) return
        val text = file.readText()
        val keepFrom = (text.length / 2).coerceAtMost(text.length)
        val trimmed = "…(log lama dipangkas)…\n" + text.substring(keepFrom)
        file.writeText(trimmed)
    }

    /** Isi lengkap file log debug -- dipakai layar Pengaturan untuk lihat/bagikan. */
    fun readLogText(context: Context): String {
        val file = logFile(context)
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        synchronized(writeLock) {
            val file = logFile(context)
            if (file.exists()) file.writeText("")
        }
    }
}
