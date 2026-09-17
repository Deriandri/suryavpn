package com.example.tunnelapp.tunnel

import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TunEngine] berbasis hev-socks5-tunnel (heiher/hev-socks5-tunnel, native C,
 * bundling lwIP internal sebagai stack TCP/IP-nya -- lihat
 * `app/src/main/cpp/CMakeLists.txt`). Ini SATU-SATUNYA engine yang punya
 * binary ter-compile & teruji jalan di app ini per hari ini.
 *
 * `HevSocks5Bridge.startTunnel()` sendiri BLOCKING (tidak return sampai
 * `stopTunnel()` dipanggil), jadi dijalankan di thread `Thread` terpisah di
 * sini -- caller ([MyVpnService]) cukup panggil [start] dari coroutine biasa,
 * tidak perlu tahu detail threading-nya.
 */
class HevSocks5Engine : TunEngine {

    companion object {
        private const val TAG = "HevSocks5Engine"

        // BUG FIX (crash native tak terduga saat reconnect lama/hard reset
        // berulang -- laporan user: "matikan internet, tunggu reconnect,
        // aplikasi crash total, tidak ada log error sama sekali"):
        // HevSocks5Bridge adalah SATU library native GLOBAL per proses
        // (System.loadLibrary sekali), TIDAK didesain multi-instance --
        // hanya ada satu context/state internal di sisi native. Sebelumnya,
        // kalau engine.stop() (dipanggil lewat
        // MyVpnService.runBlockingWithTimeout, batas 5000ms) TIMEOUT karena
        // thread native lama belum sempat benar-benar berhenti, caller di
        // MyVpnService TETAP LANJUT ("melanjutkan tanpa menunggu") ke
        // hardResetAndRetry()/reconnect berikutnya, yang lalu bikin
        // HevSocks5Engine() instance BARU dan panggil start() lagi -- itu
        // artinya HevSocks5Bridge.startTunnel() dipanggil DUA KALI hampir
        // bersamaan di native code yang sama, dengan config/fd berbeda.
        // Ini use-after-free/concurrent-global-state di sisi native ->
        // SIGSEGV/abort yang membunuh proses SEKETIKA, TIDAK LEWAT
        // Thread.UncaughtExceptionHandler (DebugLog) sama sekali -- makanya
        // log debug berhenti mendadak tanpa baris error/fatal apa pun.
        //
        // Guard statis (bukan per-instance, karena native state-nya memang
        // global) ini mencegahnya: start() BARU menunggu (polling, maksimal
        // [NATIVE_STOP_WAIT_MS]) sampai berhasil mengambil "slot" native
        // lewat [nativeThreadAlive].compareAndSet(false, true) -- slot itu
        // cuma dilepas (di-set false) DARI DALAM thread native itu sendiri
        // setelah startTunnel() benar-benar return.
        //
        // UPDATE (laporan user setelah fix di atas dipasang): ternyata di
        // build native ini, begitu thread native macet, dia macet
        // PERMANEN -- HevSocks5Bridge.stopTunnel() tidak pernah benar-benar
        // membuatnya return (kemungkinan native-nya nyangkut di blocking
        // read/epoll yang tidak pernah bangun lagi begitu interface fisik
        // sudah ditutup duluan). Akibatnya [nativeThreadAlive] TIDAK PERNAH
        // balik ke false lagi SELAMANYA dalam proses yang sama -- kalau
        // start() cuma throw biasa di sini, app jadi macet permanen: user
        // tekan Connect lagi pun SELALU gagal dengan pesan yang sama,
        // walaupun tunnel lama sudah lama mati & TUN interface baru sudah
        // siap. Satu-satunya cara aman memulihkan state native yang GLOBAL
        // per-proses ini adalah proses baru dari nol -- jadi begitu batas
        // waktu ini kelewat, app SENGAJA bunuh proses sendiri
        // (Process.killProcess) daripada membiarkan user terjebak di loop
        // gagal-terus tanpa jalan keluar selain force-stop manual dari
        // Pengaturan Android. User perlu buka ulang app sekali setelah ini
        // -- static state akan bersih lagi di proses baru.
        private val nativeThreadAlive = AtomicBoolean(false)
        private const val NATIVE_STOP_WAIT_MS = 4000L
        private const val NATIVE_STOP_POLL_MS = 100L
    }

    private var thread: Thread? = null

    /**
     * PENTING: dicek di dalam thread native sebelum memanggil [onUnexpectedStop].
     * Kalau true, berarti [stop] memang sengaja dipanggil (user disconnect,
     * atau MyVpnService lagi bikin ulang tunnel buat reconnect) -- BUKAN
     * kematian tak terduga, jadi callback TIDAK dipanggil.
     */
    @Volatile
    private var stoppedByUs = false

