package com.example.tunnelapp.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.example.tunnelapp.DashboardActivity
import com.example.tunnelapp.R
import com.example.tunnelapp.model.ServerConfig
import com.example.tunnelapp.model.GeneralSettingsStore
import com.example.tunnelapp.model.VpnSettingsStore
// FIX build error "Unresolved reference: toServerConfigOrNull": fungsi
// ekstensi di ServerConfig.kt (package model) ini dipakai di
// pickNextFallbackConfig() tapi belum pernah di-import di sini -- fungsi
// ekstensi Kotlin TIDAK otomatis ketemu hanya karena tipe datanya (SavedConfig)
// diakses lewat nama lengkap (fully-qualified), harus di-import eksplisit.
import com.example.tunnelapp.model.toServerConfigOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnService lengkap: SSH/Xray + bridging TUN<->SOCKS5 lewat [HevSocks5Engine]
 * (satu-satunya [TunEngine] yang dipakai app ini).
 *
 * Alur penuh:
 *  1. Buat TUN interface
 *  2. Konek SSH atau Xray-core -> SOCKS5 lokal aktif di 127.0.0.1:<socksPort>
 *  3. Jalankan hev-socks5-tunnel (native/JNI) dengan fd TUN + alamat SOCKS5
 *     tadi -> SEKARANG semua trafik device benar-benar lewat tunnel.
 */
class MyVpnService : VpnService() {

    companion object {
        private const val TAG = "MyVpnService"
        const val ACTION_CONNECT = "com.example.tunnelapp.CONNECT"
        const val ACTION_DISCONNECT = "com.example.tunnelapp.DISCONNECT"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SSL_SNI = "extra_ssl_sni"
        const val EXTRA_PAYLOAD = "extra_payload"
        const val EXTRA_PROXY_HOST = "extra_proxy_host"
        const val EXTRA_PROXY_PORT = "extra_proxy_port"
        const val EXTRA_TLS_VERSION = "extra_tls_version"
        const val EXTRA_WEBSOCKET_ENABLED = "extra_websocket_enabled"
        const val EXTRA_WS_PATH = "extra_ws_path"
        const val EXTRA_PROXY_RAW_MODE = "extra_proxy_raw_mode"
        const val EXTRA_XRAY_LINK = "extra_xray_link"
        const val EXTRA_CUSTOM_HEADERS = "extra_custom_headers"
        const val EXTRA_IGNORE_CERT_ERRORS = "extra_ignore_cert_errors"
        const val EXTRA_DNS1 = "extra_dns1"
        const val EXTRA_DNS2 = "extra_dns2"
        // FIX/FITUR BARU (fallback akun cadangan): id profil ProfileStore yang
        // lagi dipakai -- dikirim dari DashboardMainFragment supaya
        // MyVpnService tahu profil mana yang HARUS DIKECUALIKAN saat menyusun
        // daftar akun cadangan (lihat buildFallbackProfiles()). Opsional/boleh
        // kosong (mis. dipanggil dari kode lama) -- kalau kosong, fallback
        // tetap jalan tapi tidak mengecualikan profil manapun secara pasti.
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val TUN_ADDRESS = "10.10.0.2"
        private const val DEFAULT_DNS = "1.1.1.1"
        private const val WAKE_LOCK_TAG = "TunnelApp:VpnKeepAwake"

        // --- Deteksi & reconnect otomatis kalau tunnel mati sendiri ---
        private const val MAX_RECONNECT_ATTEMPTS = 3
        // FIX (stabilitas, laporan user): dulu setelah MAX_RECONNECT_ATTEMPTS
        // kali reconnect ringan + 1x reset penuh masih gagal juga, app
        // BENAR-BENAR MENYERAH (stopVpn()) -- beda dari app tunnel established
        // (DarkTunnel, HTTP Custom, dll) yang tidak pernah nyerah sendiri,
        // terus coba nyambung ulang sampai user sendiri menekan Disconnect.
        // Delay antar percobaan naik terus (3000ms * reconnectAttempt) tapi
        // di-cap di sini supaya tidak makin lama makin jarang tanpa batas.
        private const val RECONNECT_BACKOFF_CAP_MS = 30_000L
        // FIX (laporan user: "koneksi suka putus sendiri" dibanding app lain
        // dengan metode sama): interval lama (10s) + threshold lama (2x) makin
        // KEEP_ALIVE_TIMEOUT_MS 5s berarti tunnel bisa dianggap "mati" cuma
        // gara-gara SATU probe HTTP yang kebetulan lambat ~20-30 detik --
        // padahal di jaringan seluler Indonesia, request polos ke port 80
        // gampang sekali kena throttle/lambat sesaat oleh operator walau
        // tunnel SSH/Xray-nya sendiri sehat. Watchdog jadi salah tangkap
        // "lambat sesaat" sebagai "mati total" lalu memutus paksa tunnel yang
        // sebenarnya baik-baik saja. Sekarang interval diperjarang & threshold
        // dinaikkan supaya butuh kegagalan yang jauh lebih konsisten (bukan
        // cuma jitter sesaat) sebelum benar-benar dianggap putus.
        // FIX (laporan user: "reconnect sendiri tiap beberapa menit walau
        // Auto Ping sudah dimatikan"): akar masalahnya BUKAN cuma Auto Ping --
        // watchdog INI SENDIRI (selalu jalan, tidak ada saklarnya) yang tiap
        // siklus buka channel SSH baru (keepAliveThroughTunnel/
        // verifyTunnelReallyWorks) buat probe reachability. Server "bug host"
        // yang ketat bisa menganggap pola buka-tutup channel identik tiap
        // 20 detik nonstop sebagai penyalahgunaan lalu memutus sesi -- watchdog
        // yang seharusnya MENJAGA tunnel malah jadi PENYEBAB tunnel putus.
        //
        // Fix-nya: pisahkan dua hal yang sebelumnya digabung dalam satu
        // interval yang sama:
        //  1. Cek LOKAL (isSocksPortAlive) -- 100% di dalam device, TIDAK
        //     pernah menyentuh tunnel/channel SSH sama sekali. Ini boleh
        //     sesering apa pun karena tidak menghasilkan trafik/channel ke
        //     server sama sekali. Tetap tiap WATCHDOG_LOCAL_CHECK_INTERVAL_MS.
        //  2. Probe JARAK JAUH lewat tunnel (yang BENERAN buka channel SSH
        //     baru) -- ini yang harus DIJARANGKAN drastis, bukan dihilangkan
        //     total (tetap perlu buat menangkap kasus "tersambung tapi akun
        //     invalid/mati diam-diam"). Sekarang cuma dilakukan sekali per
        //     WATCHDOG_REMOTE_PROBE_INTERVAL_MS (+ jitter lebar & acak, BUKAN
        //     kelipatan genap dari interval lokal) -- dari tadinya ~180x per
        //     jam (tiap 20s) jadi ~6-8x per jam. Ini jauh lebih mirip pola
        //     "keep-alive sewajarnya", bukan health-check bot yang textbook.
        private const val WATCHDOG_LOCAL_CHECK_INTERVAL_MS = 15_000L
        // Dinaikkan 2x lipat dari versi sebelumnya (8 menit -> 16 menit) atas
        // permintaan user, buat cari titik paling jarang yang masih aman --
        // makin jarang channel SSH probe dibuka, makin kecil kemungkinan
        // server menganggapnya pola mencurigakan. Trade-off: deteksi "akun
        // invalid/tunnel mati" jadi lebih lambat (lihat WATCHDOG_REACHABILITY_
        // FAIL_THRESHOLD di bawah, worst-case sekarang ~32-38 menit).
        private const val WATCHDOG_REMOTE_PROBE_INTERVAL_MS = 16 * 60_000L // ~16 menit
        private const val WATCHDOG_REMOTE_PROBE_JITTER_MS = 90_000L // +0..90s acak, biar tidak jadi kelipatan genap
        private const val WATCHDOG_PROBE_TIMEOUT_MS = 5000
        // --- FIX "status tetap Terhubung walau data/WiFi device dimatikan" ---
        // isSocksPortAlive() SAJA TIDAK CUKUP (lihat catatan panjang di
        // startWatchdog): itu cuma ngecek port lokal 127.0.0.1, yang SELALU
        // bisa di-connect walau tidak ada jaringan fisik sama sekali. Watchdog
        // sekarang WAJIB juga probe BENERAN lewat tunnel ke internet
        // (keepAliveThroughTunnel) -- baru dianggap "tunnel mati" kalau probe
        // itu gagal berturut-turut sebanyak ini (bukan cuma sekali, supaya
        // satu paket yang kebetulan telat/drop tidak langsung memicu reconnect).
        // CATATAN: probe jarak jauh sekarang jalan tiap ~16 menit (lihat
        // WATCHDOG_REMOTE_PROBE_INTERVAL_MS), threshold tetap 2x gagal
        // berturut-turut -- waktu deteksi "akun invalid/tunnel benar-benar
        // mati" jadi ~32-38 menit terburuk (naik 2x dari versi sebelumnya,
        // konsekuensi wajar dari interval yang juga dinaikkan 2x).
        private const val WATCHDOG_REACHABILITY_FAIL_THRESHOLD = 2

        // FIX (laporan user, lihat catatan panjang di verifyTunnelReallyWorks):
        // jeda antar percobaan ulang di dalam SATU pemeriksaan verifikasi --
        // BEDA dari jeda antar probe jarak jauh (WATCHDOG_REMOTE_PROBE_INTERVAL_MS).
        private const val VERIFY_RETRY_DELAY_MS = 1500L

        // FIX (sama seperti di atas): dari setiap N probe jarak jauh, cuma 1
        // yang pakai cek "berat" (HTTP asli, verifyTunnelReallyWorks) --
        // sisanya pakai cek ringan (keepAliveThroughTunnel, TCP-only). Dengan
        // probe jarak jauh yang sudah jarang (~8 menit), N=3 di sini berarti
        // heavy check HTTP asli cuma ~2-3x per jam -- jauh lebih jarang dari
        // versi lama yang bisa sampai puluhan kali per jam.
        private const val HEAVY_CHECK_EVERY_N_CYCLES = 3

        // --- "Auto Ping" (Pengaturan Dasar, terpisah dari VPN Setting) ---
        private const val PING_TIMEOUT_MS = 5000

        // --- Keep-alive SUNGGUHAN lewat tunnel (bagian dari "Auto Ping") ---
        // Beda dari pingHost() di bawah (yang protect() -> LANGSUNG ke internet,
        // BYPASS tunnel, jadi TIDAK membuat koneksi SSH/Xray yang sedang aktif
        // "sibuk"). Ini sengaja CONNECT lewat SOCKS5 LOKAL (127.0.0.1:socksPort)
        // supaya paketnya BENERAN lewat channel SSH / outbound Xray yang sudah
        // konek -- itulah yang bikin firewall/NAT operator seluler tidak
        // menganggap koneksi ke server idle lalu memutusnya paksa.
        // Target (host:port) SEKARANG bisa diatur user lewat GeneralSettingsStore
        // .keepAliveTarget -- konstanta di bawah cuma dipakai kalau parsing
        // input user gagal (kosong / format salah / port di luar 1-65535).
        // FIX: dinaikkan dari 5s -> 9s -- 5s terlalu ketat untuk request HTTP
        // ASLI (verifyTunnelReallyWorks) di jaringan seluler yang RTT-nya
        // kadang sudah 1-2 detik sendiri di luar tunnel, ditambah overhead
        // SOCKS5 + enkripsi SSH/Xray di dalamnya.
        private const val KEEP_ALIVE_TIMEOUT_MS = 9000

        // --- FIX "status/app nyangkut selamanya saat teardown" ---
        // Batas maksimal menunggu SATU langkah teardown (tunEngine.stop(),
        // sshTunnelManager.disconnect(), xrayTunnelManager.disconnect()).
        // Lihat [runBlockingWithTimeout] untuk alasan lengkap kenapa ini
        // wajib ada.
        private const val TEARDOWN_STEP_TIMEOUT_MS = 5000L
    }

