package com.example.tunnelapp.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.tunnelapp.DashboardActivity
import com.example.tunnelapp.R
import com.example.tunnelapp.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val TUN_ADDRESS = "10.10.0.2"
        private const val TUN_MTU = 1500
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val sshTunnelManager = SshTunnelManager()
    private val xrayTunnelManager by lazy { XrayTunnelManager(this) }
    private var tunEngine: TunEngine? = null

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
                    xrayLink = intent.getStringExtra(EXTRA_XRAY_LINK)
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

        startForeground(NOTIFICATION_ID, buildNotification("Menghubungkan..."))
        StatusBus.initSteps(buildStepsFor(config))
        StatusBus.state.value = "Membuat antarmuka VPN (TUN)..."
        StatusBus.start(StepId.TUN)

        val builder = Builder()
            .setSession("TunnelApp")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMtu(TUN_MTU)

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

        serviceScope.launch {
            try {
                if (config.usesXray()) {
                    // libXray protect socket beroperasi di level fd mentah (Go/gomobile),
                    // bukan java.net.Socket seperti jalur SSH -- pakai overload
                    // VpnService.protect(fd: Int) langsung.
                    xrayTunnelManager.connect(config) { fd -> protect(fd) }
                    StatusBus.state.value = "Xray-core tersambung. Mengaktifkan tunnel..."
                    updateNotification("Xray-core aktif")
                } else {
                    sshTunnelManager.connect(config) { socket -> protect(socket) }
                    StatusBus.state.value = "SSH tersambung. Mengaktifkan tunnel..."
                    updateNotification("SSH tersambung ke ${config.host}")
                }

                StatusBus.start(StepId.TUNNEL_ACTIVE)
                startTunEngine(config)
                StatusBus.success(StepId.TUNNEL_ACTIVE)

                StatusBus.state.value = "Tunnel aktif — semua trafik device lewat SSH"
                updateNotification("Tunnel aktif (${config.host})")
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menyalakan tunnel", e)
                StatusBus.skipRemainingPending()
                // Pakai pesan dari tahap yang benar-benar gagal (lebih akurat)
                // kalau ada, baru fallback ke pesan exception generik.
                val reason = StatusBus.firstErrorDetail() ?: e.message ?: e.javaClass.simpleName
                StatusBus.state.value = "Gagal: $reason"
                stopVpn()
            }
        }
    }

    /** Menjalankan [HevSocks5Engine], satu-satunya [TunEngine] yang dipakai app ini. */
    private fun startTunEngine(config: ServerConfig) {
        val fd = vpnInterface?.fd ?: throw IllegalStateException("TUN interface belum siap")
        val engine = HevSocks5Engine()
        tunEngine = engine
        engine.start(
            tunFd = fd,
            tunAddress = TUN_ADDRESS,
            mtu = TUN_MTU,
            socksHost = "127.0.0.1",
            socksPort = config.socksPort
        )
    }

    private fun stopVpn() {
        // Urutan penting: matikan tun engine dulu (masih pakai fd TUN & SOCKS5),
        // baru SSH/Xray, baru TUN interface-nya sendiri.
        tunEngine?.stop()
        tunEngine = null

        // Aman dipanggil dua-duanya: masing-masing manager no-op kalau memang
        // tidak sedang aktif (lihat isConnected()/running di XrayTunnelManager,
        // connection == null di SshTunnelManager).
        sshTunnelManager.disconnect()
        xrayTunnelManager.disconnect()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error saat menutup TUN interface", e)
        }
        vpnInterface = null

        serviceJob.cancel()
        // Jangan timpa pesan "Gagal: ..." yang sudah lebih spesifik kalau
        // stopVpn() ini dipanggil akibat error, bukan disconnect manual.
        if (!StatusBus.state.value.startsWith("Gagal")) {
            StatusBus.state.value = "Terputus"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
