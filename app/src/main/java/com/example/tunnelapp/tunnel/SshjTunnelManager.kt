package com.example.tunnelapp.tunnel

import android.util.Log
import com.example.tunnelapp.model.ServerConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.Security
import javax.net.SocketFactory

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
 * CATATAN FIX NYATA -- ROOT CAUSE, BUKAN TAMBAL SATU-SATU (laporan user,
 * dua error berturutan: "no such algorithm: X25519 for provider BC", lalu
 * SETELAH X25519 dihindari, "no such algorithm: EC for provider BC" muncul
 * juga): provider JCE bernama "BC" BAWAAN ANDROID ternyata memang sangat
 * terbatas (bukan Bouncy Castle asli/lengkap seperti di JVM desktop) --
 * banyak algoritma modern (X25519, kurva EC penuh, dll) memang tidak ada di
 * situ.
 *
 * PERBAIKAN (laporan user, "pakai engine sshj koneksi internet sering hilang
 * sendiri"): fix SEBELUMNYA di sini salah -- MENIMPA provider "BC" secara
 * GLOBAL lewat Security.removeProvider("BC") + Security.insertProviderAt(...,
 * 1), yang efeknya BUKAN cuma buat sshj, tapi SELURUH proses app (semua
 * TLS/HTTPS lain: Cloud Sync, engine Xray, dll) ikut kepindah ke Bouncy
 * Castle murni-Java di posisi prioritas TERTINGGI begitu sshj dipakai SEKALI
 * saja -- dan TIDAK PERNAH di-reset lagi selama proses app hidup. Bouncy
 * Castle murni tidak seterintegrasi Conscrypt/AndroidOpenSSL dengan network
 * stack Android, jadi bisa bikin koneksi TLS LAIN (bukan cuma tunnel SSH-nya)
 * ikut melambat/gagal sesekali -- persis gejala "internet sering hilang
 * sendiri" yang dilaporkan, karena dampaknya app-wide, bukan cuma pas tunnel
 * sshj aktif.
 *
 * KOREKSI KEDUA (gagal compile, laporan user): perbaikan yang SEMPAT ditulis
 * di sini sesudah paragraf di atas mengasumsikan sshj punya API
 * `SecurityUtils.setSecurityProvider(Provider)` (versi Provider OBJECT,
 * scoped khusus ke sshj tanpa Security.insertProviderAt() sama sekali) --
 * TERNYATA SALAH utk `com.hierynomus:sshj:0.38.0` yang benar-benar dipasang
 * (lihat app/build.gradle.kts): compiler bilang jelas overload yang ADA cuma
 * `setSecurityProvider(String)` (by NAME, bukan instance), error "Type
 * mismatch: inferred type is BouncyCastleProvider but String! was expected".
 * Saya tidak bisa verifikasi klaim versi sebelumnya krn sandbox saya tidak
 * ada akses jaringan/Gradle -- ternyata memang keliru.
 *
 * KENAPA "SCOPED SEPENUHNYA" TIDAK MUNGKIN DENGAN API STRING INI: sshj cuma
 * simpan NAMA yang diberikan lalu memanggil `Security.getProvider(nama)` --
 * ini SELALU baca DAFTAR GLOBAL JVM, tidak ada jalur lain. Supaya nama "BC"
 * itu resolve ke Bouncy Castle ASLI (bukan versi terbatas bawaan Android),
 * Bouncy Castle asli MEMANG HARUS terdaftar di slot global bernama "BC" --
 * tidak ada API publik utk "ganti nama" instance BouncyCastleProvider (nama
 * "BC" hardcoded di constructor-nya) supaya bisa didaftarkan di bawah nama
 * lain yang tidak bentrok.
 *
 * FIX YANG DIPAKAI SEKARANG (mitigasi, BUKAN penghilangan total efek
 * samping): swap masuk Bouncy Castle asli TEPAT SEBELUM `client.connect()`
 * (fase handshake+key-exchange, satu-satunya titik X25519/EC dibutuhkan),
 * lalu SELALU swap KEMBALI ke provider Android original SEGERA setelah itu
 * -- baik sukses (segera setelah auth sukses) MAUPUN gagal (di catch block
 * connect/auth) -- lihat [swapInRealBouncyCastle]/[restoreOriginalBouncyCastle].
 * Ini mempersempit jendela dampak global dari "selama proses app hidup"
 * (bug lama) jadi cuma beberapa detik per percobaan connect/reconnect, TAPI
 * TIDAK NOL -- kalau ada TLS lain (Cloud Sync, dll) yang KEBETULAN
 * melakukan operasi kripto lewat provider "BC" PERSIS di detik yang sama,
 * resiko lama itu (walau jauh lebih kecil jendelanya) secara teori masih
 * bisa terjadi. Kalau gejala "internet lain ikut kebagian delay" muncul
 * lagi meski sudah jarang, laporkan -- kemungkinan solusi berikutnya adalah
 * subclass Provider yang menyalin Provider.Service satu-satu dari
 * BouncyCastleProvider ke provider baru bernama unik (mis. "BCReal"), TAPI
 * itu belum saya implementasikan di sini krn butuh verifikasi lebih dalam
 * (resiko ada Service BC yang perilakunya bergantung ke identitas provider
 * aslinya) yang tidak bisa saya lakukan tanpa environment build+test nyata.
 *
 * ARSITEKTUR: memakai [ConnectRelay] yang SAMA PERSIS dengan [SshTunnelManager]
 * (relay itu murni socket loopback, tidak terikat ke satu library SSH manapun)
 * -- jadi SEMUA [com.example.tunnelapp.model.ConnectionMode] (SSH biasa, SSH
 * SSL, SSH SSL+Payload, Remote Proxy, Enhanced) otomatis ikut didukung tanpa
 * kode tambahan di sini. [Socks5Server]/[UdpgwClient] juga dipakai ulang lewat
 * abstraksi [SshConnectionHandle]/[DirectTcpipForwarder] (lihat SshEngineTypes.kt).
 *
 * FITUR YANG BELUM DIPORTASI dari trilead-ssh2 (non-esensial, tunnel tetap
 * jalan penuh tanpa ini): reorder cipher cepat (preferFastCiphers), dan pesan
 * error per-ConnectionMode yang detail (di sini digeneralisasi).
 *
 * SUDAH DIPERBAIKI (audit "apakah sudah maksimal", permintaan user):
 * (1) Performance Mode (TCP_NODELAY) SEKARANG ikut diimplementasikan lewat
 *     [PerformanceModeSocketFactory] + SSHClient.setSocketFactory() --
 *     sebelumnya diabaikan total karena dikira tidak ada API publik setara.
 * (2) client.connectTimeout/client.timeout SEKARANG diisi CONNECT_TIMEOUT_MS
 *     selama fase handshake+auth (lalu di-reset ke 0/tanpa batas begitu auth
 *     sukses) -- sebelumnya TIDAK ADA timeout SSH-level sama sekali, jadi
 *     connect()/authPassword() berisiko menggantung tanpa batas kalau server
 *     tidak jelas membalas.
 *
 * "Server Message"/banner SUDAH diportasi (lihat connect(), setelah
 * authPassword() sukses) -- BEDA dengan trilead yang butuh reflection, sshj
 * mengekspornya lewat API publik resmi client.userAuth.banner.
 */
class SshjTunnelManager : SshEngineHandle {

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

    /**
     * PARITAS FITUR (sebelumnya belum diportasi dari [SshTunnelManager]):
     * "Performance Mode" (TCP_NODELAY, matikan algoritma Nagle). sshj TIDAK
     * punya setter publik setara Connection.setTCPNoDelay milik trilead-ssh2
     * -- tapi ADA jalan resmi lain: SSHClient.setSocketFactory() (API
     * publik sshj yang sama dipakai contoh resmi mereka untuk konek lewat
     * SOCKS proxy custom). Dengan menyuntikkan SocketFactory sendiri di
     * sini, kita bisa set tcpNoDelay pada socket SEBELUM dipakai sshj utk
     * connect ke relay lokal -- efeknya identik dengan Connection.setTCPNoDelay
     * di versi trilead (cuma memengaruhi socket loopback ke [ConnectRelay],
     * sama seperti catatan di SshTunnelManager.connect()).
     *
     * CATATAN VERIFIKASI (sama seperti javadoc kelas ini): ditulis dari API
     * publik javax.net.SocketFactory (bagian dari JDK/Android sendiri, jadi
     * signature-nya pasti stabil) + SSHClient.setSocketFactory() (API sshj
     * yang didokumentasikan resmi) -- kalau ternyata nama methodnya beda di
     * versi sshj:0.38.0 yang terpasang, laporkan error compile-nya.
     */
    private class PerformanceModeSocketFactory(private val tcpNoDelay: Boolean) : SocketFactory() {
        private fun tuned(socket: Socket): Socket = socket.apply {
            try {
                setTcpNoDelay(tcpNoDelay)
            } catch (e: Exception) {
                Log.w("SshjTunnelManager", "Gagal atur TCP_NODELAY (Performance Mode=$tcpNoDelay), lanjut pakai default", e)
            }
        }

        override fun createSocket(): Socket = tuned(Socket())

        override fun createSocket(host: String?, port: Int): Socket =
            tuned(Socket()).apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            tuned(Socket()).apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            tuned(Socket()).apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            tuned(Socket()).apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            }
    }

    private var sshClient: SSHClient? = null
    private var connectRelay: ConnectRelay? = null
    private var socks5Server: Socks5Server? = null
    private var udpgwClient: UdpgwClient? = null
    private var udpgwClientPort: Int = 0
    private var disconnectWatchThread: Thread? = null
    private val teardownLock = Any()

    companion object {
        private const val TAG = "SshjTunnelManager"
        private const val CONNECT_TIMEOUT_MS = 15000

        // Provider "BC" Android original -- disimpan sementara selama jendela
        // handshake sshj (lihat swapInRealBouncyCastle/restoreOriginalBouncyCastle),
        // supaya bisa dikembalikan persis seperti semula sesudahnya.
        @Volatile private var originalBcProvider: java.security.Provider? = null
        private val bcRegisterLock = Any()
        private const val BC_PROVIDER_NAME = "BC"

        /**
         * Swap MASUK Bouncy Castle asli ke slot global "BC", SEMENTARA --
         * lihat catatan panjang di javadoc kelas ini kenapa ini tidak bisa
         * dibuat scoped 100% dengan API sshj yang tersedia. WAJIB dipasangkan
         * dengan [restoreOriginalBouncyCastle] di SETIAP jalur keluar (sukses
         * maupun exception) -- kalau tidak, provider Android original hilang
         * permanen dan bug lama ("internet sering hilang sendiri") kembali.
         */
        private fun swapInRealBouncyCastle() {
            synchronized(bcRegisterLock) {
                originalBcProvider = Security.getProvider(BC_PROVIDER_NAME)
                Security.removeProvider(BC_PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                SecurityUtils.setSecurityProvider(BC_PROVIDER_NAME)
            }
            Log.i(TAG, "Bouncy Castle asli dipasang SEMENTARA (khusus jendela handshake sshj)")
        }

        /**
         * Kembalikan provider "BC" ke punya Android semula -- lihat
         * [swapInRealBouncyCastle]. Aman dipanggil berkali-kali /
         * meski [swapInRealBouncyCastle] belum pernah sukses dipanggil
         * (originalBcProvider null -> insertProviderAt di-skip).
         */
        private fun restoreOriginalBouncyCastle() {
            synchronized(bcRegisterLock) {
                Security.removeProvider(BC_PROVIDER_NAME)
                originalBcProvider?.let { Security.insertProviderAt(it, 1) }
                originalBcProvider = null
            }
            Log.i(TAG, "Provider BC dikembalikan ke bawaan Android (jendela handshake sshj selesai)")
        }
    }

    @Throws(Exception::class)
    override fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
        protectDatagram: ((DatagramSocket) -> Boolean)?,
        performanceMode: Boolean,
        compressionEnabled: Boolean,
        onUnexpectedDisconnect: (String) -> Unit
    ) {
        swapInRealBouncyCastle()

        val relay = ConnectRelay(config, protect)
        val relayPort = relay.start()
        connectRelay = relay

        StatusBus.start(StepId.SSH_HANDSHAKE)

        val client = SSHClient()
        // FIX (bug potensial "menggantung tanpa batas"): SEBELUMNYA tidak
        // ada timeout sama sekali di level SSHClient -- kalau server tidak
        // jelas membalas saat handshake/auth, client.connect()/authPassword()
        // bisa BLOCKING SELAMANYA (beda dari trilead-ssh2 yang selalu diberi
        // CONNECT_TIMEOUT_MS eksplisit lewat conn.connect(...)). Di sini
        // disamakan: connectTimeout utk fase TCP connect, timeout (SO_TIMEOUT)
        // utk operasi blocking sesudahnya (key exchange, auth).
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = CONNECT_TIMEOUT_MS
        // Performance Mode (TCP_NODELAY) -- lihat javadoc PerformanceModeSocketFactory.
        client.socketFactory = PerformanceModeSocketFactory(performanceMode)
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

        try {
            client.connect("127.0.0.1", relayPort)
        } catch (e: Exception) {
            StatusBus.fail(StepId.SSH_HANDSHAKE, explainHandshakeFailure(e, config))
            restoreOriginalBouncyCastle()
            relay.stop()
            throw e
        }
        StatusBus.success(StepId.SSH_HANDSHAKE)

        StatusBus.start(StepId.SSH_AUTH)
        try {
            client.authPassword(config.username, config.password.orEmpty())
        } catch (e: Exception) {
            StatusBus.fail(StepId.SSH_AUTH, e.message ?: e.javaClass.simpleName)
            restoreOriginalBouncyCastle()
            try { client.disconnect() } catch (_: Exception) {}
            relay.stop()
            throw e
        }
        // Key exchange (satu-satunya titik X25519/EC dari Bouncy Castle asli
        // dibutuhkan) sudah lewat begitu authPassword() sukses -- aman
        // dikembalikan ke provider Android original SEKARANG, sebelum trafik
        // tunnel asli mulai lewat. Lihat catatan panjang di javadoc kelas ini.
        restoreOriginalBouncyCastle()
        StatusBus.log("Auth complete")
        // "Server Message"/banner (SSH_MSG_USERAUTH_BANNER, RFC 4252 SS5.4) --
        // beda dengan trilead-ssh2 (lihat SshTunnelManager.extractServerBanner()),
        // sshj MENGEKSPOR ini lewat API publik resmi: SSHClient.getUserAuth().getBanner()
        // (lihat net.schmizz.sshj.SSHClient.getUserAuth() & UserAuth.getBanner()),
        // jadi TIDAK perlu reflection ke field internal seperti versi trilead.
        // CATATAN: sshj mengembalikan "" (bukan null) kalau server tidak kirim
        // banner sama sekali -- makanya dicek isNotBlank(), sama persis
        // perilaku extractServerBanner() versi trilead.
        client.userAuth.banner?.takeIf { it.isNotBlank() }?.let { banner ->
            StatusBus.log("Server Message:\n$banner")
        }
        StatusBus.success(StepId.SSH_AUTH)
        // PENTING (cegah bug "tunnel idle 15 detik lalu putus sendiri"):
        // client.timeout di atas cuma dimaksudkan utk fase handshake/auth --
        // WAJIB dikembalikan ke 0 (tanpa batas) sebelum dipakai utk trafik
        // tunnel asli, sama persis alasannya dengan HANDSHAKE_READ_TIMEOUT_MS
        // di ConnectRelay. Tanpa ini, sesi yang idle (tidak ada trafik) lebih
        // dari CONNECT_TIMEOUT_MS akan salah dianggap putus.
        client.timeout = 0

        // FIX (laporan user, "pakai engine sshj koneksi internet sering
        // hilang sendiri"): SEBELUMNYA tidak ada keep-alive level protokol
        // SSH sama sekali di sini -- selama tunnel idle (tidak ada trafik),
        // sshj TIDAK PERNAH kirim apa pun ke server sampai probe watchdog app
        // (MyVpnService, ~tiap 16 menit) lewat. Di jaringan seluler yang NAT-
        // nya sering drop koneksi TCP idle LEBIH CEPAT dari 16 menit, tunnel
        // bisa mati diam-diam di tengah jeda itu. keepAliveInterval di bawah
        // bikin sshj kirim SSH_MSG_GLOBAL_REQUEST "keepalive" kecil tiap 30
        // detik selama idle -- selain menjaga NAT tetap terbuka, kalau
        // ternyata koneksi sudah putus, sshj juga lebih cepat sadar (lewat
        // exception di reader thread -> disconnectWatchThread di bawah
        // langsung menangkapnya), bukan menunggu sampai watchdog jarak jauh
        // berikutnya.
        client.connection.keepAlive.keepAliveInterval = 30

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