    override fun start(tunFd: Int, tunAddress: String, mtu: Int, socksHost: String, socksPort: Int, onUnexpectedStop: (() -> Unit)?) {
        // compareAndSet(false, true): kalau sukses, kita satu-satunya
        // pemilik "slot" native saat ini. Kalau gagal (masih ada thread
        // native sebelumnya yang belum set balik ke false di
        // thread.apply{} bawah), tunggu sebentar (polling, TANPA memegang
        // lock apa pun -- supaya thread native sebelumnya bebas
        // menyelesaikan compareAndSet-nya sendiri) sampai [NATIVE_STOP_WAIT_MS],
        // baru menyerah kalau tetap tidak dapat slot.
        val deadline = System.currentTimeMillis() + NATIVE_STOP_WAIT_MS
        while (!nativeThreadAlive.compareAndSet(false, true)) {
            if (System.currentTimeMillis() >= deadline) {
                // Macet PERMANEN, bukan cuma lambat -- lihat catatan
                // panjang "UPDATE" di companion object. Log dulu (SINKRON,
                // langsung ke disk lewat DebugLog.e) supaya kelihatan di
                // Log Debug pas app dibuka ulang, baru bunuh proses.
                DebugLog.e(
                    TAG,
                    "hev-socks5-tunnel sebelumnya MACET PERMANEN (tidak selesai " +
                        "setelah ${NATIVE_STOP_WAIT_MS}ms) -- memaksa restart proses " +
                        "total untuk memulihkan state native yang global. Buka ulang " +
                        "app untuk sambung lagi."
                )
                Process.killProcess(Process.myPid())
                // Baris di bawah tidak akan pernah tereksekusi -- killProcess()
                // di atas langsung mematikan proses ini. Cuma buat memenuhi
                // tipe Kotlin (compiler tidak tahu killProcess() tidak pernah
                // return secara normal).
                throw IllegalStateException("Proses dihentikan paksa (native tunnel macet permanen)")
            }
            Thread.sleep(NATIVE_STOP_POLL_MS)
        }
        stoppedByUs = false
        // CATATAN soal fitur kecepatan yang PERNAH dicoba di sini lalu
        // di-revert -- lihat blok komentar REVERT di bawah untuk detail:
        //  - misc.tcp-buffer-size (dinaikkan ke 65536) -- DIMATIKAN.
        //  - SENGAJA TIDAK PERNAH diaktifkan sama sekali (beda dari yang di
        //    atas, ini bukan revert tapi memang dari awal tidak dipakai):
        //    tunnel.multi-queue: true -- didesain untuk beberapa fd TUN
        //    paralel, sedangkan integrasi VpnService Android di app ini
        //    cuma membuka SATU fd (ParcelFileDescriptor dari establish()).
        //    socks5.pipeline: true -- varian handshake SOCKS5 yang
        //    Socks5Server.kt (implementasi minimal buatan sendiri) belum
        //    tentu dukung, resiko merusak handshake demi untung kecepatan
        //    yang nyaris nol (toh ini localhost, RTT-nya sudah ~0).
        // REVERT (laporan user: "terhubung tapi internet tidak jalan" di mode
        // SSH, tepat setelah fitur ini ditambahkan): tcp-buffer-size ini
        // dipakai SEMUA mode (SSH maupun Xray) karena letaknya di config
        // native TUN engine, bukan spesifik SSH -- jadi walau bug dilaporkan
        // di mode SSH, ini tetap kandidat kuat karena project ini build
        // hev-socks5-tunnel LANGSUNG dari branch `main` GitHub (bukan versi
        // yang di-pin), jadi saya tidak bisa pastikan 100% versi yang
        // ke-compile PERSIS sama behaviour-nya dengan README yang saya cek.
        // Tanpa server/device nyata untuk mengetes langsung, DIMATIKAN DULU
        // (dikomentari, bukan dihapus) sampai ada cara aman untuk verifikasi.
        val yamlConfig = """
            tunnel:
              name: tun0
              mtu: $mtu
              ipv4: $tunAddress
            socks5:
              address: $socksHost
              port: $socksPort
              udp: 'udp'
            misc:
              log-level: warn
              log-file: stdout
        """.trimIndent()

        thread = Thread({
            val result = HevSocks5Bridge.startTunnel(yamlConfig, tunFd)
            // Native thread ini BENAR-BENAR selesai sekarang -- baru di sini
            // aman melepas slot & mengizinkan start() berikutnya (lihat
            // guard compareAndSet di atas).
            nativeThreadAlive.set(false)
            Log.i(TAG, "hev-socks5-tunnel berhenti, kode: $result")
            if (!stoppedByUs) {
                // Engine berhenti sendiri (bukan diminta stop()) -- native lib exit
                // atau socket ke SOCKS5 lokal putus fatal. Ini persis kondisi
                // "tunnel mati sendiri" yang mau dideteksi.
                Log.w(TAG, "hev-socks5-tunnel berhenti TAK TERDUGA (kode $result)")
                onUnexpectedStop?.invoke()
            }
        }, "hev-socks5-tunnel").apply { start() }
    }

    override fun stop() {
        stoppedByUs = true
        val t = thread ?: return
        HevSocks5Bridge.stopTunnel()
        try {
            t.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted saat menunggu hev-tunnel berhenti", e)
        }
        thread = null
    }
}
