package com.example.tunnelapp.tunnel

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * [TunEngine] berbasis badvpn-tun2socks (fork shadowsocks/badvpn -- lihat
 * catatan panjang di app/src/main/cpp/CMakeLists.txt soal kenapa fork ini,
 * bukan ambrop72/badvpn upstream). BEDA FUNDAMENTAL dari [HevSocks5Engine]:
 * hev jalan sebagai native lib lewat JNI (satu proses dengan app), badvpn
 * di sini adalah BINARY TERPISAH yang di-spawn lewat [ProcessBuilder] --
 * mirip cara v2rayNG/Shadowsocks-Android menjalankan core native mereka.
 *
 * PERINGATAN JUJUR (WAJIB DIBACA sebelum mengandalkan ini di produksi):
 * lingkungan penyusunan kode ini TIDAK punya Android SDK/NDK maupun device
 * fisik untuk compile+install+jalankan APK sungguhan -- jadi kelas ini
 * DITULIS dari pengetahuan umum cara badvpn-tun2socks dipakai di app-app
 * Android sejenis (argumen CLI di bawah mengikuti pola fork
 * shadowsocks/badvpn yang menambahkan opsi --tunfd/--tunmtu KHUSUS untuk
 * Android, karena upstream ambrop72/badvpn ASLI tidak bisa menerima fd TUN
 * yang sudah terbuka dari VpnService.Builder().establish() -- ia cuma bisa
 * buka /dev/net/tun sendiri, yang butuh root). BELUM PERNAH dites jalan
 * sungguhan. WAJIB: build APK ini, jalankan di device fisik, pindah ke
 * ENGINE_BADVPN dari kartu "VPN Setting", lalu perhatikan Logcat tag
 * [TAG] -- kalau argumen di bawah ternyata tidak cocok dengan versi
 * binary yang benar-benar ter-compile (mis. nama flag beda), proses akan
 * langsung exit dengan kode bukan 0 dan pesan errornya kelihatan di log.
 *
 * @param context dipakai untuk mencari lokasi binary hasil extract APK
 * ([Context.applicationInfo.nativeLibraryDir]) -- lihat catatan
 * [useLegacyPackaging] di app/build.gradle.kts: WAJIB true, kalau tidak
 * binary ini tidak pernah benar-benar ada sebagai file fisik di device
 * (APK modern default-nya TIDAK meng-extract native lib ke disk lagi),
 * dan [ProcessBuilder] di bawah akan gagal total dengan "No such file".
 */
class BadVpnTun2socksEngine(private val context: Context) : TunEngine {

    companion object {
        private const val TAG = "BadVpnTun2socksEngine"

        // Nama file HARUS diawali "lib" dan diakhiri ".so" walau isinya
        // sebenarnya binary ELF EXECUTABLE, bukan shared library -- itu
        // konvensi wajib supaya AGP/PackageManager mau meng-extract-nya ke
        // nativeLibraryDir sebagai bagian dari native libs APK (lihat
        // custom command yang menyalin binary ini di CMakeLists.txt).
        private const val BINARY_NAME = "libbadvpn-tun2socks.so"

        // Waktu tunggu maksimal proses benar-benar berhenti setelah
        // destroy() dipanggil, sebelum kita menyerah dan cuma nge-log --
        // sama pola dengan HevSocks5Engine.stop() (join 2000ms), TAPI di
        // sini pakai Process.waitFor(timeout) karena tidak ada thread yang
        // kita mulai sendiri untuk di-join (pump log jalan di thread daemon
        // terpisah, lihat startStreamPump()).
        private const val STOP_WAIT_MS = 2000L
    }

    private var process: Process? = null
    private var stdoutPump: Thread? = null
    private var stderrPump: Thread? = null
    private var watcherThread: Thread? = null

    /** Sama fungsinya dengan [HevSocks5Engine.stoppedByUs] -- lihat catatan di sana. */
    @Volatile
    private var stoppedByUs = false

    override fun start(
        tunFd: Int,
        tunAddress: String,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        onUnexpectedStop: (() -> Unit)?
    ) {
        stoppedByUs = false

        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            // Pesan error SENGAJA eksplisit menyebut penyebab paling mungkin
            // (bukan cuma "file not found" generik) -- lihat catatan
            // useLegacyPackaging di kepala file ini & app/build.gradle.kts.
            throw IllegalStateException(
                "Binary badvpn-tun2socks tidak ditemukan di ${binary.absolutePath}. " +
                    "Kemungkinan: (1) app/build.gradle.kts belum di-set " +
                    "packaging.jniLibs.useLegacyPackaging = true, atau (2) " +
                    "ExternalProject badvpn di CMakeLists.txt belum pernah " +
                    "berhasil di-build (cek log Gradle langkah " +
                    "externalNativeBuild untuk error clone/compile)."
            )
        }
        if (!binary.canExecute()) {
            // Beberapa device/ROM (terutama yang strip execute bit saat
            // extract) butuh ini di-set eksplisit -- aman dipanggil
            // berkali-kali, no-op kalau sudah executable.
            binary.setExecutable(true)
        }

