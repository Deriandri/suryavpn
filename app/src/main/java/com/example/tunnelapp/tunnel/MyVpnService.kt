package com.example.tunnelapp.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.example.tunnelapp.DashboardActivity
import com.example.tunnelapp.R
import com.example.tunnelapp.model.ServerConfig
import com.example.tunnelapp.model.VpnSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
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
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    // MTU dipakai di dua tempat (Builder.setMtu() & HevSocks5Engine.start()),
    // diambil dari VpnSettingsStore sekali di startVpn() supaya kedua sisi
    // pasti konsisten walau user ganti nilainya di tengah sesi tunnel aktif.
    private var currentMtu: Int = com.example.tunnelapp.model.VpnSettings.DEFAULT_MTU
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
        releaseWakeLock()
        // Batalkan proses connect/reconnect yang mungkin masih jalan di
        // serviceScope -- TIDAK memengaruhi shutdownScope di bawah (scope beda).
        serviceJob.cancel()

        // Jangan timpa pesan "Gagal: ..." yang sudah lebih spesifik kalau
        // stopVpn() ini dipanggil akibat error, bukan disconnect manual.
        if (!StatusBus.state.value.startsWith("Gagal")) {
            StatusBus.state.value = "Memutuskan..."
        }

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
