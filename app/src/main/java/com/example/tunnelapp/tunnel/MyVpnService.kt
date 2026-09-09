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
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.example.tunnelapp.DashboardActivity
import com.example.tunnelapp.R
import com.example.tunnelapp.model.ServerConfig
import com.example.tunnelapp.model.GeneralSettingsStore
import com.example.tunnelapp.model.VpnSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
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

        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val TUN_ADDRESS = "10.10.0.2"
        private const val DEFAULT_DNS = "1.1.1.1"
        private const val WAKE_LOCK_TAG = "TunnelApp:VpnKeepAwake"

        // --- Deteksi & reconnect otomatis kalau tunnel mati sendiri ---
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val WATCHDOG_PROBE_TIMEOUT_MS = 3000

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
        private const val KEEP_ALIVE_TIMEOUT_MS = 5000
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
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val sshTunnelManager = SshTunnelManager()
    private val xrayTunnelManager by lazy { XrayTunnelManager(this) }
    private var tunEngine: TunEngine? = null

    // Config terakhir yang berhasil/sedang dicoba -- dipakai untuk reconnect
    // otomatis tanpa perlu minta izin VPN ke user lagi (TUN interface yang
    // sudah establish() dibiarkan hidup selama proses reconnect).
    private var lastConfig: ServerConfig? = null
    private var reconnectAttempt = 0
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

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.w(TAG, "Jaringan fisik device hilang (data seluler/WiFi dimatikan)")
                handleTunnelDeath("Jaringan device terputus (data/WiFi mati)")
            }

            override fun onUnavailable() {
                Log.w(TAG, "Tidak ada jaringan fisik yang tersedia")
                handleTunnelDeath("Tidak ada jaringan aktif di device")
            }
        }
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mendaftarkan pemantau jaringan", e)
        }
    }

    private fun unregisterNetworkWatcher() {
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
                startVpn(config)
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startVpn(config: ServerConfig) {
        if (vpnInterface != null) {
            Log.w(TAG, "VPN sudah berjalan, abaikan permintaan start kedua")
            return
        }

        lastConfig = config
        reconnectAttempt = 0
        stoppingIntentionally = false
        handlingDeath.set(false)

        val vpnSettings = VpnSettingsStore.load(this)
        currentMtu = vpnSettings.mtu
        currentAutoReconnect = vpnSettings.autoReconnect
        acquireWakeLockIfNeeded(vpnSettings.keepCpuAwake)

        startForeground(NOTIFICATION_ID, buildNotification("Menghubungkan..."))
        StatusBus.clearLog()
        StatusBus.initSteps(buildStepsFor(config))
        StatusBus.state.value = "Membuat antarmuka VPN (TUN)..."
        StatusBus.start(StepId.TUN)

        val builder = Builder()
            .setSession("TunnelApp")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(currentMtu)
        applyDnsServers(builder, config, vpnSettings)

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuat TUN interface", e)
            StatusBus.fail(StepId.TUN, e.message ?: e.javaClass.simpleName)
            StatusBus.skipRemainingPending()
            StatusBus.state.value = "Gagal membuat TUN interface: ${e.message}"
            stopSelf()
            return
        }
        StatusBus.success(StepId.TUN)

        Log.i(TAG, "TUN interface berhasil dibuat")
        registerNetworkWatcher()
        StatusBus.state.value = "Menghubungkan ke ${config.host}:${config.port}..."

        establishTunnel(config, isReconnect = false)
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
     * salah satunya diisi. Kalau keduanya kosong/tidak diisi sama sekali,
     * fallback ke [DEFAULT_DNS] supaya resolusi domain tetap jalan seperti
     * perilaku lama.
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
            builder.addDnsServer(DEFAULT_DNS)
        }
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
    private fun establishTunnel(config: ServerConfig, isReconnect: Boolean) {
        serviceScope.launch {
            try {
                if (isReconnect) {
                    StatusBus.initSteps(buildStepsFor(config))
                    StatusBus.start(StepId.TUN)
                    StatusBus.success(StepId.TUN)
                }

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
                        onUnexpectedDisconnect = { reason -> handleTunnelDeath("SSH: $reason") }
                    )
                    StatusBus.state.value = "SSH tersambung. Mengaktifkan tunnel..."
                    updateNotification("SSH tersambung ke ${config.host}")
                }

                StatusBus.start(StepId.TUNNEL_ACTIVE)
                startTunEngine(config)
                StatusBus.success(StepId.TUNNEL_ACTIVE)

                // Reconnect (kalau ada) sukses -- reset hitungan percobaan &
                // nyalakan ulang watchdog buat siklus berikutnya.
                reconnectAttempt = 0
                handlingDeath.set(false)
                startWatchdog(config)
                startPingLoop(config)

                StatusBus.state.value = "Tunnel aktif — semua trafik device lewat SSH"
                updateNotification("Tunnel aktif (${config.host})")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menyalakan tunnel", e)
                StatusBus.skipRemainingPending()
                // Pakai pesan dari tahap yang benar-benar gagal (lebih akurat)
                // kalau ada, baru fallback ke pesan exception generik.
                val reason = StatusBus.firstErrorDetail() ?: e.message ?: e.javaClass.simpleName
                if (isReconnect) {
                    scheduleReconnectOrGiveUp("Reconnect gagal: $reason")
                } else {
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
        try { tunEngine?.stop() } catch (_: Exception) {}
        tunEngine = null
        try { sshTunnelManager.disconnect() } catch (_: Exception) {}
        try { xrayTunnelManager.disconnect() } catch (_: Exception) {}

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
            StatusBus.log("Tunnel terputus ($reason) -- reconnect otomatis gagal setelah $MAX_RECONNECT_ATTEMPTS percobaan")
            StatusBus.state.value = "Gagal: tunnel terputus, reconnect otomatis gagal ($reason)"
            stopVpn()
            return
        }

        val delayMs = 3000L * reconnectAttempt
        StatusBus.log("Tunnel terputus ($reason) -- reconnect otomatis percobaan $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS dalam ${delayMs / 1000}s")
        StatusBus.state.value = "Tunnel terputus — reconnect otomatis ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)..."
        updateNotification("Reconnect otomatis ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)...")

        serviceScope.launch {
            delay(delayMs)
            if (stoppingIntentionally || vpnInterface == null) return@launch
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
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (stoppingIntentionally) break
                if (!isSocksPortAlive(config.socksPort)) {
                    Log.w(TAG, "Watchdog: SOCKS5 lokal (127.0.0.1:${config.socksPort}) tidak merespons")
                    handleTunnelDeath("SOCKS5 lokal tidak merespons")
                    break
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
                val keepAliveMs = keepAliveThroughTunnel(config.socksPort, targetHost, targetPort)
                if (keepAliveMs != null) {
                    StatusBus.log("Keep-alive: handshake ke $targetHost:$targetPort lewat tunnel sukses (${keepAliveMs}ms)")
                } else {
                    StatusBus.log("Keep-alive: gagal membuka handshake ke $targetHost:$targetPort lewat tunnel")
                }
            }
        }
    }

    /**
     * Parse input user "host:port" ([GeneralSettings.keepAliveTarget]) jadi
     * pasangan (host, port). Ambil bagian SETELAH titik dua TERAKHIR sebagai
     * port -- supaya hostname yang aneh-aneh tetap kepisah dengan benar --
     * lalu validasi port-nya harus angka 1-65535. Kalau kosong, format salah
     * (tidak ada titik dua, host kosong), atau port invalid, fallback diam-
     * diam ke [GeneralSettings.DEFAULT_KEEP_ALIVE_TARGET] supaya keep-alive
     * tetap jalan walau user salah ketik, bukan malah mati total.
     */
    private fun parseKeepAliveTarget(raw: String): Pair<String, Int> {
        val fallbackHost = "www.google.com"
        val fallbackPort = 443
        val trimmed = raw.trim()
        val sepIndex = trimmed.lastIndexOf(':')
        if (sepIndex <= 0 || sepIndex == trimmed.length - 1) return fallbackHost to fallbackPort

        val host = trimmed.substring(0, sepIndex).trim()
        val port = trimmed.substring(sepIndex + 1).trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return fallbackHost to fallbackPort

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
        StatusBus.markInterrupted()

        // Ambil referensi lokal, lalu langsung null-kan field-nya di sini
        // (masih di caller thread, cepat & tidak blocking) supaya startVpn()
        // berikutnya tidak salah kira VPN masih berjalan.
        val engine = tunEngine
        val vpnIf = vpnInterface
        tunEngine = null
        vpnInterface = null
        lastConfig = null

        // --- FIX ANR ---
        // Semua pemanggilan di bawah ini BLOCKING (join thread native,
        // tutup socket SSH, JNI ke libXray) -- makanya WAJIB dieksekusi di
        // background thread (shutdownScope), BUKAN langsung di sini. Dulu
        // baris-baris ini jalan langsung di badan stopVpn(), yang dipanggil
        // dari onStartCommand()/onDestroy() di MAIN THREAD -- itulah
        // sumber dialog "TunnelApp isn't responding".
        shutdownScope.launch {
            // Urutan penting: matikan tun engine dulu (masih pakai fd TUN &
            // SOCKS5), baru SSH/Xray, baru TUN interface-nya sendiri.
            try {
                engine?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop tun engine", e)
            }

            // Aman dipanggil dua-duanya: masing-masing manager no-op kalau
            // memang tidak sedang aktif (lihat isConnected()/running di
            // XrayTunnelManager, connection == null di SshTunnelManager).
            try {
                sshTunnelManager.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnect SSH", e)
            }
            try {
                xrayTunnelManager.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnect Xray", e)
            }

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
            .setContentTitle("TunnelApp")
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
