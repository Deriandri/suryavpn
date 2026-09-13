package com.example.tunnelapp.tunnel

import android.content.Context
import android.util.Log
import com.example.tunnelapp.model.VpnSettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [TunEngine] berbasis badvpn-tun2socks (ambrop72/badvpn, komponen `tun2socks`
 * saja -- BUKAN `ncd`/komponen lain di repo yang sama). BEDA ARSITEKTUR
 * penting dibanding [HevSocks5Engine]: hev-socks5-tunnel dipanggil sebagai
 * *library* lewat JNI (satu proses dengan app), sedangkan badvpn-tun2socks di
 * sini dijalankan sebagai *proses terpisah* (native executable) lewat
 * [ProcessBuilder] -- karena upstream badvpn TIDAK menyediakan API
 * start/stop yang bisa dipanggil sebagai library (dia murni program CLI
 * dengan `main()`, berhenti lewat SIGTERM). Konsekuensinya:
 *  - [stop] mengirim SIGTERM (`Process.destroy()`), BUKAN memanggil fungsi
 *    native apa pun.
 *  - Tidak ada resiko native crash membawa turun proses app utama (beda
 *    proses OS) -- tapi juga berarti overhead 1 proses ekstra + IPC lewat fd
 *    yang di-dup, bukan panggilan fungsi langsung.
 *
 * Binary `libbadvpn-tun2socks.so` (nama sengaja pola `lib*.so` walau ISINYA
 * native EXECUTABLE, bukan shared library -- lihat catatan panjang di
 * `app/src/main/cpp/CMakeLists.txt` kenapa) diharapkan sudah ada di
 * `applicationInfo.nativeLibraryDir` (diekstrak otomatis oleh Android dari
 * `jniLibs/<abi>/` saat instal APK, PERSIS seperti .so JNI biasa).
 *
 * PENTING -- lihat `INTEGRASI_BADVPN.md` di root project: argumen CLI di
 * bawah (`--tundev`, `--netif-ipaddr`, dst.) saya susun berdasarkan dokumentasi
 * publik badvpn-tun2socks yang saya ingat, TAPI saya TIDAK PUNYA akses
 * jaringan di sandbox ini untuk clone source-nya dan mencocokkan 100% ke
 * `--help` output versi yang benar-benar ke-compile. WAJIB jalankan
 * `libbadvpn-tun2socks.so --help` (lewat adb shell setelah APK terpasang,
 * atau jalankan binary hasil build NDK langsung di komputer dev kalau host
 * arch cocok) sebelum rilis, cocokkan tiap argumen di [buildArgs] ke situ.
 */
class BadVpnEngine(private val context: Context) : TunEngine {

    companion object {
        private const val TAG = "BadVpnEngine"
        private const val BINARY_NAME = "libbadvpn-tun2socks.so"

        // Netmask "virtual" utk netif internal badvpn-tun2socks sendiri --
        // TIDAK harus sama dengan prefix /32 yang dipakai
        // VpnService.Builder().addAddress() di MyVpnService (itu urusan
        // routing table Android, P2P, terpisah total dari virtual netif
        // internal tun2socks/lwIP di dalam badvpn). 255.255.255.0 adalah
        // nilai yang paling umum dipakai di contoh/tutorial badvpn-tun2socks
        // utk skenario satu klien seperti ini -- BELUM diverifikasi di sini,
        // lihat INTEGRASI_BADVPN.md.
        private const val NETIF_NETMASK = "255.255.255.0"
    }

    private var process: Process? = null
    private var logThread: Thread? = null
    private var watchThread: Thread? = null

    /** Sama perannya dengan [HevSocks5Engine.stoppedByUs] -- lihat komentar di sana. */
    @Volatile
    private var stoppedByUs = false

    private fun resolveBinary(): File {
        val dir = context.applicationInfo.nativeLibraryDir
            ?: throw IllegalStateException("nativeLibraryDir null -- APK tidak ter-install dengan benar?")
        val bin = File(dir, BINARY_NAME)
        if (!bin.exists()) {
            throw IllegalStateException(
                "Binary $BINARY_NAME tidak ditemukan di $dir -- CMake belum berhasil " +
                    "membangun target badvpn_tun2socks_copy, atau ABI device ini tidak " +
                    "termasuk yang di-build (lihat abiFilters di app/build.gradle.kts)."
            )
        }
        return bin
    }

