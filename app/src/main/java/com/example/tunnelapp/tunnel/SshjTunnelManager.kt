package com.example.tunnelapp.tunnel

import android.util.Log
import com.example.tunnelapp.model.ServerConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Engine SSH KEDUA app ini (permintaan user: "tambah library, bisa pilih di
 * Pengaturan") -- pakai sshj (com.hierynomus:sshj, lihat app/build.gradle.kts),
 * BUKAN Apache MINA SSHD (lebih lengkap tapi berbasis NIO gaya server & riwayat
 * rewel di Android) dan bukan pula fork JSch (usul awal, tapi sshj dinilai
 * paritas fiturnya lebih baik).
 *
 * BEDA UTAMA dari [SshTunnelManager] (trilead-ssh2): sshj BENERAN mendukung
 * kompresi zlib/zlib@openssh.com lewat [SSHClient.useCompression] -- lihat
 * catatan JUJUR soal ini di VpnSettingsStore.compressionEnabled.
 *
 * CATATAN VERIFIKASI (baca ini kalau gagal compile): kelas ini ditulis dari
 * pengetahuan API publik sshj (useCompression(), newLocalPortForwarder(),
 * authPassword(), dll -- semuanya dari dokumentasi/README resmi sshj), TAPI
 * lingkungan penyusunan kode ini tidak punya akses internet utk menjalankan
 * Gradle build & memverifikasi langsung ke versi sshj:0.38.0 yang dipasang.
 * Kalau Android Studio melempar error "unresolved reference" di salah satu
 * pemanggilan API sshj di bawah, itu kemungkinan besar cuma beda nama
 * method/kelas antar versi -- laporkan pesan errornya, gampang diperbaiki.
 *
 * ARSITEKTUR: memakai [ConnectRelay] yang SAMA PERSIS dengan [SshTunnelManager]
 * (relay itu murni socket loopback, tidak terikat ke satu library SSH manapun)
 * -- jadi SEMUA [com.example.tunnelapp.model.ConnectionMode] (SSH biasa, SSH
 * SSL, SSH SSL+Payload, Remote Proxy, Enhanced) otomatis ikut didukung tanpa
 * kode tambahan di sini. [Socks5Server]/[UdpgwClient] juga dipakai ulang lewat
 * abstraksi [SshConnectionHandle]/[DirectTcpipForwarder] (lihat SshEngineTypes.kt).
 *
 * FITUR YANG BELUM DIPORTASI dari trilead-ssh2 (non-esensial, tunnel tetap
 * jalan penuh tanpa ini): reorder cipher cepat (preferFastCiphers), ekstraksi
 * "Server Message"/banner lewat reflection, dan pesan error per-ConnectionMode
 * yang detail (di sini digeneralisasi).
 */
class SshjTunnelManager : SshEngineHandle {

    companion object {
        private const val TAG = "SshjTunnelManager"
        private const val CONNECT_TIMEOUT_MS = 15000
    }

    /**
     * Bungkus [SSHClient] jadi [SshConnectionHandle] generik supaya
     * [Socks5Server]/[UdpgwClient] bisa dipakai ulang persis seperti di
     * [SshTunnelManager].
     *
     * sshj TIDAK punya API publik "buka satu direct-tcpip channel, kembalikan
     * stream-nya langsung" sesederhana trilead-ssh2's createLocalStreamForwarder()
     * -- API forwarding publik & stabil di sshj adalah [SSHClient.newLocalPortForwarder]
     * (gaya "ssh -L": SATU forwarder = SATU tujuan tetap, bind ke SATU
     * ServerSocket). Jadi di sini kita buat SATU ServerSocket loopback
     * sementara (port 0 = pilih otomatis) + SATU LocalPortForwarder per
     * request CONNECT/DNS-relay, lalu KITA SENDIRI connect() ke situ utk
     * memicu channel SSH direct-tcpip-nya benar-benar terbuka. Ekstra satu
     * hop loopback murni lokal (127.0.0.1, bukan jaringan asli) dibanding
     * API level-rendah -- kecil overhead-nya, tapi cuma pakai API publik sshj
     * yang terdokumentasi stabil (bukan kelas internal yang bisa beda
     * visibility antar versi).
     */
    private class SshjConnectionHandle(private val client: SSHClient) : SshConnectionHandle {
        override fun openDirectTcpip(host: String, port: Int): DirectTcpipForwarder {
            val serverSocket = ServerSocket()
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
            val localPort = serverSocket.localPort

            val params = Parameters("127.0.0.1", localPort, host, port)
            val forwarder = client.newLocalPortForwarder(params, serverSocket)
            val listenThread = Thread({
                try {
                    forwarder.listen()
                } catch (_: Exception) {
                    // Normal begitu serverSocket ditutup dari close() di bawah
                    // (accept() yang lagi menunggu langsung melempar exception).
                }
            }, "sshj-directtcpip-listen").apply { isDaemon = true; start() }

            val local = Socket()
            try {
                local.connect(InetSocketAddress("127.0.0.1", localPort), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                try { serverSocket.close() } catch (_: Exception) {}
                listenThread.interrupt()
                throw e
            }

            return object : DirectTcpipForwarder {
                override val inputStream get() = local.getInputStream()
                override val outputStream get() = local.getOutputStream()
                override fun close() {
                    try { local.close() } catch (_: Exception) {}
                    try { serverSocket.close() } catch (_: Exception) {}
                    listenThread.interrupt()
                }
            }
        }
    }

    private var sshClient: SSHClient? = null
    private var connectRelay: ConnectRelay? = null
    private var socks5Server: Socks5Server? = null
    private var udpgwClient: UdpgwClient? = null
    private var udpgwClientPort: Int = 0
    private var disconnectWatchThread: Thread? = null
    private val teardownLock = Any()

    @Throws(Exception::class)
    override fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
        protectDatagram: ((DatagramSocket) -> Boolean)?,
        performanceMode: Boolean,
        compressionEnabled: Boolean,
        onUnexpectedDisconnect: (String) -> Unit
    ) {
        val relay = ConnectRelay(config, protect)
        val relayPort = relay.start()
        connectRelay = relay

        StatusBus.start(StepId.SSH_HANDSHAKE)

        val client = SSHClient()
        // MVP: terima host key apa pun -- sama persis kebijakan
        // ServerHostKeyVerifier di SshTunnelManager (trilead).
        client.addHostKeyVerifier(PromiscuousVerifier())
        if (compressionEnabled) {
            try {
                client.useCompression()
                Log.i(TAG, "Kompresi zlib diaktifkan (engine sshj)")
            } catch (e: Exception) {
                Log.w(TAG, "Gagal aktifkan kompresi sshj, lanjut TANPA kompresi", e)
            }
        }
        // Performance Mode (TCP_NODELAY): sshj tidak punya setter publik
        // setara Connection.setTCPNoDelay milik trilead-ssh2 utk socket relay
        // lokal ini -- diabaikan dengan catatan log, BUKAN kegagalan fatal.
        if (!performanceMode) {
            Log.d(TAG, "Performance Mode 'off' tidak berefek khusus di engine sshj (tidak ada API publik setara)")
        }

        try {
            client.connect("127.0.0.1", relayPort)
        } catch (e: Exception) {
            StatusBus.fail(StepId.SSH_HANDSHAKE, explainHandshakeFailure(e, config))
            relay.stop()
            throw e
        }
        StatusBus.success(StepId.SSH_HANDSHAKE)

        StatusBus.start(StepId.SSH_AUTH)
        try {
            client.authPassword(config.username, config.password.orEmpty())
        } catch (e: Exception) {
            StatusBus.fail(StepId.SSH_AUTH, e.message ?: e.javaClass.simpleName)
            try { client.disconnect() } catch (_: Exception) {}
            relay.stop()
            throw e
        }
        StatusBus.success(StepId.SSH_AUTH)

        sshClient = client
        val connHandle = SshjConnectionHandle(client)

        StatusBus.start(StepId.SOCKS5)
        val existing = socks5Server
        try {
            if (existing != null && existing.isRunning()) {
                existing.attachConnection(connHandle)
                existing.setProtectDatagram(protectDatagram)
                StatusBus.log("SOCKS5 lokal sudah aktif di 127.0.0.1:${config.socksPort} (dipakai ulang, tidak bind ulang)")
            } else {
                val fresh = Socks5Server()
                fresh.setProtectDatagram(protectDatagram)
                fresh.start(config.socksPort)
                fresh.attachConnection(connHandle)
                socks5Server = fresh
            }
        } catch (e: Exception) {
            StatusBus.fail(StepId.SOCKS5, e.message ?: e.javaClass.simpleName)
            try { client.disconnect() } catch (_: Exception) {}
            relay.stop()
            throw e
        }
        StatusBus.success(StepId.SOCKS5)

        if (config.udpgwPort > 0) {
            val existingUdpgw = udpgwClient
            val udp = if (existingUdpgw != null && udpgwClientPort == config.udpgwPort) {
                existingUdpgw
            } else {
                existingUdpgw?.stop()
                val fresh = UdpgwClient(config.udpgwPort)
                fresh.start()
                udpgwClient = fresh
                udpgwClientPort = config.udpgwPort
                fresh
            }
            udp.attachConnection(connHandle)
            socks5Server?.setUdpgwClient(udp)
            StatusBus.log("[UDPGW] Diaktifkan, target 127.0.0.1:${config.udpgwPort} di sisi server")
        } else {
            udpgwClient?.stop()
            udpgwClient = null
            udpgwClientPort = 0
            socks5Server?.setUdpgwClient(null)
        }

        // Deteksi "tunnel mati sendiri": sshj tidak punya event listener
        // setunggal ConnectionMonitor milik trilead-ssh2 di API publiknya --
        // cara yang stabil & publik di sini adalah thread pengintai
        // isConnected() berkala.
        disconnectWatchThread = Thread({
            try {
                while (client.isConnected) {
                    Thread.sleep(2000)
                }
                onUnexpectedDisconnect("koneksi SSH (sshj) terputus")
            } catch (_: InterruptedException) {
            }
        }, "sshj-disconnect-watch").apply { isDaemon = true; start() }

        Log.i(TAG, "SSH (sshj) tersambung via relay lokal. SOCKS5 di 127.0.0.1:${config.socksPort}")
    }

    /** Versi sederhana dari SshTunnelManager.explainHandshakeFailure -- lihat catatan "FITUR YANG BELUM DIPORTASI" di atas. */
    private fun explainHandshakeFailure(e: Exception, config: ServerConfig): String {
        StatusBus.firstErrorDetail()?.let { return it }
        val rawDetail = (e.message ?: e.javaClass.simpleName)
            .replace(Regex("""127\.0\.0\.1:\d+"""), "server")
        return "Server ${config.host}:${config.port} tidak membalas dengan protokol SSH yang valid lewat engine sshj. " +
            "Kalau server butuh koneksi terenkripsi/proxy, coba mode transport lain (SSH SSL/Payload/Remote " +
            "Proxy/Enhanced) di Konfigurasi. ($rawDetail)"
    }

    override fun disconnectForReconnect() {
        synchronized(teardownLock) {
            socks5Server?.detachConnection()
            udpgwClient?.detachConnection()
            disconnectWatchThread?.interrupt()
            disconnectWatchThread = null
            try {
                sshClient?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error close SSH (sshj)", e)
            }
            try {
                connectRelay?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop relay", e)
            }
            sshClient = null
            connectRelay = null
        }
    }

    override fun disconnect() {
        synchronized(teardownLock) {
            try {
                socks5Server?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop SOCKS5", e)
            }
            try {
                udpgwClient?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop udpgw client", e)
            }
            disconnectWatchThread?.interrupt()
            disconnectWatchThread = null
            try {
                sshClient?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error close SSH (sshj)", e)
            }
            try {
                connectRelay?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop relay", e)
            }
            socks5Server = null
            udpgwClient = null
            udpgwClientPort = 0
            sshClient = null
            connectRelay = null
        }
    }

    override fun isConnected(): Boolean = sshClient?.isConnected == true && sshClient?.isAuthenticated == true
}