    /**
     * Jalankan [block] (operasi BLOCKING -- native/JNI atau I/O biasa, TIDAK
     * mendukung pembatalan lewat coroutine cancellation) di thread terpisah,
     * lalu tunggu maksimal [timeoutMs]. Dipakai untuk SEMUA panggilan teardown
     * (tunEngine.stop(), sshTunnelManager.disconnect(),
     * xrayTunnelManager.disconnect()) yang sebelumnya dipanggil TELANJANG
     * tanpa batas waktu.
     *
     * PENTING (bug fix "app stuck total, VPN tidak bisa dimatikan"): panggilan
     * ini bisa datang dari thread MANA PUN -- callback native hev-socks5-
     * tunnel, ConnectionMonitor trilead-ssh2, callback ConnectivityManager
     * saat jaringan device mati (handleTunnelDeath), ATAU shutdownScope
     * (stopVpn manual). xrayTunnelManager.disconnect() KHUSUSNYA memanggil
     * LibXray.invoke("stopXray") -- native call ke runtime Go yang TIDAK
     * PUNYA timeout internal sama sekali. Kalau jaringan device benar-benar
     * mati saat dipanggil, panggilan itu bisa MENGGANTUNG SELAMANYA di thread
     * pemanggil -- itulah sebab asli status "Memutuskan..."/"Terhubung" yang
     * tidak pernah berubah, dan tombol Kontrol Koneksi yang macet permanen.
     *
     * Thread di bawah SENGAJA TIDAK di-interrupt paksa kalau timeout (operasi
     * native/JNI umumnya tidak aman/tidak bisa diinterupsi begitu saja) --
     * dibiarkan jalan sendiri di background (daemon thread, tidak menahan
     * proses hidup) sementara caller SUDAH BEBAS lanjut. Kalaupun runtime
     * di baliknya benar-benar macet total, stopVpn() tetap akan sampai ke
     * stopSelf() dalam waktu terbatas, dan Android pada akhirnya akan
     * membongkar seluruh proses (termasuk native handle yang macet itu).
     */
    private fun runBlockingWithTimeout(label: String, timeoutMs: Long = TEARDOWN_STEP_TIMEOUT_MS, block: () -> Unit) {
        val latch = CountDownLatch(1)
        val thread = Thread({
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Error saat $label", e)
            } finally {
                latch.countDown()
            }
        }, "teardown-$label").apply {
            isDaemon = true
            start()
        }
        val finishedInTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finishedInTime) {
            Log.w(
                TAG,
                "$label tidak selesai dalam ${timeoutMs}ms -- melanjutkan tanpa menunggu " +
                    "(thread '${thread.name}' dibiarkan jalan sendiri di background)"
            )
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    // MTU dipakai di dua tempat (Builder.setMtu() & HevSocks5Engine.start()),
    // diambil dari VpnSettingsStore sekali di startVpn() supaya kedua sisi
    // pasti konsisten walau user ganti nilainya di tengah sesi tunnel aktif.
    private var currentMtu: Int = com.example.tunnelapp.model.VpnSettings.DEFAULT_MTU
    // Diambil dari VpnSettingsStore.autoReconnect sekali di startVpn(),
    // dicek di scheduleReconnectOrGiveUp() -- kalau false, tunnel yang mati
    // sendiri LANGSUNG di-stopVpn() tanpa retry sama sekali (bukan cuma
    // MAX_RECONNECT_ATTEMPTS diset 0, supaya pesan status ke user juga beda:
    // "auto reconnect nonaktif" vs "reconnect otomatis gagal").
    private var currentAutoReconnect: Boolean = true
    // CATATAN: dulu autoPingEnabled/pingIntervalSeconds di-cache ke sini
    // SEKALI di startVpn() -- akibatnya toggle Auto Ping atau ganti interval
    // di Pengaturan SAAT tunnel sudah aktif tidak ngefek sampai
    // disconnect+reconnect. Sekarang startPingLoop() baca GeneralSettingsStore
    // ULANG tiap siklus, jadi perubahan langsung kepakai di siklus berikutnya.
    private var pingJob: kotlinx.coroutines.Job? = null
    // Non-null selama fitur "Keep CPU Awake" (VpnSettingsStore.keepCpuAwake)
    // aktif -- dipegang dari startVpn() sampai stopVpn(), mencegah CPU masuk
    // deep sleep (WAJIB PARTIAL_WAKE_LOCK, bukan varian yang menyalakan layar)
    // supaya tunnel/reconnect otomatis tetap jalan mulus walau layar mati.
    private var wakeLock: PowerManager.WakeLock? = null
    // FIX (dipakai bareng fix race "EADDRINUSE saat connect cepat" di
    // startVpn()): dulu ini `val` -- aman selama asumsi "satu instance
    // Service = satu siklus hidup start->stop" selalu benar (stopVpn()
    // manggil serviceJob.cancel(), lalu stopSelf() memang menghancurkan
    // instance ini, jadi start berikutnya otomatis dapat instance BARU
    // dengan serviceJob baru). TAPI stopSelf() TIDAK instan -- kalau user
    // menekan connect lagi SEBELUM Android benar-benar menghancurkan
    // instance lama, onStartCommand() berikutnya bisa saja mendarat di
    // instance yang SAMA, dengan serviceJob yang SUDAH di-cancel oleh
    // stopVpn() sebelumnya. Coroutine baru yang di-launch lewat scope
    // dengan parent job yang sudah cancelled itu diam-diam gagal jalan.
    // Makanya sekarang `var`, dan startVpn() mengecek+membuat ulang
    // keduanya kalau job lama sudah tidak aktif lagi.
    private var serviceJob = Job()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val sshTunnelManager = SshTunnelManager()
    private val xrayTunnelManager by lazy { XrayTunnelManager(this) }
    private var tunEngine: TunEngine? = null

    // Proxy HTTP lokal opsional (lihat VpnSettings.httpPort) -- hanya
    // non-null selama tunnel aktif DAN httpPort diisi user di VPN Setting.
    private var httpProxyServer: HttpProxyServer? = null

    /** Aman dipanggil berkali-kali (no-op kalau memang tidak sedang jalan). */
    private fun stopHttpProxyServer() {
        httpProxyServer?.stop()
        httpProxyServer = null
    }

    // Config terakhir yang berhasil/sedang dicoba -- dipakai untuk reconnect
    // otomatis tanpa perlu minta izin VPN ke user lagi (TUN interface yang
    // sudah establish() dibiarkan hidup selama proses reconnect).
    private var lastConfig: ServerConfig? = null

    // FIX (permintaan user: DNS device-side bypass JANGAN otomatis nyala
    // sendiri diam-diam -- terlalu "ajaib"/tidak terduga, dan device-side
    // artinya DNS device jadi keluar dari tunnel (trade-off privasi)).
    // Sekarang bypass itu HANYA aktif kalau user ISI SENDIRI DNS1/DNS2 di
    // kartu "VPN Setting" yang SUDAH ADA (VpnSettingsStore) atau di
    // Konfigurasi SSH per-server (ServerConfig.dns1/dns2) -- persis sinyal
    // yang sama dipakai applyDnsServers() utk menentukan apakah DEFAULT_DNS
    // fallback dipakai atau tidak. Diisi ulang tiap kali applyDnsServers()
    // dipanggil (initial connect & hard-reset reconnect), dibaca di
    // establishTunnel() saat connect SSH.
    @Volatile
    private var customDnsConfigured: Boolean = false
    private var reconnectAttempt = 0
    // --- FITUR BARU: fallback otomatis ke akun cadangan ---
    // Kalau akun yang lagi aktif gagal terus (reconnect ringan + reset penuh
    // sudah dicoba semua, lihat scheduleReconnectOrGiveUp), dan user punya
    // akun LAIN tersimpan di ProfileStore (mis. server cadangan), app
    // sekarang otomatis coba akun itu bergantian -- alih-alih cuma
    // mengulang-ulang akun yang sama yang sudah terbukti gagal terus,
    // ATAUPUN nyerah total. Mirip perilaku DarkTunnel/HTTP Custom kalau user
    // menyimpan beberapa server. Diisi sekali di startVpn(), dikonsumsi
    // bergantian di scheduleReconnectOrGiveUp().
    private var fallbackProfiles: List<com.example.tunnelapp.model.SavedProfile> = emptyList()
    private var fallbackIndex = 0
    // FIX (permintaan user): sebelum benar-benar menyerah setelah
    // MAX_RECONNECT_ATTEMPTS kali reconnect "ringan" (yang sengaja
    // mempertahankan TUN interface tetap hidup) gagal semua, coba SATU KALI
    // reset total -- termasuk bongkar & bikin ulang TUN interface dari nol --
    // sebagai upaya terakhir sebelum stopVpn(). Direset ke false tiap kali
    // startVpn() baru ATAU reconnect/hard-reset berhasil, supaya siklus
    // gagal berikutnya tetap dapat satu jatah hard reset lagi.
    private var hardResetAttempted = false
    // true selama proses stop yang memang DIMINTA (user disconnect, atau kita
    // sendiri lagi membongkar tunnel di tengah reconnect) -- dicek di
    // handleTunnelDeath supaya penutupan socket yang kita sengaja tidak
    // disalahartikan sebagai "tunnel mati sendiri".
    @Volatile
    private var stoppingIntentionally = false
    // Mencegah beberapa sinyal kematian (hev engine + SSH monitor + watchdog)
    // yang datang hampir bersamaan memicu reconnect dobel.
    private val handlingDeath = AtomicBoolean(false)
    private var watchdogJob: kotlinx.coroutines.Job? = null

    // --- FIX "data internet dimatikan tapi VPN tetap terhubung" ---
    // Sebelumnya SATU-SATUNYA deteksi "tunnel mati" adalah watchdog yang
    // nge-cek port SOCKS5 LOKAL (127.0.0.1) masih bisa di-connect atau
    // tidak (lihat isSocksPortAlive()) -- itu selalu TRUE walau data
    // seluler/WiFi device dimatikan total, karena itu murni socket lokal
    // di dalam device sendiri, tidak menyentuh jaringan fisik sama sekali.
    // Akibatnya StatusBus.state tetap "Tunnel aktif" tanpa batas walau
    // sebenarnya sudah tidak ada jalur keluar sama sekali.
    // NetworkCallback ini mendaftar ke jaringan FISIK (NOT_VPN, supaya
    // tidak ke-trigger oleh TUN interface app ini sendiri) dan langsung
    // memicu handleTunnelDeath() begitu Android melaporkan jaringan itu
    // hilang -- deteksi instan, bukan menunggu watchdog/keep-alive.
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // FIX (laporan user: "koneksi suka putus sendiri" dibanding app lain
    // dengan metode sama): dulu onLost()/onUnavailable() LANGSUNG memicu
    // handleTunnelDeath() tanpa toleransi sama sekali. Di jaringan seluler,
    // NetworkCallback ini bisa terpicu HANYA KARENA sinyal sempat drop
    // sedetik, pindah tower BTS, atau device sebentar pindah radio
    // WiFi<->data -- yang PADA AKHIRNYA tersambung lagi sendiri dalam
    // hitungan detik tanpa tunnel benar-benar mati. Sebelum ini, kejadian
    // seperti itu langsung membongkar & menyambung ulang SELURUH tunnel
    // (SSH/Xray + TUN), padahal tidak perlu -- itulah rasanya app "suka
    // putus sendiri" walau server & metode sama persis dengan app lain.
    // Sekarang dikasih jeda: begitu onLost/onUnavailable kepanggil, TUNGGU
    // dulu [NETWORK_LOSS_GRACE_MS], baru cek ULANG apakah jaringan fisik
    // (NOT_VPN + INTERNET) BENAR-BENAR masih tidak ada -- kalau saat itu
    // sudah pulih (mis. sudah pindah ke jaringan lain), batalkan, JANGAN
    // putus tunnel sama sekali.
    private var networkLossJob: kotlinx.coroutines.Job? = null
    private val networkLossGraceMs = 6000L

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.w(TAG, "Jaringan fisik device hilang (data seluler/WiFi dimatikan) -- menunggu ${networkLossGraceMs}ms sebelum putus tunnel")
                scheduleNetworkLossCheck("Jaringan device terputus (data/WiFi mati)")
            }

            override fun onUnavailable() {
                Log.w(TAG, "Tidak ada jaringan fisik yang tersedia -- menunggu ${networkLossGraceMs}ms sebelum putus tunnel")
                scheduleNetworkLossCheck("Tidak ada jaringan aktif di device")
            }

            override fun onAvailable(network: Network) {
                // Jaringan fisik (WiFi/data lain) sudah kembali tersedia --
                // batalkan rencana putus tunnel dari onLost/onUnavailable
                // sebelumnya kalau masih menunggu.
                networkLossJob?.cancel()
                networkLossJob = null
            }
        }
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mendaftarkan pemantau jaringan", e)
        }
    }

    /**
     * Dipanggil dari onLost()/onUnavailable(). TIDAK langsung memutus tunnel
     * -- tunggu [networkLossGraceMs], lalu cek ulang lewat
     * [connectivityManager.activeNetwork] apakah jaringan fisik benar-benar
     * masih hilang. Kalau ternyata sudah pulih (atau ganti onAvailable()
     * sempat membatalkan job ini duluan), tidak melakukan apa-apa.
     */
    private fun scheduleNetworkLossCheck(reason: String) {
        networkLossJob?.cancel()
        networkLossJob = serviceScope.launch {
            delay(networkLossGraceMs)
            if (stoppingIntentionally) return@launch
            val active = connectivityManager.activeNetwork
            val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
            val stillHasPhysicalNetwork = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (stillHasPhysicalNetwork) {
                Log.i(TAG, "Jaringan fisik sudah pulih dalam ${networkLossGraceMs}ms, batal putus tunnel")
                return@launch
            }
            Log.w(TAG, "Jaringan fisik tetap hilang setelah ${networkLossGraceMs}ms, putus tunnel")
            handleTunnelDeath(reason)
        }
    }

    private fun unregisterNetworkWatcher() {
        networkLossJob?.cancel()
        networkLossJob = null
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal melepas pemantau jaringan (mungkin sudah tidak terdaftar)", e)
        }
    }

    // --- FIX ANR: scope KHUSUS untuk teardown (tunEngine.stop(), SSH/Xray
    // disconnect(), vpnInterface.close()) ---
    // Semua panggilan itu BLOCKING: HevSocks5Engine.stop() nge-join thread
    // native sampai 2 detik, SshTunnelManager.disconnect() nutup socket SSH
    // (trilead-ssh2), XrayTunnelManager.disconnect() manggil JNI ke runtime Go
    // (libXray). stopVpn() dulu menjalankan semua itu LANGSUNG di badan
    // onStartCommand()/onDestroy() -- keduanya jalan di MAIN THREAD (sama
    // dengan UI Activity, app ini satu proses) -- jadi main thread ke-block
    // sampai semuanya kelar & muncul dialog "isn't responding". Scope ini
    // sengaja TERPISAH dari [serviceScope]/[serviceJob] (yang di-cancel duluan
    // di stopVpn()) supaya teardown-nya sendiri tidak ikut ke-cancel.
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Cegah stopVpn() dobel (bisa kepanggil dari onStartCommand, onDestroy,
    // DAN onRevoke hampir bersamaan).
    private val stopping = AtomicBoolean(false)

    // FIX (race "EADDRINUSE saat user buru-buru connect lagi"): stopVpn()
    // membongkar SSH/SOCKS5/TUN lama secara ASYNC di shutdownScope, sementara
    // vpnInterface (penanda "boleh start baru") sudah di-null-kan SINKRON di
    // awal stopVpn(). Kalau startVpn() berikutnya dipanggil di antara dua
    // momen itu, dia bisa mulai bind() SOCKS5 baru SEBELUM socket lama
    // benar-benar ditutup di background -- bentrok lagi persis seperti bug
    // utama di atas, tapi dari sisi "stop lalu connect cepat" alih-alih
    // "reconnect otomatis". Job ini dipegang supaya startVpn() bisa
    // menunggunya (dengan batas waktu) sebelum lanjut, bukan cuma
    // mengandalkan vpnInterface == null.
    @Volatile
    private var shutdownJob: Job? = null

    // FIX (celah baru akibat startVpn() sekarang async menunggu shutdownJob):
    // vpnInterface baru terisi SETELAH builder.establish() selesai di dalam
    // coroutine -- ada jeda singkat sebelum itu. Tanpa flag ini, user yang
    // menekan tombol Connect dua kali sangat cepat bisa lolos dari pengecekan
    // "vpnInterface != null" dua-duanya dan memicu dua proses connect
    // paralel. Flag ini di-set begitu startVpn() diterima, dan direset kalau
    // gagal di tahap TUN (supaya user bisa coba lagi).
    private val startInProgress = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val modeStr = intent.getStringExtra(EXTRA_MODE)
                val mode = try {
                    com.example.tunnelapp.model.ConnectionMode.valueOf(
                        modeStr ?: com.example.tunnelapp.model.ConnectionMode.SSH.name
                    )
                } catch (e: IllegalArgumentException) {
                    com.example.tunnelapp.model.ConnectionMode.SSH
                }
                val proxyPortExtra = intent.getIntExtra(EXTRA_PROXY_PORT, -1)
                val config = ServerConfig(
                    host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PORT, 22),
                    username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASSWORD),
                    mode = mode,
                    sslSni = intent.getStringExtra(EXTRA_SSL_SNI),
                    payload = intent.getStringExtra(EXTRA_PAYLOAD),
                    proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST),
                    proxyPort = if (proxyPortExtra > 0) proxyPortExtra else null,
                    tlsVersion = intent.getStringExtra(EXTRA_TLS_VERSION),
                    useWebSocket = intent.getBooleanExtra(EXTRA_WEBSOCKET_ENABLED, false),
                    wsPath = intent.getStringExtra(EXTRA_WS_PATH),
                    proxyRawMode = intent.getBooleanExtra(EXTRA_PROXY_RAW_MODE, false),
                    xrayLink = intent.getStringExtra(EXTRA_XRAY_LINK),
                    customHeaders = intent.getStringExtra(EXTRA_CUSTOM_HEADERS),
                    ignoreCertErrors = intent.getBooleanExtra(EXTRA_IGNORE_CERT_ERRORS, false),
                    dns1 = intent.getStringExtra(EXTRA_DNS1),
                    dns2 = intent.getStringExtra(EXTRA_DNS2)
                )
                startVpn(config, intent.getStringExtra(EXTRA_PROFILE_ID))
                return START_STICKY
            }
            else -> {
                // FIX (laporan user): notifikasi "SSH tersambung..." nyangkut
                // di status bar padahal Dashboard sudah bilang "Belum
                // tersambung". Ini terjadi kalau Android membunuh proses app
                // di background (manajemen baterai agresif ala MIUI/ColorOS/
                // FuntouchOS) lalu membangunkan ulang Service ini otomatis
                // karena onStartCommand() sebelumnya return START_STICKY --
                // pembangunan ulang itu SELALU dengan intent NULL (tidak
                // pernah bawa action ACTION_CONNECT lagi), jadi pasti mendarat
                // di branch ini. Proses baru = StatusBus baru (default
                // "Belum tersambung", benar apa adanya karena vpnInterface di
                // instance baru ini juga pasti null, tunnel LAMA sudah mati
                // bersama proses lama) -- tapi notifikasi lama yang di-set
                // ongoing=true tidak pernah otomatis hilang, jadi tetap
                // menampilkan status basi dari sebelum proses mati.
                // Tanpa START_STICKY di sini pula, supaya Android tidak terus
                // membangunkan ulang Service kosong berulang-ulang.
                if (vpnInterface == null) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Warna durasi "(...ms)" di log ping/keep-alive (permintaan user):
     * 1-80ms dianggap CEPAT (biru, ping_ms_fast), 85ms ke atas dianggap
     * LAMBAT (merah, ping_ms_slow). 81-84ms (celah kecil di antara ambang
     * yang diminta user) ikut masuk kategori lambat supaya tidak ada nilai
     * yang tidak kebagian warna.
     */
    private fun pingMsColorHex(ms: Long): String {
        val colorRes = if (ms in 1..80) R.color.ping_ms_fast else R.color.ping_ms_slow
        return String.format(
            "#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(this, colorRes)
        )
    }

    /**
     * Susun baris banner "Running on <manufacturer> <model> (<device>),
     * Android <release> (<build id>) API <sdk>. Version <versionName>
     * Build <versionCode>." -- ditampilkan sebagai baris pertama halaman
     * Log tiap kali mulai connect (permintaan user, gaya DarkTunnel).
     * versionName/versionCode diambil dari PackageManager (pola yang sama
     * dipakai SettingsActivity.appVersion) supaya selalu sinkron dengan
     * gradle, tidak perlu di-hardcode di sini.
     */
    private fun deviceInfoBanner(): String {
        val pkgInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val versionName = pkgInfo?.versionName ?: "-"
        val versionCode = if (pkgInfo != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
        } else 0L
        return "Running on ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), " +
            "Android ${Build.VERSION.RELEASE} (${Build.ID}) API ${Build.VERSION.SDK_INT}. " +
            "Version $versionName Build $versionCode."
    }

    private fun startVpn(rawConfig: ServerConfig, profileId: String? = null) {
        if (vpnInterface != null) {
            Log.w(TAG, "VPN sudah berjalan, abaikan permintaan start kedua")
            return
        }
        if (!startInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Permintaan connect sudah sedang diproses, abaikan permintaan kedua")
            return
        }

        val vpnSettings = VpnSettingsStore.load(this)
        // Global override "SOCKS5 Port" (VPN Setting) MENIMPA socksPort
        // per-profil kalau diisi (>0) -- sama pola dengan override DNS
        // (applyDnsServers). Diterapkan di sini SEBELUM lastConfig
        // ditetapkan supaya seluruh alur (establishTunnel, reconnect, hard
        // reset) konsisten memakai port yang sama.
        val config = (if (vpnSettings.socksPort > 0) {
            rawConfig.copy(socksPort = vpnSettings.socksPort)
        } else {
            rawConfig
        }).copy(udpgwPort = vpnSettings.udpgwPort) // udpgw SELALU global (VPN Setting), tidak ada per-profil -- lihat ServerConfig.udpgwPort

        lastConfig = config
        // Susun daftar akun cadangan SEKALI di sini (bukan tiap kali dibutuhkan
        // di scheduleReconnectOrGiveUp) -- akun aktif (profileId) dikecualikan
        // supaya tidak "fallback" ke diri sendiri. Kalau user cuma punya satu
        // akun tersimpan (atau profileId tidak dikirim, mis. dari kode lama),
        // daftar ini kosong dan perilaku persis seperti sebelumnya (retry akun
        // yang sama terus).
        fallbackProfiles = com.example.tunnelapp.model.ProfileStore.getAll(this)
            .filter { profileId == null || it.id != profileId }
        fallbackIndex = 0
        reconnectAttempt = 0
        hardResetAttempted = false
        stoppingIntentionally = false
        handlingDeath.set(false)
        stopping.set(false)
        // Lihat catatan di deklarasi serviceJob/serviceScope: kalau instance
        // Service ini sempat menjalani stopVpn() sebelumnya (job lama sudah
        // cancelled), buat job+scope baru di sini supaya serviceScope.launch()
        // di bawah maupun di establishTunnel()/scheduleReconnectOrGiveUp()
        // benar-benar jalan, bukan diam-diam ter-drop.
        if (!serviceJob.isActive) {
            serviceJob = Job()
            serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
        }

        currentMtu = vpnSettings.mtu
        currentAutoReconnect = vpnSettings.autoReconnect
        acquireWakeLockIfNeeded(vpnSettings.keepCpuAwake)

        startForeground(NOTIFICATION_ID, buildNotification("Menghubungkan..."))
        StatusBus.clearLog()
        // Banner info perangkat & versi app (permintaan user, gaya DarkTunnel):
        // baris PALING PERTAMA yang tampil di halaman Log tiap kali mulai
        // connect, mis. "Running on Infinix Infinix X678B (Infinix-X678B),
        // Android 14 (UP1A.231005.007) API 34. Version 1.0.26 Build 32."
        StatusBus.log(deviceInfoBanner())
        StatusBus.initSteps(buildStepsFor(config))
        StatusBus.state.value = "Membuat antarmuka VPN (TUN)..."

        // FIX (race di atas): kalau masih ada shutdownJob dari stopVpn()
        // sebelumnya yang belum kelar, tunggu dulu (maks TEARDOWN_STEP_TIMEOUT_MS)
        // di coroutine -- BUKAN blocking main thread -- sebelum bikin TUN
        // interface baru & connect. Kalau memang tidak ada shutdown yang
        // sedang berjalan, pendingShutdown null/sudah selesai dan bagian ini
        // langsung lanjut tanpa delay sama sekali.
        val pendingShutdown = shutdownJob
        serviceScope.launch {
            if (pendingShutdown != null && pendingShutdown.isActive) {
                StatusBus.log("Menunggu proses disconnect sebelumnya selesai...")
                withTimeoutOrNull(TEARDOWN_STEP_TIMEOUT_MS) { pendingShutdown.join() }
            }

            StatusBus.start(StepId.TUN)

            val builder = Builder()
                .setSession("SuryaVPN")
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                // REVERT (laporan user: server SSH yang dipakai TIDAK punya rute
                // IPv6 sama sekali -- SEMUA percobaan CONNECT SOCKS5 ke tujuan
                // IPv6 selalu gagal "Could not open channel (state:4)", terus
                // diulang oleh hev-socks5-tunnel/browser tanpa henti, bikin log
                // penuh & internet berasa lambat/macet). addRoute("::", 0)
                // SEHARUSNYA cuma "menahan lalu buang bersih" trafik IPv6 di
                // dalam TUN (lihat versi lama fungsi ini) -- tapi pada
                // praktiknya hev-socks5-tunnel TETAP menangkap paket IPv6 itu
                // & mengirimkannya sebagai request SOCKS5 CONNECT ber-ATYP
                // IPv6 ke Socks5Server, yang lalu diteruskan ke server SSH --
                // bukan didrop diam-diam seperti yang diharapkan. Sekarang
                // IPv6 TIDAK di-route ke TUN sama sekali -- device akan
                // memakai jalur asli (di luar tunnel) untuk trafik IPv6,
                // sama seperti sebelum VPN dinyalakan.
                // CATATAN (trade-off, bukan bug): kalau jaringan asli device
                // punya IPv6 DAN jalur asli itu sendiri yang dibatasi/diblokir
                // operator (alasan awal orang pakai tunnel ini), app yang
                // mencoba IPv6 dulu (Happy Eyeballs) bisa tetap terasa lambat
                // sebelum fallback ke IPv4 asli device -- tapi ini di LUAR
                // tunnel, tunnel/log SOCKS5 tidak lagi kebanjiran percobaan
                // yang pasti gagal. Kalau nanti server SSH lain yang dipakai
                // TERNYATA punya rute IPv6, addRoute("::", 0) bisa diaktifkan
                // lagi khusus untuk server itu.
                .setMtu(currentMtu)
            applyDnsServers(builder, config, vpnSettings)

            vpnInterface = try {
                builder.establish()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal membuat TUN interface", e)
                StatusBus.fail(StepId.TUN, e.message ?: e.javaClass.simpleName)
                StatusBus.skipRemainingPending()
                StatusBus.state.value = "Gagal membuat TUN interface: ${e.message}"
                startInProgress.set(false)
                stopSelf()
                return@launch
            }
            StatusBus.success(StepId.TUN)

            Log.i(TAG, "TUN interface berhasil dibuat")
            registerNetworkWatcher()
            StatusBus.state.value = "Menghubungkan ke ${config.host}:${config.port}..."

            establishTunnel(config, isReconnect = false)
        }
    }

    /**
     * Pasang DNS ke [builder] kalau diisi & valid (harus literal IP,
     * [VpnService.Builder.addDnsServer] melempar [IllegalArgumentException]
     * untuk hostname/string sembarangan -- ditangkap di sini supaya salah
     * ketik DNS tidak menggagalkan seluruh pembuatan TUN interface, cukup
     * diabaikan + dicatat ke log).
     *
     * Prioritas sumber DNS: DNS1/DNS2 di kartu "VPN Setting"
     * ([VpnSettingsStore], global) MENIMPA DNS per-server
     * ([ServerConfig.dns1]/[ServerConfig.dns2], dari Konfigurasi SSH) kalau
     * salah satunya diisi.
     *
     * PERCOBAAN (atas permintaan user, SUDAH DIPERINGATKAN risikonya):
     * fallback ke [DEFAULT_DNS] DIHAPUS -- kalau DNS1/DNS2 kosong semua,
     * TIDAK ADA addDnsServer() dipanggil sama sekali. Efek yang paling
     * mungkin: resolusi domain gagal total buat user yang tidak isi
     * DNS1/DNS2 manual, karena addRoute("0.0.0.0", 0) tetap menangkap
     * SEMUA trafik ke TUN termasuk DNS bawaan operator/wifi (yang sering
     * berupa IP privat, tidak bisa dicapai server SSH). Kalau efeknya
     * memang seburuk itu, tinggal balikin baris addDnsServer(DEFAULT_DNS)
     * di bawah (kodenya dipertahankan dlm komentar, bukan dihapus total).
     */
    private fun applyDnsServers(builder: Builder, config: ServerConfig, vpnSettings: com.example.tunnelapp.model.VpnSettings) {
        val useGlobalOverride = vpnSettings.dns1.isNotBlank() || vpnSettings.dns2.isNotBlank()
        val dnsCandidates = if (useGlobalOverride) {
            listOf("DNS1 (VPN Setting)" to vpnSettings.dns1, "DNS2 (VPN Setting)" to vpnSettings.dns2)
        } else {
            listOf("DNS1" to config.dns1, "DNS2" to config.dns2)
        }

        var addedAny = false
        for ((label, dns) in dnsCandidates) {
            val value = dns?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            try {
                builder.addDnsServer(value)
                addedAny = true
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "$label \"$value\" bukan alamat IP valid, diabaikan", e)
                StatusBus.log("$label \"$value\" bukan alamat IP valid -- diabaikan")
            }
        }
        if (!addedAny) {
            // builder.addDnsServer(DEFAULT_DNS)  // DIMATIKAN sesuai permintaan user
            StatusBus.log("[DNS] DNS1/DNS2 kosong -- TIDAK ada DNS default dipasang ke TUN (fallback $DEFAULT_DNS dimatikan)")
        }

        // addedAny == true berarti user MEMANG mengisi DNS1/DNS2 sendiri
        // (bukan fallback DEFAULT_DNS diam-diam) -- inilah sinyal "DNS
        // diisi manual di Pengaturan" yang dipakai establishTunnel() utk
        // menyalakan/mematikan device-side DNS bypass di Socks5Server.
        customDnsConfigured = addedAny
    }

    /**
     * Fitur "Keep CPU Awake" (kartu VPN Setting) -- pegang
     * PARTIAL_WAKE_LOCK selama tunnel aktif supaya CPU tidak masuk deep
     * sleep dan koneksi/reconnect otomatis tetap jalan walau layar device
     * mati. Aman dipanggil berulang: kalau [enabled] false atau wakeLock
     * sudah dipegang, tidak melakukan apa-apa.
     */
    private fun acquireWakeLockIfNeeded(enabled: Boolean) {
        if (!enabled) return
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Keep CPU Awake aktif: wake lock dipegang")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memegang wake lock (Keep CPU Awake)", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal melepas wake lock", e)
        }
        wakeLock = null
    }

    /**
     * Sambungkan SSH/Xray + nyalakan [TunEngine] di atas TUN interface yang
     * SUDAH ada ([vpnInterface]). Dipisah dari [startVpn] supaya bisa dipakai
     * ulang untuk reconnect otomatis TANPA perlu builder.establish() lagi
     * (yang berarti tanpa perlu minta izin VPN ke user lagi).
     */
    /**
     * FIX BUG "batal connect tapi tetap 'Connected' / kelihatan reconnect
     * sendiri": establishTunnel() ini isinya panggilan BLOCKING berurutan
     * (SSH/Xray connect -> startTunEngine -> verifyTunnelReallyWorks) tanpa
     * satu pun suspension point di antaranya. serviceJob.cancel() yang
     * dipanggil stopVpn() TIDAK BISA menghentikan coroutine yang sedang di
     * tengah menjalankan kode sinkron seperti itu -- cancellation Kotlin
     * coroutine sifatnya kooperatif, cuma diperiksa di titik-titik tertentu.
     * Tanpa pengecekan manual, coroutine lama ini tetap jalan sampai
     * selesai SETELAH stopVpn()/shutdownScope sudah menutup semuanya --
     * lalu balik menimpa tunEngine, StatusBus, dan menyalakan
     * watchdog/ping loop baru, persis seperti "reconnect sendiri" padahal
     * itu proses connect pertama yang tidak pernah benar-benar dibatalkan.
     * checkStoppedMidway() dipanggil di tiap titik transisi tahap di bawah
     * -- kalau true, method ini SENDIRI yang membongkar apa pun yang baru
     * saja dibuat (bukan cuma `return`), supaya tidak ada tunnel yang
     * bocor tetap hidup walau UI sudah bilang "Terputus".
     */
    private fun checkStoppedMidway(engineJustStarted: TunEngine?): Boolean {
        if (!stoppingIntentionally) return false
        Log.w(TAG, "Stop diminta di tengah proses connect -- membatalkan & membongkar hasil parsial")
        stopHttpProxyServer()
        if (engineJustStarted != null && tunEngine === engineJustStarted) {
            tunEngine = null
        }
        if (engineJustStarted != null) {
            runBlockingWithTimeout("tunEngine.stop() (dibatalkan di tengah connect)") { engineJustStarted.stop() }
        }
        runBlockingWithTimeout("sshTunnelManager.disconnect() (dibatalkan di tengah connect)") {
            sshTunnelManager.disconnect()
        }
        runBlockingWithTimeout("xrayTunnelManager.disconnect() (dibatalkan di tengah connect)") {
            xrayTunnelManager.disconnect()
        }
        return true
    }

    private fun establishTunnel(config: ServerConfig, isReconnect: Boolean) {
        serviceScope.launch {
            try {
                if (isReconnect) {
                    StatusBus.initSteps(buildStepsFor(config))
                    StatusBus.start(StepId.TUN)
                    StatusBus.success(StepId.TUN)
                }

                if (checkStoppedMidway(null)) return@launch

                // "Performance Mode" -- global (VPN Setting), sama seperti httpPort
                // di bawah: di-load ulang di sini (bukan dari startVpn()) supaya
                // toggle yang diubah user SESUDAH tunnel sempat konek tapi
                // SEBELUM reconnect berikutnya tetap ikut kepakai (establishTunnel
                // ini juga dipanggil ulang tiap reconnect, lihat isReconnect).
                val performanceMode = VpnSettingsStore.load(this@MyVpnService).performanceMode

                if (config.usesXray()) {
                    // libXray protect socket beroperasi di level fd mentah (Go/gomobile),
                    // bukan java.net.Socket seperti jalur SSH -- pakai overload
                    // VpnService.protect(fd: Int) langsung.
                    xrayTunnelManager.connect(config) { fd -> protect(fd) }
                    StatusBus.state.value = "Xray-core tersambung. Mengaktifkan tunnel..."
                    updateNotification("Xray-core aktif")
                } else {
                    sshTunnelManager.connect(
                        config,
                        protect = { socket -> protect(socket) },
                        // FIX (permintaan user): device-side DNS bypass di
                        // Socks5Server HANYA aktif kalau user mengisi
                        // sendiri DNS1/DNS2 di Pengaturan (lihat
                        // customDnsConfigured/applyDnsServers). Kalau tidak
                        // diisi (pakai DEFAULT_DNS), lambda-nya sengaja
                        // dibuat SELALU return false -- Socks5Server bakal
                        // langsung fallback ke relay SSH lama tanpa pernah
                        // benar-benar kirim trafik di luar tunnel, PERSIS
                        // perilaku sebelum fitur bypass ini ada.
                        protectDatagram = if (customDnsConfigured) {
                            { datagramSocket -> protect(datagramSocket) }
                        } else {
                            null
                        },
                        performanceMode = performanceMode,
                        onUnexpectedDisconnect = { reason -> handleTunnelDeath("SSH: $reason") }
                    )
                    StatusBus.state.value = "SSH tersambung. Mengaktifkan tunnel..."
                    updateNotification("SSH tersambung ke ${config.host}")
                }

                if (checkStoppedMidway(null)) return@launch

                // FIX "status sukses vs koneksi nyata beda": step ini dulu
                // ditandai success() SEKETIKA setelah startTunEngine() -- padahal
                // startTunEngine() cuma nge-launch thread native (hev-socks5-tunnel)
                // yang jalan async & blocking di thread-nya sendiri, TIDAK pernah
                // ditunggu sampai benar-benar siap. Akibatnya kartu "Tahapan
                // Koneksi" + log "Connected" sudah tampak 100% hijau/sukses padahal
                // trafik device belum tentu bisa lewat sama sekali. StepId.TUNNEL_ACTIVE
                // sekarang cuma START (spinner) di sini -- success()/fail()-nya
                // dipindah ke SETELAH verifyTunnelReallyWorks() di bawah, supaya
                // step ini baru hijau kalau memang sudah terbukti ada trafik nyata
                // yang balik lewat tunnel, bukan asumsi optimistis.
                StatusBus.start(StepId.TUNNEL_ACTIVE)
                startTunEngine(config)
                if (checkStoppedMidway(tunEngine)) return@launch

                // --- Verifikasi tunnel BENERAN tembus ke internet DAN akun/
                // kredensial-nya masih valid ---
                // FIX (laporan user): "internet nyala lagi, VPN langsung
                // 'terhubung' tanpa ngecek akun valid atau tidak". Sebelumnya
                // baris ini pakai keepAliveThroughTunnel() -- itu CUMA
                // mengecek balasan handshake SOCKS5 CONNECT dari Xray-core
                // LOKAL, dan balasan itu dikirim begitu TCP OUTBOUND ke server
                // remote berhasil dibuka -- BUKAN setelah server VMess/VLESS/
                // Trojan remote benar-benar memvalidasi kredensial (UUID/
                // password), karena validasi itu terjadi DI DALAM payload
                // terenkripsi yang tidak pernah dibalas lewat ack terpisah di
                // level SOCKS5. Akibatnya: akun expired/invalid pun tetap
                // dianggap "reachable" selama server proxy remote-nya sendiri
                // masih hidup -- inilah yang bikin reconnect otomatis
                // "berhasil" walau sebenarnya akun sudah tidak berlaku.
                // verifyTunnelReallyWorks() di bawah BENERAN mengirim &
                // menunggu balasan HTTP asli lewat tunnel -- kalau akun
                // invalid, server remote diam-diam DROP payloadnya (tidak ada
                // balasan sama sekali), dan itu baru ketahuan dari sini.
                val reachable = verifyTunnelReallyWorks(config.socksPort)
                if (!reachable) {
                    val reason = "Tunnel nyala tapi tidak ada trafik nyata yang balik lewat tunnel " +
                        "-- kemungkinan jaringan device mati, ATAU akun/kredensial server " +
                        "sudah tidak valid/expired"
                    // Tandai step ini ERROR secara eksplisit -- tanpa ini, step yang
                    // masih RUNNING (spinner) bakal nyangkut selamanya di layar Log,
                    // karena skipRemainingPending() di catch block cuma menyentuh
                    // step yang masih PENDING, bukan yang RUNNING.
                    StatusBus.fail(StepId.TUNNEL_ACTIVE, reason)
                    throw IllegalStateException(reason)
                }
                StatusBus.success(StepId.TUNNEL_ACTIVE)
                // FIX (laporan user): baris terakhir yang pernah masuk ke Log
                // terminal sebelumnya cuma "Mengaktifkan tunnel ke seluruh
                // trafik device..." (dari SshTunnelManager) -- tidak pernah ada
                // baris konfirmasi susulan setelah verifyTunnelReallyWorks()
                // benar-benar sukses, jadi Log terlihat "berhenti di situ"
                // padahal sebenarnya sudah terverifikasi aktif. Tambahkan event
                // nyata di sini supaya Log merefleksikan status sebenarnya.
                StatusBus.log("Tunnel aktif — verifikasi trafik nyata berhasil, semua koneksi device lewat tunnel.")
                if (checkStoppedMidway(tunEngine)) return@launch

                // Nyalakan proxy HTTP lokal tambahan kalau diisi user (VPN
                // Setting > HTTP Port) -- SOCKS5 lokal (config.socksPort)
                // sudah pasti aktif di titik ini (verifyTunnelReallyWorks
                // di atas sudah membuktikannya).
                val vpnSettingsForHttpProxy = VpnSettingsStore.load(this@MyVpnService)
                if (vpnSettingsForHttpProxy.httpPort > 0) {
                    stopHttpProxyServer()
                    try {
                        httpProxyServer = HttpProxyServer().apply {
                            start(vpnSettingsForHttpProxy.httpPort, config.socksPort)
                        }
                        StatusBus.log("Proxy HTTP lokal aktif di 127.0.0.1:${vpnSettingsForHttpProxy.httpPort}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal menyalakan proxy HTTP lokal", e)
                        StatusBus.log("Proxy HTTP lokal GAGAL dinyalakan di port ${vpnSettingsForHttpProxy.httpPort}: ${e.message}")
                        httpProxyServer = null
                    }
                }

                // Reconnect (kalau ada) sukses -- reset hitungan percobaan &
                // nyalakan ulang watchdog buat siklus berikutnya.
                reconnectAttempt = 0
                hardResetAttempted = false
                handlingDeath.set(false)
                startWatchdog(config)
                startPingLoop(config)

                StatusBus.state.value = "Tunnel aktif — semua trafik device lewat SSH"
                updateNotification("Tunnel aktif (${config.host})")
            } catch (e: Exception) {
                // Kalau exception ini muncul GARA-GARA kita sendiri sedang
                // membatalkan (mis. verifyTunnelReallyWorks gagal karena
                // sshTunnelManager.disconnect() dari checkStoppedMidway/stopVpn
                // baru saja menutup socketnya), jangan lanjut ke
                // scheduleReconnectOrGiveUp()/stopVpn() lagi -- stopVpn() asli
                // sudah/sedang menangani teardown & status "Terputus".
                if (stoppingIntentionally) return@launch
                Log.e(TAG, "Gagal menyalakan tunnel", e)
                StatusBus.skipRemainingPending()
                // Pakai pesan dari tahap yang benar-benar gagal (lebih akurat)
                // kalau ada, baru fallback ke pesan exception generik.
                val reason = StatusBus.firstErrorDetail() ?: e.message ?: e.javaClass.simpleName

                // FIX BUG "banner reconnect otomatis nongol duluan / state korup
                // & crash saat connect ulang": sshTunnelManager.disconnect() di
                // bawah memanggil connection.close(), yang SINKRON memicu
                // ConnectionMonitor.connectionLost() -> onUnexpectedDisconnect()
                // -> handleTunnelDeath() -- padahal ini cuma cleanup attempt yang
                // gagal, BUKAN tunnel mati sendiri. Tanpa guard ini,
                // handleTunnelDeath() ikut jalan DI TENGAH cleanup kita sendiri
                // (reentrant), menjadwalkan reconnect otomatis yang balapan
                // dengan scheduleReconnectOrGiveUp()/stopVpn() yang beberapa
                // baris di bawah -- itulah sumber status/log yang "loncat"
                // (reconnect keluar duluan sebelum status final), dan sisa
                // thread teardown yang masih menulis ke connection/socks5Server/
                // tunEngine belakangan bisa menabrak siklus connect berikutnya.
                // Klaim gerbang handlingDeath DI SINI (sebelum disconnect()
                // dipanggil) supaya connectionLost() yang datang dari close()
                // kita sendiri otomatis diabaikan (lihat guard di awal
                // handleTunnelDeath()). Direset balik ke false oleh
                // scheduleReconnectOrGiveUp() (sebelum retry) atau tetap true
                // sampai stopVpn() kalau memang menyerah -- konsisten dengan
                // pola reset yang sudah dipakai di tempat lain.
                handlingDeath.set(true)

                // FIX ARSITEKTUR EADDRINUSE (dulu "FIX BUG UTAMA bind failed:
                // EADDRINUSE di percobaan reconnect berikutnya" -- ditambal
                // dengan disconnect() penuh di sini; sekarang ditutup di akar
                // masalahnya lewat SshTunnelManager.disconnectForReconnect()):
                // kalau attempt ini adalah RECONNECT yang gagal, SOCKS5 lokal
                // SENGAJA dibiarkan hidup (port tidak dilepas) -- attempt
                // berikutnya cuma perlu Connection SSH baru, tidak pernah
                // bind() ulang port sama sekali, jadi race EADDRINUSE yang
                // lama tidak mungkin terjadi lagi secara struktural. Kalau ini
                // justru percobaan connect AWAL (bukan reconnect) yang gagal,
                // belum ada sesi yang perlu dipertahankan -- aman dibongkar
                // total lewat disconnect() biasa. xrayTunnelManager tetap
                // pakai disconnect() penuh di kedua kasus (jalurnya beda,
                // tidak punya local proxy persisten seperti SshTunnelManager).
                stopHttpProxyServer()
                if (isReconnect) {
                    runBlockingWithTimeout("sshTunnelManager.disconnectForReconnect() (cleanup reconnect gagal)") {
                        sshTunnelManager.disconnectForReconnect()
                    }
                } else {
                    runBlockingWithTimeout("sshTunnelManager.disconnect() (cleanup gagal connect awal)") {
                        sshTunnelManager.disconnect()
                    }
                }
                runBlockingWithTimeout("xrayTunnelManager.disconnect() (cleanup gagal connect)") {
                    xrayTunnelManager.disconnect()
                }

                if (isReconnect) {
                    scheduleReconnectOrGiveUp("Reconnect gagal: $reason")
                } else {
                    // FIX (laporan user): sebelumnya kegagalan connect awal
                    // (bukan reconnect) cuma nge-update StatusBus.state, jadi
                    // terminal Log berhenti begitu saja di baris terakhir
                    // sebelum gagal -- tidak pernah ada baris "Gagal: ..."
                    // yang benar-benar tertulis ke liveLog. Sama seperti fix
                    // sebelumnya untuk jalur sukses, di sini juga perlu event
                    // nyata ke Log supaya konsisten dengan status akhirnya.
                    StatusBus.log("Gagal: $reason")
                    StatusBus.state.value = "Gagal: $reason"
                    stopVpn()
                }
            }
        }
    }

    /**
     * Dipanggil dari SINYAL APAPUN yang menandakan tunnel mati sendiri (bukan
     * diminta stop): thread hev-socks5-tunnel exit tak terduga
     * ([HevSocks5Engine.onUnexpectedStop]), koneksi SSH putus
     * ([com.trilead.ssh2.ConnectionMonitor]), atau watchdog gagal probe SOCKS5
     * lokal ([startWatchdog]). Bisa dipanggil dari thread mana pun -- karena
     * itu semua state yang dibaca/ditulis di sini WAJIB aman dipanggil
     * berulang (idempotent), makanya pakai [handlingDeath] sebagai gerbang.
     */
    private fun handleTunnelDeath(reason: String) {
        if (stoppingIntentionally) return
        if (!handlingDeath.compareAndSet(false, true)) return // sudah lagi ditangani sinyal lain

        val config = lastConfig
        if (config == null) {
            stopVpn()
            return
        }

        Log.w(TAG, "Tunnel mati sendiri: $reason")
        watchdogJob?.cancel()
        pingJob?.cancel()
        pingJob = null

        // Bongkar SSH/Xray + tun engine yang mati itu -- TUN interface
        // (vpnInterface) SENGAJA DIBIARKAN HIDUP supaya reconnect tidak perlu
        // builder.establish() ulang (= tidak perlu izin VPN ulang dari user).
        //
        // FIX "status nyangkut Terhubung walau internet mati / app freeze":
        // handleTunnelDeath() ini bisa dipanggil dari thread APA SAJA (native
        // hev-socks5-tunnel, ConnectionMonitor SSH, ATAU callback
        // ConnectivityManager saat jaringan device hilang) -- ketiga panggilan
        // di bawah dulu TELANJANG tanpa batas waktu, padahal
        // xrayTunnelManager.disconnect() bisa menggantung selamanya kalau
        // jaringan device benar-benar mati (lihat catatan panjang di
        // [runBlockingWithTimeout]). Sekarang dibungkus timeout supaya thread
        // pemanggil PASTI bebas lagi dalam waktu terbatas, dan
        // scheduleReconnectOrGiveUp() di bawah ini SELALU sempat terpanggil.
        val engine = tunEngine
        tunEngine = null
        stopHttpProxyServer()
        if (engine != null) {
            runBlockingWithTimeout("tunEngine.stop()") { engine.stop() }
        }
        // FIX ARSITEKTUR EADDRINUSE: pakai disconnectForReconnect() (bukan
        // disconnect() penuh) -- SOCKS5 lokal SENGAJA dibiarkan hidup & tetap
        // mendengarkan di port yang sama, cuma referensi Connection SSH yang
        // matinya dilepas. Reconnect berikutnya (establishTunnel isReconnect
        // = true) jadi tidak pernah perlu bind() ulang port sama sekali.
        // Lihat catatan arsitektur lengkap di SshTunnelManager & Socks5Server.
        runBlockingWithTimeout("sshTunnelManager.disconnectForReconnect()") { sshTunnelManager.disconnectForReconnect() }
        runBlockingWithTimeout("xrayTunnelManager.disconnect()") { xrayTunnelManager.disconnect() }

        scheduleReconnectOrGiveUp(reason)
    }

    private fun scheduleReconnectOrGiveUp(reason: String) {
        val config = lastConfig
        if (config == null) {
            stopVpn()
            return
        }

        if (!currentAutoReconnect) {
            StatusBus.log("Tunnel terputus ($reason) -- auto reconnect nonaktif (VPN Setting), tidak mencoba nyambung ulang")
            StatusBus.state.value = "Terputus: tunnel mati ($reason), auto reconnect nonaktif"
            stopVpn()
            return
        }

        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            if (!hardResetAttempted) {
                hardResetAndRetry(config, reason)
                return
            }
            // FIX (stabilitas, laporan user): titik ini dulu = stopVpn()
            // permanen, user harus buka app & pencet Connect manual lagi.
            // Sekarang siklus reconnect diulang lagi dari awal (BUKAN
            // stopVpn()) -- lihat catatan RECONNECT_BACKOFF_CAP_MS di atas.
            //
            // FITUR BARU: SEBELUM sekadar mengulang config yang sama (yang
            // sudah terbukti gagal terus), coba dulu akun cadangan lain yang
            // tersimpan di ProfileStore satu per satu (round-robin lewat
            // fallbackIndex) -- baru kalau semua akun cadangan juga sudah
            // dicoba habis (atau memang tidak ada sama sekali), balik lagi
            // mengulang config semula seperti biasa.
            val nextConfig = pickNextFallbackConfig()
            if (nextConfig != null) {
                StatusBus.log("Akun ini gagal terus ($reason) -- beralih coba akun cadangan lain yang tersimpan")
                lastConfig = nextConfig
            } else {
                StatusBus.log("Reconnect otomatis + reset penuh masih gagal ($reason) -- tetap mencoba lagi, tidak menyerah")
            }
            reconnectAttempt = 1
            hardResetAttempted = false
        }

        // PENTING: pakai lastConfig (bisa saja BARU SAJA diganti ke akun
        // cadangan di atas), BUKAN val config lokal di awal fungsi ini yang
        // merekam nilai SEBELUM kemungkinan pergantian itu terjadi.
        val configToUse = lastConfig ?: config
        val delayMs = (3000L * reconnectAttempt).coerceAtMost(RECONNECT_BACKOFF_CAP_MS)
        StatusBus.log("Tunnel terputus ($reason) -- reconnect otomatis percobaan $reconnectAttempt dalam ${delayMs / 1000}s")
        StatusBus.state.value = "Tunnel terputus — reconnect otomatis (percobaan $reconnectAttempt)..."
        updateNotification("Reconnect otomatis (percobaan $reconnectAttempt)...")

        serviceScope.launch {
            delay(delayMs)
            if (stoppingIntentionally || vpnInterface == null) return@launch
            handlingDeath.set(false)
            establishTunnel(configToUse, isReconnect = true)
        }
    }

    /**
     * Ambil kandidat [ServerConfig] akun cadangan BERIKUTNYA dari
     * [fallbackProfiles] (round-robin lewat [fallbackIndex]), lewati akun
     * yang konfigurasinya ternyata tidak valid (mis. link Xray kosong).
     * Null kalau tidak ada akun cadangan sama sekali, atau semua yang ada
     * ternyata tidak valid -- pemanggil lalu jatuh balik ke perilaku lama
     * (mengulang config semula).
     */
    private fun pickNextFallbackConfig(): ServerConfig? {
        if (fallbackProfiles.isEmpty()) return null
        val vpnSettings = VpnSettingsStore.load(this)
        repeat(fallbackProfiles.size) {
            val candidate = fallbackProfiles[fallbackIndex % fallbackProfiles.size]
            fallbackIndex++
            val built = candidate.config.toServerConfigOrNull() ?: return@repeat
            val withSocksOverride = if (vpnSettings.socksPort > 0) built.copy(socksPort = vpnSettings.socksPort) else built
            return withSocksOverride.copy(udpgwPort = vpnSettings.udpgwPort)
        }
        return null
    }

    /**
     * Upaya TERAKHIR sebelum benar-benar menyerah (dipanggil PERSIS SEKALI per
     * siklus gagal, dijaga [hardResetAttempted]): beda dari reconnect biasa
     * di [handleTunnelDeath] yang SENGAJA membiarkan TUN interface tetap
     * hidup, di sini SEMUANYA dibongkar total termasuk TUN interface itu
     * sendiri, lalu dibuat ulang dari nol dan dicoba connect sekali lagi.
     *
     * PENTING (trade-off yang harus disadari): selama TUN interface mati di
     * sini (dari titik ini sampai builder.establish() baru selesai lagi di
     * bawah), trafik device TIDAK lewat tunnel sama sekali (balik ke jalur
     * normal device, sebentar) -- beda dari reconnect biasa yang TUN-nya
     * tidak pernah turun. Makanya ini SENGAJA tidak dijadikan perilaku
     * default tiap reconnect, cuma dipakai sebagai jalan terakhir kalau
     * reconnect ringan berkali-kali sudah gagal semua (kemungkinan ada
     * state internal TUN/engine yang nyangkut & butuh benar-benar dari nol).
     */
    private fun hardResetAndRetry(config: ServerConfig, reason: String) {
        Log.w(TAG, "Reconnect ringan gagal $MAX_RECONNECT_ATTEMPTS kali ($reason) -- melakukan reset penuh (termasuk TUN interface)")
        StatusBus.log("Reconnect otomatis gagal $MAX_RECONNECT_ATTEMPTS kali ($reason) -- mencoba reset penuh (termasuk antarmuka VPN) sebagai upaya terakhir")
        StatusBus.state.value = "Reset penuh tunnel, mencoba sekali lagi..."
        updateNotification("Reset penuh, mencoba sekali lagi...")

        hardResetAttempted = true
        watchdogJob?.cancel()
        watchdogJob = null
        pingJob?.cancel()
        pingJob = null
        unregisterNetworkWatcher()

        // Bongkar SEMUANYA, termasuk TUN interface -- beda dari
        // handleTunnelDeath() yang sengaja membiarkan vpnInterface hidup.
        val engine = tunEngine
        val vpnIf = vpnInterface
        tunEngine = null
        vpnInterface = null
        stopHttpProxyServer()
        if (engine != null) {
            runBlockingWithTimeout("tunEngine.stop() (hard reset)") { engine.stop() }
        }
        runBlockingWithTimeout("sshTunnelManager.disconnect() (hard reset)") { sshTunnelManager.disconnect() }
        runBlockingWithTimeout("xrayTunnelManager.disconnect() (hard reset)") { xrayTunnelManager.disconnect() }
        try {
            vpnIf?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error saat menutup TUN interface (hard reset)", e)
        }

        // Jeda sebentar (biar OS/network settle) sebelum bikin ulang TUN
        // interface dari nol dan coba connect lagi seperti awal.
        serviceScope.launch {
            delay(2000L)
            if (stoppingIntentionally) return@launch

            StatusBus.log("Membuat ulang antarmuka VPN (TUN) dari nol...")
            StatusBus.initSteps(buildStepsFor(config))
            StatusBus.start(StepId.TUN)

            val vpnSettings = VpnSettingsStore.load(this@MyVpnService)
            // REVERT (sama seperti startVpn()): IPv6 TIDAK di-route ke TUN --
            // lihat catatan panjang di builder pertama pada startVpn() untuk
            // alasannya (server SSH tidak punya rute IPv6, jadi semua CONNECT
            // ke tujuan IPv6 selalu gagal & membanjiri log).
            val builder = Builder()
                .setSession("SuryaVPN")
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(currentMtu)
            applyDnsServers(builder, config, vpnSettings)

            vpnInterface = try {
                builder.establish()
            } catch (e: Exception) {
                Log.e(TAG, "Hard reset: gagal membuat ulang TUN interface", e)
                StatusBus.fail(StepId.TUN, e.message ?: e.javaClass.simpleName)
                StatusBus.skipRemainingPending()
                StatusBus.state.value = "Gagal: reset penuh juga gagal membuat antarmuka VPN (${e.message})"
                stopVpn()
                return@launch
            }
            StatusBus.success(StepId.TUN)
            registerNetworkWatcher()
            handlingDeath.set(false)
            establishTunnel(config, isReconnect = true)
        }
    }

    /**
     * Cek periodik: port SOCKS5 lokal (dipakai SSH maupun Xray) masih bisa
     * di-connect atau tidak. Ini jaring pengaman UNIVERSAL -- beda dari
     * callback hev-engine/SSH-monitor yang spesifik per komponen, watchdog
     * ini nutup celah kasus yang tidak trigger callback manapun (mis.
     * Xray-core hang/mati tanpa melempar error, yang tidak punya hook
     * "connection lost" resmi ke luar seperti trilead-ssh2 py di SSH).
     */
    private fun startWatchdog(config: ServerConfig) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            var consecutiveReachabilityFailures = 0
            var cycleCount = 0
            // FIX (laporan user: "reconnect sendiri tiap beberapa menit
            // walau Auto Ping sudah dimatikan"): sebelumnya SETIAP siklus
            // watchdog (tiap ~20s) buka channel SSH baru lewat tunnel buat
            // probe reachability -- ternyata watchdog INI SENDIRI (bukan
            // cuma Auto Ping) sudah cukup menghasilkan pola buka-tutup
            // channel identik berulang yang dicurigai server "bug host"
            // ketat sebagai penyalahgunaan, lalu sesi diputus/throttle --
            // gejalanya persis "reconnect sendiri tiap beberapa menit".
            //
            // Sekarang dipisah jadi dua siklus dengan interval BEDA JAUH:
            //  - Cek lokal (isSocksPortAlive) tiap WATCHDOG_LOCAL_CHECK_INTERVAL_MS
            //    -- ini TIDAK membuka channel SSH sama sekali (murni cek
            //    port di 127.0.0.1 di dalam device sendiri), jadi boleh
            //    sesering apa pun tanpa menambah "sidik jari" trafik ke server.
            //  - Probe jarak jauh (yang BENERAN buka channel SSH) cuma
            //    dilakukan sekali setiap WATCHDOG_REMOTE_PROBE_INTERVAL_MS
            //    (+ jitter lebar acak) -- dari ~180x/jam jadi ~6-8x/jam.
            var nextRemoteProbeAtMs = System.currentTimeMillis() +
                WATCHDOG_REMOTE_PROBE_INTERVAL_MS +
                java.util.concurrent.ThreadLocalRandom.current().nextLong(0, WATCHDOG_REMOTE_PROBE_JITTER_MS + 1)

            while (isActive) {
                // Jitter kecil di cek lokal juga -- murah, tapi tetap dibuat
                // tidak identik persis biar tidak ada dua timer yang selalu
                // rebound bareng.
                val localJitterMs = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 3001)
                delay(WATCHDOG_LOCAL_CHECK_INTERVAL_MS + localJitterMs)
                if (stoppingIntentionally) break

                // 1) Cek port lokal dulu (murah, TIDAK menyentuh channel SSH
                //    sama sekali) -- kalau ini saja sudah mati, Xray-core/SSH
                //    proxy-nya sendiri yang crash, tidak perlu tunggu probe
                //    reachability segala.
                if (!isSocksPortAlive(config.socksPort)) {
                    Log.w(TAG, "Watchdog: SOCKS5 lokal (127.0.0.1:${config.socksPort}) tidak merespons")
                    handleTunnelDeath("SOCKS5 lokal tidak merespons")
                    break
                }

                // 2) Port lokal hidup TIDAK BERARTI tunnel benar-benar tembus
                //    ke internet DENGAN AKUN YANG MASIH VALID -- tapi probe
                //    ini BENERAN buka channel SSH baru, jadi HANYA dilakukan
                //    kalau sudah waktunya (lihat nextRemoteProbeAtMs di atas),
                //    bukan setiap siklus lokal.
                if (System.currentTimeMillis() < nextRemoteProbeAtMs) continue
                nextRemoteProbeAtMs = System.currentTimeMillis() +
                    WATCHDOG_REMOTE_PROBE_INTERVAL_MS +
                    java.util.concurrent.ThreadLocalRandom.current().nextLong(0, WATCHDOG_REMOTE_PROBE_JITTER_MS + 1)

                if (stoppingIntentionally) break
                cycleCount++
                val useHeavyCheck = cycleCount % HEAVY_CHECK_EVERY_N_CYCLES == 0
                val reachable = if (useHeavyCheck) {
                    verifyTunnelReallyWorks(config.socksPort)
                } else {
                    keepAliveThroughTunnel(config.socksPort, "connectivitycheck.gstatic.com", 80) != null
                }

                if (reachable) {
                    consecutiveReachabilityFailures = 0
                } else {
                    consecutiveReachabilityFailures++
                    Log.w(
                        TAG,
                        "Watchdog: probe lewat tunnel gagal " +
                            "($consecutiveReachabilityFailures/$WATCHDOG_REACHABILITY_FAIL_THRESHOLD)"
                    )
                    if (consecutiveReachabilityFailures >= WATCHDOG_REACHABILITY_FAIL_THRESHOLD) {
                        handleTunnelDeath("Tunnel tidak menjangkau internet / akun tidak valid (tidak ada trafik nyata yang balik)")
                        break
                    }
                }
            }
        }
    }

    private fun isSocksPortAlive(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), WATCHDOG_PROBE_TIMEOUT_MS)
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * "Auto Ping" (kartu "Pengaturan Dasar" -- SENGAJA TERPISAH dari kartu
     * "VPN Setting", lihat [GeneralSettingsStore]). Beda tujuan dari
     * [startWatchdog]: watchdog ngecek SOCKS5 LOKAL buat trigger reconnect
     * otomatis. Loop ini punya DUA tugas tiap siklus:
     *  1. Ping diagnostik ke [ServerConfig.host] ASLI (lewat [pingHost],
     *     BYPASS tunnel) -- murni info latency ke [StatusBus.log] (layar
     *     Log Koneksi), TIDAK memicu reconnect apa pun.
     *  2. Keep-alive SUNGGUHAN lewat [keepAliveThroughTunnel] -- CONNECT ke
     *     target dari [GeneralSettings.keepAliveTarget] (bisa diatur user di
     *     layar Pengaturan, lihat [parseKeepAliveTarget]) lewat SOCKS5 lokal
     *     (artinya BENERAN lewat channel SSH/outbound Xray yang aktif),
     *     supaya koneksi ke server TIDAK dianggap idle & diputus paksa oleh
     *     firewall/NAT operator seluler. Ini yang sebelumnya TIDAK ADA --
     *     pingHost() doang tidak menyentuh tunnel sama sekali.
     *
     * Job ini SELALU jalan selama tunnel aktif (bukan cuma kalau enabled di
     * awal) dan baca ulang [GeneralSettingsStore] TIAP siklus -- supaya
     * toggle Auto Ping / ganti interval di Pengaturan saat tunnel SEDANG
     * aktif langsung kepakai di siklus berikutnya, tanpa perlu
     * disconnect+reconnect dulu.
     */
    private fun startPingLoop(config: ServerConfig) {
        pingJob?.cancel()
        pingJob = serviceScope.launch {
            while (isActive) {
                val settings = GeneralSettingsStore.load(this@MyVpnService)
                val intervalMs = settings.pingIntervalSeconds.coerceIn(
                    com.example.tunnelapp.model.GeneralSettings.MIN_PING_INTERVAL_SECONDS,
                    com.example.tunnelapp.model.GeneralSettings.MAX_PING_INTERVAL_SECONDS
                ) * 1000L

                delay(intervalMs)
                if (stoppingIntentionally) break
                if (!settings.autoPingEnabled) continue // tetap nunggu, siap nyala begitu di-toggle ON

                val elapsedMs = pingHost(config.host, config.port)
                if (elapsedMs != null) {
                    StatusBus.log("Auto Ping: ${config.host}:${config.port} balas dalam ${elapsedMs}ms")
                } else {
                    StatusBus.log("Auto Ping: ${config.host}:${config.port} tidak merespons (timeout ${PING_TIMEOUT_MS}ms)")
                }

                if (stoppingIntentionally) break
                val (targetHost, targetPort) = parseKeepAliveTarget(settings.keepAliveTarget)
                val useHttp = settings.keepAliveMethod == com.example.tunnelapp.model.GeneralSettings.METHOD_HTTP
                // Warna durasi "(...ms)" tergantung cepat/lambatnya (permintaan
                // user): 1-80ms BIRU, 85ms ke atas MERAH -- lihat
                // pingMsColorHex(). Ambil dari resource ping_ms_fast/slow
                // supaya satu sumber kebenaran sama seperti warna lain di app.
                fun redMs(ms: Long) = "<font color='${pingMsColorHex(ms)}'>${ms}ms</font>"
                if (useHttp) {
                    val result = httpKeepAliveThroughTunnel(config.socksPort, targetHost, targetPort)
                    if (result != null) {
                        StatusBus.log("HTTP Ping ${result.statusText} (${redMs(result.elapsedMs)})")
                    } else {
                        StatusBus.log("HTTP Ping gagal ke $targetHost:$targetPort lewat tunnel")
                    }
                } else {
                    val keepAliveMs = keepAliveThroughTunnel(config.socksPort, targetHost, targetPort)
                    if (keepAliveMs != null) {
                        StatusBus.log("Keep-alive (TCP): $targetHost:$targetPort lewat tunnel sukses (${redMs(keepAliveMs)})")
                    } else {
                        StatusBus.log("Keep-alive (TCP): $targetHost:$targetPort lewat tunnel gagal")
                    }
                }
            }
        }
    }

    /**
     * Parse input user "host:port" ATAU cuma "host" ([GeneralSettings.keepAliveTarget])
     * jadi pasangan (host, port). Ambil bagian SETELAH titik dua TERAKHIR
     * sebagai port -- supaya hostname yang aneh-aneh tetap kepisah dengan
     * benar.
     *
     * FIX (permintaan user "bisa tanpa port?"): dulu kalau tidak ada titik
     * dua SAMA SEKALI, fungsi ini membuang HOST YANG SUDAH DIKETIK USER juga
     * (bukan cuma port-nya) dan diam-diam ganti KEDUANYA ke default Google --
     * jadi user yang sengaja isi host custom tanpa port merasa settingnya
     * "tidak ke-save"/diabaikan. Sekarang: tidak ada titik dua = anggap
     * SELURUH input sebagai host apa adanya, port otomatis
     * [GeneralSettings.DEFAULT_KEEP_ALIVE_PORT] (443) -- host user TETAP
     * DIPAKAI. Fallback PENUH ke default Google cuma terjadi kalau memang
     * tidak ada host yang bisa dipakai sama sekali (input kosong) ATAU user
     * secara eksplisit mengetik titik dua tapi port setelahnya rusak/di luar
     * jangkauan (mis. "host:abc" atau "host:99999") -- itu jelas typo yang
     * disengaja, beda kasus dari "memang tidak mau isi port".
     */
    private fun parseKeepAliveTarget(raw: String): Pair<String, Int> {
        val fallbackHost = "www.google.com"
        val fallbackPort = com.example.tunnelapp.model.GeneralSettings.DEFAULT_KEEP_ALIVE_PORT
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallbackHost to fallbackPort

        val sepIndex = trimmed.lastIndexOf(':')
        if (sepIndex <= 0 || sepIndex == trimmed.length - 1) {
            // Tidak ada ":port" sama sekali -- pakai host user apa adanya + port default.
            return trimmed to fallbackPort
        }

        val host = trimmed.substring(0, sepIndex).trim()
        val port = trimmed.substring(sepIndex + 1).trim().toIntOrNull()
        if (host.isEmpty()) return fallbackHost to fallbackPort
        if (port == null || port !in 1..65535) {
            // Ada titik dua tapi port-nya rusak -- host user tetap dipakai,
            // cuma port-nya yang di-fallback (bukan dua-duanya).
            return host to fallbackPort
        }

        return host to port
    }

    /**
     * Ukur round-trip lewat TCP connect (bukan ICMP -- app pihak ketiga di
     * Android non-root umumnya tidak bisa buka raw ICMP socket) ke
     * [host]:[port] ASLI di internet (di luar TUN interface, makanya WAJIB
     * [protect] dulu -- persis seperti socket kontrol SSH/Xray, supaya
     * paketnya tidak nyasar masuk ke TUN interface kita sendiri dan bikin
     * loop routing). MURNI diagnostik, tidak menyentuh tunnel sama sekali.
     */
    private fun pingHost(host: String, port: Int): Long? = try {
        Socket().use { socket ->
            protect(socket)
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS)
            System.currentTimeMillis() - start
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Verifikasi tunnel BENERAN bisa dipakai: BENERAN kirim request HTTP lewat
     * tunnel dan menunggu balasan HTTP ASLI dari server tujuan -- bukan cuma
     * buka-tutup handshake SOCKS5 seperti [keepAliveThroughTunnel] (yang
     * tujuannya beda, lihat catatan di sana).
     *
     * PENTING (bug fix: "internet nyala lagi, VPN langsung 'terhubung' tanpa
     * ngecek akun valid atau tidak"): baik SOCKS5 lokal (mode SSH lewat
     * [Socks5Server]) maupun inbound Xray-core (mode Xray) MENGIRIM BALASAN
     * SOCKS5 "sukses" begitu koneksi TCP OUTBOUND ke server tujuan berhasil
     * dibuka -- ini kejadian di level TCP/SOCKS5, SAMA SEKALI TIDAK
     * MENUNGGU server VMess/VLESS/Trojan remote memvalidasi kredensial
     * (UUID/password) yang dikirim di dalam payload terenkripsi setelahnya.
     * Kalau akun sudah expired/invalid, server proxy remote-nya SENDIRI masih
     * hidup (makanya TCP tetap connect & SOCKS5 tetap balas "sukses"), tapi
     * begitu ada trafik ASLI, server itu diam-diam DROP paket kita tanpa
     * balasan apa pun (tidak ada RST, tidak ada pesan error -- cuma diam).
     * [keepAliveThroughTunnel] tidak pernah sampai ke tahap kirim data asli,
     * jadi tidak pernah menangkap kasus ini -- akun invalid tetap dianggap
     * "reachable".
     *
     * Fix-nya: kirim request HTTP polos (port 80, BUKAN TLS, supaya gampang
     * diparse tanpa perlu implementasi handshake TLS sendiri) ke endpoint
     * connectivity-check RESMI milik Google (dipakai Android sendiri untuk
     * deteksi captive portal/internet, jadi hampir tidak pernah diblokir) --
     * lalu tunggu baris status HTTP ASLI ("HTTP/1.1 204 ..."). Endpoint ini
     * SENGAJA di-hardcode (bukan pakai target keep-alive yang bisa diatur
     * user di Pengaturan) supaya hasil verifikasi ini konsisten & tidak
     * kepengaruh salah ketik/target yang bukan HTTP di kartu "Pengaturan
     * Dasar". Kalau akun invalid, balasan ini TIDAK AKAN PERNAH datang --
     * [KEEP_ALIVE_TIMEOUT_MS] di bawah yang menangkapnya sebagai gagal.
     *
     * @return true HANYA kalau balasan HTTP ASLI dengan status 2xx/3xx
     * benar-benar diterima lewat tunnel; false untuk semua kegagalan lain
     * (tidak ada jaringan, TCP gagal connect, SOCKS5 ditolak, timeout
     * menunggu balasan HTTP -- termasuk kasus akun invalid di atas).
     *
     * FIX (laporan user: "reconnect sendiri tiap beberapa menit padahal
     * sinyal lancar, DarkTunnel dengan akun sama stabil"): dulu fungsi ini
     * SATU KALI percobaan -- satu request yang kebetulan telat/drop
     * (jitter jaringan seluler biasa, ATAU server tunnel yang dipakai
     * sedang sedikit sibuk) langsung dianggap "tunnel mati", padahal
     * detik berikutnya sebenarnya sudah normal lagi. Sekarang retry
     * [attempts] kali dengan jeda [VERIFY_RETRY_DELAY_MS] SEBELUM
     * benar-benar menyerah -- baru gagal kalau SEMUA percobaan gagal
     * berturut-turut, jauh lebih toleran terhadap satu blip sesaat.
     */
    private fun verifyTunnelReallyWorks(socksPort: Int, attempts: Int = 2): Boolean {
        repeat(attempts) { attemptIndex ->
            if (verifyTunnelReallyWorksOnce(socksPort)) return true
            if (attemptIndex < attempts - 1) {
                try {
                    Thread.sleep(VERIFY_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                }
            }
        }
        return false
    }

    private fun verifyTunnelReallyWorksOnce(socksPort: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), KEEP_ALIVE_TIMEOUT_MS)
            socket.soTimeout = KEEP_ALIVE_TIMEOUT_MS
            val out = socket.getOutputStream()
            val din = DataInputStream(socket.getInputStream())

            // Handshake SOCKS5 (persis seperti keepAliveThroughTunnel).
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            din.readFully(greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                return@use false
            }

            val host = "connectivitycheck.gstatic.com"
            val port = 80
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                write(hostBytes.size)
                write(hostBytes)
                write((port shr 8) and 0xFF)
                write(port and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val replyHeader = ByteArray(4)
            din.readFully(replyHeader)
            if (replyHeader[1] != 0x00.toByte()) return@use false
            val addrLen = when (replyHeader[3].toInt()) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> din.readUnsignedByte()
                else -> 0
            }
            if (addrLen > 0) din.skipBytes(addrLen)
            din.skipBytes(2)

            // --- Bedanya dari keepAliveThroughTunnel: BENERAN kirim request
            // HTTP & tunggu balasan ASLI dari server, bukan cuma buka-tutup
            // handshake SOCKS5. Ini yang bisa menangkap akun invalid/expired.
            val httpRequest = "GET /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
            out.write(httpRequest.toByteArray(Charsets.US_ASCII))
            out.flush()

            val statusLine = ByteArrayOutputStream()
            while (statusLine.size() < 64) {
                val b = din.read()
                if (b == -1 || b == '\n'.code) break
                if (b != '\r'.code) statusLine.write(b)
            }
            val line = statusLine.toByteArray().toString(Charsets.US_ASCII)
            Regex("""^HTTP/1\.\d\s+(\d{3})""").find(line)
                ?.groupValues?.get(1)?.toIntOrNull()?.let { it in 200..399 } == true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Keep-alive SUNGGUHAN: buka handshake SOCKS5 CONNECT ke
     * [targetHost]:[targetPort] lewat SOCKS5 LOKAL di 127.0.0.1:[socksPort]
     * -- port yang sama dipakai [Socks5Server] (mode SSH) maupun inbound
     * Xray-core (mode Xray), KEDUANYA tanpa autentikasi (method 0x00).
     * Bedanya dengan [pingHost]: koneksi ini betul-betul lewat channel
     * SSH / outbound Xray yang sudah konek ke server, jadi TCP flow ke
     * server itu kelihatan "aktif" oleh firewall/NAT di jalur tengah --
     * inilah yang bikin server tidak diputus paksa gara-gara dianggap idle.
     * Socket ditutup lagi begitu handshake CONNECT selesai (tidak perlu
     * kirim data beneran, cukup buka-tutup channel).
     *
     * CATATAN: fungsi ini SEKARANG cuma dipakai untuk "Keep-alive" (menjaga
     * koneksi tidak dianggap idle oleh firewall/NAT, di [startPingLoop]) --
     * BUKAN lagi untuk memutuskan apakah tunnel "valid"/akun masih aktif,
     * karena buka-tutup handshake saja TIDAK CUKUP untuk itu (lihat
     * [verifyTunnelReallyWorks] yang dipakai [establishTunnel] & [startWatchdog]
     * untuk keperluan itu).
     *
     * Return null kalau gagal di tahap mana pun (server nolak, konek gagal,
     * timeout, dll) -- dianggap sebagai satu siklus keep-alive yang gagal,
     * TIDAK memicu reconnect (biar konsisten dengan sifat "Auto Ping" yang
     * murni informatif; watchdog di [startWatchdog] yang sudah bertugas
     * mendeteksi tunnel benar-benar mati).
     */
    private fun keepAliveThroughTunnel(socksPort: Int, targetHost: String, targetPort: Int): Long? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), KEEP_ALIVE_TIMEOUT_MS)
            socket.soTimeout = KEEP_ALIVE_TIMEOUT_MS
            val out = socket.getOutputStream()
            val din = DataInputStream(socket.getInputStream())

            val start = System.currentTimeMillis()

            // Greeting SOCKS5: versi 5, 1 metode ditawarkan, no-auth (0x00).
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            din.readFully(greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                return@use null // server SOCKS5 lokal minta auth / versi tak dikenal
            }

            // Request CONNECT (0x01) pakai ATYP domain name (0x03) supaya
            // resolusi DNS "targetHost" dilakukan DI SISI SERVER (lewat
            // tunnel), bukan di device.
            val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                write(hostBytes.size)
                write(hostBytes)
                write((targetPort shr 8) and 0xFF)
                write(targetPort and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val replyHeader = ByteArray(4)
            din.readFully(replyHeader)
            if (replyHeader[1] != 0x00.toByte()) {
                return@use null // server balas kode error (host unreachable, dll)
            }

            // Habiskan sisa alamat balasan (BND.ADDR + BND.PORT) sesuai ATYP
            // supaya socket ditutup bersih -- bukan wajib secara fungsional,
            // tapi menghindari data nyangkut di buffer sebelum socket.close().
            val addrLen = when (replyHeader[3].toInt()) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> din.readUnsignedByte()
                else -> 0
            }
            if (addrLen > 0) din.skipBytes(addrLen)
            din.skipBytes(2)

            System.currentTimeMillis() - start
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Metode keep-alive KEDUA (permintaan user: "tambahkan metode ping yang
     * lain") -- beda dari [keepAliveThroughTunnel] yang cuma buka-tutup
     * handshake SOCKS5 CONNECT, ini BENERAN mengirim request HTTP GET ke
     * [targetHost]:[targetPort] lewat SOCKS5 lokal (jadi tetap lewat channel
     * SSH/outbound Xray yang aktif -- efek anti-idle ke firewall/NAT operator
     * SAMA seperti [keepAliveThroughTunnel]) dan menunggu baris status HTTP
     * ASLI dibalas.
     *
     * Semangatnya mirip [verifyTunnelReallyWorks], tapi SENGAJA target-nya
     * ikut [targetHost]/[targetPort] yang diatur user di Pengaturan (bukan
     * hardcode connectivitycheck.gstatic.com) -- verifyTunnelReallyWorks
     * TETAP hardcode apa adanya, TIDAK diubah/dipanggil dari sini, supaya
     * verifikasi "tunnel valid & akun tidak expired" saat connect/watchdog
     * tetap konsisten & tidak kepengaruh target keep-alive yang bisa
     * diketik bebas oleh user.
     *
     * Diterima status HTTP APA PUN (1xx-5xx) sebagai "hidup" -- beda dari
     * verifyTunnelReallyWorks yang mensyaratkan 2xx/3xx -- karena tujuan di
     * sini murni "server ini benar-benar membalas lewat tunnel" (anti-idle),
     * bukan "akun valid" (itu sudah tugas verifyTunnelReallyWorks/watchdog).
     * Kalau [targetHost] kebetulan cuma listen HTTPS di [targetPort] (paling
     * umum kalau port 443), request HTTP polos ini wajar gagal di-parse
     * sebagai HTTP (server balas handshake TLS, bukan teks HTTP) -- itu
     * dianggap gagal (null), sama seperti kegagalan lain di sini.
     *
     * Return elapsed ms (waktu sampai baris status HTTP pertama diterima)
     * kalau berhasil, null kalau gagal di tahap mana pun -- SAMA POLA dengan
     * [keepAliveThroughTunnel], TIDAK memicu reconnect (murni informatif).
     */
    /**
     * Dipakai bareng [statusLine] untuk log "HTTP Ping <status> (<ms>ms)"
     * (permintaan user, format & warna merah pada durasi ms meniru
     * DarkTunnel) -- lihat pemanggilnya di [startPingLoop].
     */
    private data class HttpPingResult(val elapsedMs: Long, val statusText: String)

    private fun httpKeepAliveThroughTunnel(socksPort: Int, targetHost: String, targetPort: Int): HttpPingResult? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), KEEP_ALIVE_TIMEOUT_MS)
            socket.soTimeout = KEEP_ALIVE_TIMEOUT_MS
            val out = socket.getOutputStream()
            val din = DataInputStream(socket.getInputStream())

            val start = System.currentTimeMillis()

            // Greeting + request CONNECT SOCKS5 -- persis pola yang sama
            // dengan keepAliveThroughTunnel/verifyTunnelReallyWorks.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            din.readFully(greeting)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                return@use null
            }

            val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
            if (hostBytes.size > 255) return@use null // ATYP domain SOCKS5 cuma muat panjang 1 byte
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
                write(hostBytes.size)
                write(hostBytes)
                write((targetPort shr 8) and 0xFF)
                write(targetPort and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val replyHeader = ByteArray(4)
            din.readFully(replyHeader)
            if (replyHeader[1] != 0x00.toByte()) return@use null

            val addrLen = when (replyHeader[3].toInt()) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> din.readUnsignedByte()
                else -> 0
            }
            if (addrLen > 0) din.skipBytes(addrLen)
            din.skipBytes(2)

            // BENERAN kirim request HTTP & tunggu balasan asli -- inilah
            // bedanya dari keepAliveThroughTunnel (yang cuma sampai sini).
            val httpRequest = "GET / HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\n\r\n"
            out.write(httpRequest.toByteArray(Charsets.US_ASCII))
            out.flush()

            val statusLine = ByteArrayOutputStream()
            while (statusLine.size() < 128) {
                val b = din.read()
                if (b == -1 || b == '\n'.code) break
                if (b != '\r'.code) statusLine.write(b)
            }
            val line = statusLine.toByteArray().toString(Charsets.US_ASCII)
            val statusMatch = Regex("""^HTTP/1\.\d\s+(.+)$""").find(line)
            val statusText = statusMatch?.groupValues?.get(1)?.trim() ?: return@use null

            HttpPingResult(System.currentTimeMillis() - start, statusText)
        }
    } catch (e: Exception) {
        null
    }

    /** Menjalankan [HevSocks5Engine], satu-satunya [TunEngine] yang dipakai app ini. */
    private fun startTunEngine(config: ServerConfig) {
        val fd = vpnInterface?.fd ?: throw IllegalStateException("TUN interface belum siap")
        val engine = HevSocks5Engine()
        tunEngine = engine
        engine.start(
            tunFd = fd,
            tunAddress = TUN_ADDRESS,
            mtu = currentMtu,
            socksHost = "127.0.0.1",
            socksPort = config.socksPort,
            onUnexpectedStop = { handleTunnelDeath("Engine tunnel (hev-socks5-tunnel) berhenti tak terduga") }
        )
    }

    private fun stopVpn() {
        // Cegah dobel: onDestroy/onRevoke/ACTION_DISCONNECT bisa saja
        // hampir bersamaan manggil ini. Kalau sudah diproses, cukup return --
        // bukan cuma optimisasi, ini WAJIB supaya tunEngine/vpnInterface yang
        // sudah di-null-kan di bawah tidak "dibongkar dua kali".
        if (!stopping.compareAndSet(false, true)) return
        startInProgress.set(false)

        // Tandai dulu SEBELUM membongkar apa pun -- sshTunnelManager.disconnect()
        // di bawah bakal manggil conn.close(), yang otomatis memicu
        // ConnectionMonitor.connectionLost() juga. Tanpa flag ini,
        // handleTunnelDeath() bisa salah mengira penutupan yang KITA lakukan
        // sendiri sebagai "tunnel mati sendiri" lalu nyoba reconnect balik.
        stoppingIntentionally = true
        watchdogJob?.cancel()
        watchdogJob = null
        pingJob?.cancel()
        pingJob = null
        releaseWakeLock()
        unregisterNetworkWatcher()
        // Batalkan proses connect/reconnect yang mungkin masih jalan di
        // serviceScope -- TIDAK memengaruhi shutdownScope di bawah (scope beda).
        serviceJob.cancel()

        // Jangan timpa pesan "Gagal: ..." yang sudah lebih spesifik kalau
        // stopVpn() ini dipanggil akibat error, bukan disconnect manual.
        if (!StatusBus.state.value.startsWith("Gagal")) {
            StatusBus.state.value = "Memutuskan..."
        }
        // FIX: sebelumnya stopVpn() cuma update StatusBus.state, sedangkan
        // layar Log (Tahapan Koneksi + Terminal) murni mengikuti
        // StatusBus.steps / StatusBus.liveLog -- jadi begitu VPN diputus,
        // dua-duanya tetap menampilkan snapshot terakhir saat masih
        // connect (seolah beku/tidak real), dan tahap yang masih RUNNING
        // (spinner) nyangkut selamanya. Sekarang kirim event nyata ke
        // liveLog + tandai tahap yang belum selesai.
        StatusBus.log("Memutuskan tunnel (diminta pengguna)...")
        // FIX: pakai markDisconnected() (bukan markInterrupted()) supaya tahap
        // yang sudah SUCCESS ikut direset -- lihat catatan di StatusBus.kt.
        // markInterrupted() saja tidak cukup kalau disconnect dipanggil SETELAH
        // tunnel sempat aktif penuh (semua tahap sudah hijau).
        StatusBus.markDisconnected()

        // Ambil referensi lokal, lalu langsung null-kan field-nya di sini
        // (masih di caller thread, cepat & tidak blocking) supaya startVpn()
        // berikutnya tidak salah kira VPN masih berjalan.
        val engine = tunEngine
        val vpnIf = vpnInterface
        tunEngine = null
        vpnInterface = null
        lastConfig = null
        stopHttpProxyServer()

        // --- FIX ANR ---
        // Semua pemanggilan di bawah ini BLOCKING (join thread native,
        // tutup socket SSH, JNI ke libXray) -- makanya WAJIB dieksekusi di
        // background thread (shutdownScope), BUKAN langsung di sini. Dulu
        // baris-baris ini jalan langsung di badan stopVpn(), yang dipanggil
        // dari onStartCommand()/onDestroy() di MAIN THREAD -- itulah
        // sumber dialog "TunnelApp isn't responding".
        shutdownJob = shutdownScope.launch {
            // Urutan penting: matikan tun engine dulu (masih pakai fd TUN &
            // SOCKS5), baru SSH/Xray, baru TUN interface-nya sendiri.
            //
            // FIX "tombol Kontrol Koneksi macet di Memutuskan... selamanya":
            // sebelumnya dipanggil TELANJANG di sini -- kalau
            // xrayTunnelManager.disconnect() kebetulan lagi menggantung
            // (misalnya karena handleTunnelDeath() sempat memicu disconnect
            // yang sama sebelum ini, saat jaringan device mati -- lihat
            // catatan di [runBlockingWithTimeout] dan guard [disconnecting]
            // di XrayTunnelManager), coroutine ini pun ikut menggantung
            // selamanya SEBELUM sempat sampai ke vpnIf?.close() dan
            // stopSelf() di bawah -- itulah kenapa VPN "tidak bisa dimatikan
            // sama sekali". Sekarang tiap langkah dibatasi waktu, jadi proses
            // shutdown ini DIJAMIN sampai ke stopForeground()/stopSelf() di
            // bawah dalam waktu terbatas, apa pun kondisi native lib di
            // baliknya.
            if (engine != null) {
                runBlockingWithTimeout("tunEngine.stop()") { engine.stop() }
            }

            // Aman dipanggil dua-duanya: masing-masing manager no-op kalau
            // memang tidak sedang aktif (lihat isConnected()/running di
            // XrayTunnelManager, connection == null di SshTunnelManager).
            runBlockingWithTimeout("sshTunnelManager.disconnect()") { sshTunnelManager.disconnect() }
            runBlockingWithTimeout("xrayTunnelManager.disconnect()") { xrayTunnelManager.disconnect() }

            try {
                vpnIf?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error saat menutup TUN interface", e)
            }

            if (!StatusBus.state.value.startsWith("Gagal")) {
                StatusBus.state.value = "Terputus"
            }
            // Baris log terakhir yang menandakan tunnel BENAR-BENAR sudah
            // ditutup (engine, SSH/Xray, dan TUN interface semua sudah
            // dibongkar) -- ini yang bikin layar Log terasa "real": ada
            // event baru yang muncul persis saat status berubah jadi
            // Terputus, bukan cuma diam di baris terakhir sebelum stop.
            StatusBus.log("Tunnel terputus, semua koneksi ditutup.")
            // stopForeground()/stopSelf() aman dipanggil dari thread mana pun.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun buildNotification(contentText: String): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "Status Koneksi Tunnel",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("SuryaVPN")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