    private fun buildArgs(
        binary: File,
        tunFd: Int,
        tunAddress: String,
        mtu: Int,
        socksHost: String,
        socksPort: Int
    ): List<String> {
        val settings = VpnSettingsStore.load(context)
        val args = mutableListOf(
            binary.absolutePath,
            // Trik path /proc/self/fd/<N> supaya badvpn-tun2socks (yang cuma
            // tahu cara `open()` sebuah PATH, bukan menerima fd int langsung
            // lewat argumen) tetap bisa memakai fd TUN yang sudah dibuka
            // VpnService.Builder().establish() -- membuka symlink ini
            // menghasilkan fd baru yang menunjuk ke file description YANG
            // SAMA (dup-like semantics kernel Linux utk /proc/self/fd/),
            // teknik umum dipakai integrasi badvpn/openvpn di Android tanpa
            // root. `self` aman dipakai karena proses ini (child) mewarisi
            // fd milik proses induk (app) hanya kalau fd itu tidak
            // FD_CLOEXEC -- lihat catatan CLOEXEC di MyVpnService kalau ada
            // masalah "Bad file descriptor" di log badvpn.
            "--tundev", "/proc/self/fd/$tunFd",
            "--netif-ipaddr", tunAddress,
            "--netif-netmask", NETIF_NETMASK,
            "--socks-server-addr", "$socksHost:$socksPort",
            "--loglevel", "warning"
        )
        // udpgw: server companion `badvpn-udpgw` di sisi SSH server, format
        // arg persis sama dengan yang sudah dipakai app ini utk UdpgwClient
        // custom (lihat VpnSettings.udpgwPort) -- di sini kita serahkan
        // sepenuhnya ke tun2socks bawaan (bukan implementasi Kotlin sendiri)
        // supaya tidak dobel jalur UDP kalau BADVPN yang dipilih sbg tun engine.
        if (settings.udpgwPort > 0) {
            args += listOf("--udpgw-remote-server-addr", "$socksHost:${settings.udpgwPort}")
        }
        return args
    }

    override fun start(
        tunFd: Int,
        tunAddress: String,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        onUnexpectedStop: (() -> Unit)?
    ) {
        stoppedByUs = false
        val binary = resolveBinary()
        val args = buildArgs(binary, tunFd, tunAddress, mtu, socksHost, socksPort)

        Log.i(TAG, "Menjalankan: ${args.joinToString(" ")}")

        val builder = ProcessBuilder(args)
        builder.redirectErrorStream(true)
        val proc = builder.start()
        process = proc

        // Pompa stdout+stderr proses child ke Logcat terus-menerus -- WAJIB
        // ada (bukan cuma nice-to-have): kalau tidak ada yang membaca pipe
        // ini, buffer-nya bisa penuh dan bikin proses child BLOCKING saat
        // nulis log, yang ujungnya bisa menghentikan tunnel-nya sendiri.
        logThread = Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, line)
                }
            } catch (e: Exception) {
                // Stream ditutup krn proses berhenti (stop() atau mati sendiri) -- normal, abaikan.
            }
        }, "badvpn-tun2socks-log").apply {
            isDaemon = true
            start()
        }

        watchThread = Thread({
            val exitCode = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted saat menunggu badvpn-tun2socks", e)
                return@Thread
            }
            Log.i(TAG, "badvpn-tun2socks berhenti, kode: $exitCode")
            if (!stoppedByUs) {
                // Sama seperti HevSocks5Engine: proses berhenti sendiri
                // (crash, fatal error socket SOCKS5 lokal, dll), BUKAN
                // karena stop() kita panggil -- trigger reconnect otomatis.
                Log.w(TAG, "badvpn-tun2socks berhenti TAK TERDUGA (kode $exitCode)")
                onUnexpectedStop?.invoke()
            }
        }, "badvpn-tun2socks-watch").apply { start() }
    }

    override fun stop() {
        stoppedByUs = true
        val proc = process ?: return
        // SIGTERM dulu (graceful -- badvpn-tun2socks upstream menangani
        // SIGTERM utk keluar bersih dari event loop-nya), baru paksa kalau
        // dalam 2 detik belum juga keluar.
        proc.destroy()
        try {
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "badvpn-tun2socks tidak berhenti dalam 2 detik, destroyForcibly()")
                proc.destroyForcibly()
                proc.waitFor(1, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted saat menunggu badvpn-tun2socks berhenti", e)
        }
        try {
            watchThread?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted saat join watchThread", e)
        }
        watchThread = null
        logThread = null
        process = null
    }
}