        // --netif-ipaddr HARUS beda dari alamat TUN device sendiri (badvpn
        // meniru dirinya sendiri sebagai "gateway" virtual di jaringan
        // /30 kecil bersama tunAddress) -- konvensi umum: satu alamat di
        // atasnya dalam /30 yang sama. tunAddress app ini selalu
        // "10.10.0.2" (lihat MyVpnService.TUN_ADDRESS), jadi netmask /30
        // (255.255.255.252) dengan gateway "10.10.0.1" aman dipakai tanpa
        // konflik dengan subnet TUN device manapun yang dipilih user.
        val netifIpAddr = "10.10.0.1"
        val netifNetmask = "255.255.255.252"

        val command = mutableListOf(
            binary.absolutePath,
            "--tunfd", tunFd.toString(),
            "--tunmtu", mtu.toString(),
            "--netif-ipaddr", netifIpAddr,
            "--netif-netmask", netifNetmask,
            "--socks-server-addr", "$socksHost:$socksPort",
            // Biarkan query DNS device lewat juga (badvpn tun2socks punya
            // resolver internal opsional) -- SENGAJA TIDAK diaktifkan
            // (--dns-resolver) karena app ini sudah handle DNS lewat jalur
            // lain (Socks5Server/DNS-over-TCP, lihat catatan di
            // VpnSettingsStore soal udpgwPort) -- mengaktifkan dua-duanya
            // sekaligus belum tentu aman/perlu digunakan bersamaan tanpa
            // pernah dites.
            "--loglevel", "notice"
        )

        Log.i(TAG, "Menjalankan: ${command.joinToString(" ")}")

        val proc = try {
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw IllegalStateException("Gagal menjalankan badvpn-tun2socks: ${e.message}", e)
        }
        process = proc

        stdoutPump = startStreamPump(proc.inputStream, "stdout")
        stderrPump = startStreamPump(proc.errorStream, "stderr")

        // Thread TERPISAH (bukan blocking di [start] ini sendiri) yang
        // menunggu proses exit -- pola sama seperti thread native di
        // HevSocks5Engine, supaya caller ([MyVpnService.startTunEngine])
        // tidak ikut ter-block menunggu proses ini berakhir.
        watcherThread = Thread({
            val exitCode = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted saat menunggu badvpn-tun2socks exit", e)
                return@Thread
            }
            Log.i(TAG, "badvpn-tun2socks berhenti, kode: $exitCode")
            if (!stoppedByUs) {
                Log.w(TAG, "badvpn-tun2socks berhenti TAK TERDUGA (kode $exitCode)")
                onUnexpectedStop?.invoke()
            }
        }, "badvpn-tun2socks-watcher").apply { start() }
    }

    /**
     * Alirkan stdout/stderr proses ke Logcat -- tanpa ini, PIPE stdout/stderr
     * proses anak bisa penuh (OS buffer terbatas) dan proses itu sendiri
     * jadi macet menunggu ada yang membaca, KALAU binary-nya cukup cerewet
     * logging-nya. Sekaligus berguna untuk debugging kalau proses exit
     * tak terduga (lihat watcherThread di atas) -- baris log terakhir
     * sebelum exit code biasanya berisi alasannya.
     */
    private fun startStreamPump(stream: java.io.InputStream, label: String): Thread {
        return Thread({
            try {
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "[$label] $line")
                    }
                }
            } catch (e: Exception) {
                // Normal terjadi begitu proses di-destroy() -- stream ikut
                // tertutup paksa di tengah pembacaan. Bukan error sungguhan.
                Log.d(TAG, "Stream pump [$label] berhenti: ${e.message}")
            }
        }, "badvpn-tun2socks-$label").apply { isDaemon = true; start() }
    }

    override fun stop() {
        stoppedByUs = true
        val proc = process ?: return
        process = null

        // destroy() dulu (SIGTERM) -- kasih kesempatan proses keluar bersih
        // sebelum destroyForcibly() (SIGKILL) kalau ternyata macet.
        proc.destroy()
        val exitedCleanly = try {
            proc.waitFor(STOP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!exitedCleanly && proc.isAlive) {
            Log.w(TAG, "badvpn-tun2socks tidak berhenti dalam ${STOP_WAIT_MS}ms, paksa destroyForcibly()")
            proc.destroyForcibly()
        }

        try {
            watcherThread?.join(STOP_WAIT_MS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted saat menunggu watcherThread berhenti", e)
        }
        watcherThread = null
        // stdout/stderrPump adalah daemon thread yang berhenti sendiri
        // begitu stream proses tertutup (lihat startStreamPump) -- tidak
        // perlu di-join eksplisit, cukup dilepas referensinya.
        stdoutPump = null
        stderrPump = null
    }
}
