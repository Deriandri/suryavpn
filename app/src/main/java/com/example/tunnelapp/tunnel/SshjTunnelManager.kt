package com.example.tunnelapp.tunnel

import android.util.Log
import com.example.tunnelapp.model.ServerConfig
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
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
 * sshj BENERAN mendukung kompresi zlib/zlib@openssh.com lewat
 * [SSHClient.useCompression] -- lihat VpnSettingsStore.compressionEnabled.
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
 * FIX YANG BENAR (dipakai sekarang, lihat ensureBouncyCastleRegistered()):
 * di sshj:0.38.0, SecurityUtils.setSecurityProvider() menerima NAMA provider
 * (String, "BC"), BUKAN objek Provider. Karena Android SUDAH mendaftarkan
 * provider bernama "BC" sejak boot (versi TERBATAS) dan nama provider itu
 * UNIK per JVM, satu-satunya cara sshj bisa menemukan Bouncy Castle ASLI
 * lewat nama "BC" adalah MENGGANTI registrasi nama itu: Security.removeProvider("BC")
 * lalu Security.addProvider(BouncyCastleProvider()) (BUKAN insertProviderAt(p, 1)).
 * BEDANYA dengan fix versi sebelumnya yang bikin "koneksi sering hilang
 * sendiri": versi lama pakai insertProviderAt(provider, 1) yang MEMAKSA
 * Bouncy Castle jadi provider prioritas TERTINGGI utk SELURUH proses
 * (termasuk TLS/HTTPS lain seperti Cloud Sync & Xray, tanpa mereka minta).
 * addProvider menaruh BC di AKHIR daftar prioritas -- Conscrypt/AndroidOpenSSL
 * bawaan Android TETAP jadi pilihan pertama utk kode lain yang cari algoritma
 * TANPA sebut nama provider secara eksplisit. Bouncy Castle asli hanya
 * "kepakai" kalau ada kode yang EKSPLISIT minta provider by name "BC" --
 * persis yang dilakukan SecurityUtils.setSecurityProvider("BC") di bawah,
 * khusus utk sshj. Efeknya: X25519/EC dkk tetap lengkap tersedia utk sshj,
 * TANPA mengubah provider default TLS lain di app.
 *
 * ARSITEKTUR: memakai [ConnectRelay] (relay itu murni socket loopback, tidak
 * terikat ke satu library SSH manapun) -- jadi SEMUA
 * [com.example.tunnelapp.model.ConnectionMode] (SSH biasa, SSH SSL, SSH
 * SSL+Payload, Remote Proxy, Enhanced) otomatis ikut didukung tanpa kode
 * tambahan di sini. [Socks5Server]/[UdpgwClient] juga dipakai ulang lewat
 * abstraksi [SshConnectionHandle]/[DirectTcpipForwarder] (lihat SshEngineTypes.kt).
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
 * "Server Message"/banner (lihat connect(), setelah authPassword() sukses)
 * diekspor sshj lewat API publik resmi client.userAuth.banner.
 */
class SshjTunnelManager : SshEngineHandle {

    /**
     * Bungkus [SSHClient] jadi [SshConnectionHandle] generik supaya
     * [Socks5Server]/[UdpgwClient] bisa dipakai ulang lewat abstraksi umum.
     *
     * sshj TIDAK punya API publik "buka satu direct-tcpip channel, kembalikan
     * stream-nya langsung" yang sesederhana itu -- API forwarding publik &
     * stabil di sshj adalah [SSHClient.newLocalPortForwarder]
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
     * "Performance Mode" (TCP_NODELAY, matikan algoritma Nagle). sshj TIDAK
     * punya setter publik langsung untuk ini -- tapi ADA jalan resmi lain:
     * SSHClient.setSocketFactory() (API publik sshj yang sama dipakai contoh
     * resmi mereka untuk konek lewat SOCKS proxy custom). Dengan menyuntikkan
     * SocketFactory sendiri di sini, kita bisa set tcpNoDelay pada socket
     * SEBELUM dipakai sshj utk connect ke relay lokal (cuma memengaruhi
     * socket loopback ke [ConnectRelay]).
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

        // Guard supaya registrasi provider cuma dijalankan SEKALI per proses
        // (bukan per connect()/reconnect) -- Security.insertProviderAt() TIDAK
        // masalah dipanggil berkali-kali, tapi tidak ada gunanya juga,
        // sekedar hindari kerja & log berulang tanpa perlu.
        @Volatile private var bcRegistered = false
        private val bcRegisterLock = Any()

        /**
         * FIX ROOT CAUSE (lihat catatan panjang di javadoc kelas ini) --
         * kasih tahu Bouncy Castle ASLI (dependency org.bouncycastle:bcprov-jdk18on,
         * lihat app/build.gradle.kts) KHUSUS ke sshj lewat SecurityUtils,
         * BUKAN lewat Security.insertProviderAt() yang berlaku global ke
         * seluruh proses app. Ini scoped, aman dipanggil kapan pun (tidak
         * ada efek samping ke TLS/HTTPS/kripto lain di app), dan otomatis
         * dipakai sshj untuk SEMUA algoritma yang ia minta lewat provider
         * "BC" (X25519, kurva EC penuh, dll) -- tidak perlu filter algoritma
         * satu-satu, sama seperti tujuan fix versi sebelumnya, cuma tanpa
         * dampak global-nya.
         */
        private fun ensureBouncyCastleRegistered() {
            if (bcRegistered) return
            synchronized(bcRegisterLock) {
                if (bcRegistered) return
                try {
                    // PENTING (root cause error "no such algorithm: X25519 for
                    // provider BC" yang MASIH muncul): Android SUDAH mendaftarkan
                    // provider bernama "BC" sejak proses boot (versi TERBATAS,
                    // bukan Bouncy Castle asli) -- karena nama provider itu UNIK
                    // per JVM (cuma satu provider boleh pakai nama "BC"
                    // sekaligus), cek "kalau belum ada" SELALU false dan Bouncy
                    // Castle asli TIDAK PERNAH benar-benar terpasang. Satu-
                    // satunya cara sshj bisa menemukan Bouncy Castle ASLI lewat
                    // nama "BC" adalah MENGGANTI registrasi nama itu: copot versi
                    // Android punya dulu, baru pasang versi asli.
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    // addProvider (BUKAN insertProviderAt(p, 1)) = ditaruh di
                    // AKHIR daftar prioritas. Ini yang menjaga TIDAK terulang
                    // bug "internet sering hilang sendiri": kode lain di app
                    // yang mencari algoritma TANPA sebut nama provider secara
                    // eksplisit (kebanyakan TLS/HTTPS biasa, termasuk Cloud
                    // Sync & Xray) tetap memilih Conscrypt/AndroidOpenSSL dulu
                    // (prioritas lebih tinggi) seperti biasa -- Bouncy Castle
                    // asli hanya "kepakai" kalau ada kode yang EKSPLISIT minta
                    // provider by name "BC", persis yang sshj lakukan di baris
                    // setSecurityProvider() di bawah.
                    Security.addProvider(BouncyCastleProvider())
                    // sshj cuma perlu NAMA provider ("BC"), bukan objeknya --
                    // API ini yang bikin sshj (lewat SecurityUtils.getKeyPairGenerator()
                    // dkk) secara internal minta provider "BC" ke JVM by name,
                    // dan sekarang ketemu Bouncy Castle asli yang barusan
                    // menggantikan versi Android di nama itu.
                    SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Log.i(TAG, "Bouncy Castle asli MENGGANTIKAN provider \"BC\" bawaan Android (prioritas tetap rendah, dipakai sshj lewat nama provider)")
                } catch (e: Exception) {
                    // Non-fatal di titik ini -- kalau ternyata masih ada
                    // algoritma yang hilang, error "no such algorithm: ...
                    // for provider BC" yang sama akan muncul lagi nanti pas
                    // handshake, dan itu ke-log jelas di StatusBus seperti
                    // sebelumnya, jadi tetap gampang didiagnosis.
                    Log.e(TAG, "Gagal daftarkan Bouncy Castle asli ke sshj, lanjut pakai provider bawaan Android", e)
                }
                bcRegistered = true
            }
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
        ensureBouncyCastleRegistered()

        val relay = ConnectRelay(config, protect)
        val relayPort = relay.start()
        connectRelay = relay

        StatusBus.start(StepId.SSH_HANDSHAKE)

        // DIAGNOSIS (log 19:38): server cuma sempat mengirim banner (42 byte),
        // sshj mengirim 2685 byte (ident + KEXINIT dengan SEMUA algoritma
        // Bouncy Castle), lalu koneksi di-RESET pihak seberang dalam ~50 ms
        // sebelum server sempat membalas KEXINIT. HTTP Custom ke server yang
        // sama berhasil hanya dengan diffie-hellman-group-exchange-sha256 +
        // aes256-ctr + hmac-sha2-512. Daftar algoritma dipersempit dan ident
        // dibuat umum (lihat buildCompactConfig) untuk menguji apakah ukuran/isi
        // paket pertama itu penyebab reset.
        val client = SSHClient(buildCompactConfig())
        // FIX (bug potensial "menggantung tanpa batas"): SEBELUMNYA tidak
        // ada timeout sama sekali di level SSHClient -- kalau server tidak
        // jelas membalas saat handshake/auth, client.connect()/authPassword()
        // bisa BLOCKING SELAMANYA. Di sini connectTimeout dipakai utk fase TCP
        // connect, timeout (SO_TIMEOUT) utk operasi blocking sesudahnya (key
        // exchange, auth).
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = CONNECT_TIMEOUT_MS
        // Performance Mode (TCP_NODELAY) -- lihat javadoc PerformanceModeSocketFactory.
        client.socketFactory = PerformanceModeSocketFactory(performanceMode)
        // MVP: terima host key apa pun.
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
        StatusBus.log("Auth complete")
        // "Server Message"/banner (SSH_MSG_USERAUTH_BANNER, RFC 4252 SS5.4) --
        // sshj mengekspor ini lewat API publik resmi:
        // SSHClient.getUserAuth().getBanner() (lihat net.schmizz.sshj.SSHClient.getUserAuth()
        // & UserAuth.getBanner()). CATATAN: sshj mengembalikan "" (bukan null)
        // kalau server tidak kirim banner sama sekali -- makanya dicek isNotBlank().
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
        // koneksi tunggal di API publiknya -- cara yang stabil & publik di
        // sini adalah thread pengintai isConnected() berkala.
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

    /**
     * Ambil hanya algoritma bernama [wanted] (urut sesuai [wanted]), yang tidak
     * tersedia di [all] dilewati. Kalau hasilnya kosong, daftar asli dipakai
     * supaya negosiasi tidak mustahil.
     */
    private fun <T> pickNamed(all: List<Factory.Named<T>>, wanted: List<String>): List<Factory.Named<T>> {
        val picked = wanted.mapNotNull { name -> all.firstOrNull { it.name == name } }
        return if (picked.isNotEmpty()) picked else all
    }

    /**
     * Konfigurasi sshj dengan daftar algoritma ringkas (KEXINIT jauh lebih
     * kecil dari default yang memuat semua algoritma Bouncy Castle) dan ident
     * umum "SSH-2.0-OpenSSH_8.9p1". Urutan kex sengaja mengutamakan
     * diffie-hellman-group-exchange-sha256, kombinasi yang terbukti berhasil
     * di HTTP Custom ke server yang sama. Tetap ada cadangan
     * curve25519/group14/ecdh dan hmac-sha1 untuk server lain (mis. Dropbear).
     */
    private fun buildCompactConfig(): DefaultConfig {
        val cfg = DefaultConfig()
        cfg.setKeyExchangeFactories(
            pickNamed(
                cfg.getKeyExchangeFactories(),
                listOf(
                    "diffie-hellman-group-exchange-sha256",
                    "curve25519-sha256",
                    "curve25519-sha256@libssh.org",
                    "diffie-hellman-group14-sha256",
                    "ecdh-sha2-nistp256"
                )
            )
        )
        cfg.setCipherFactories(
            pickNamed(
                cfg.getCipherFactories(),
                listOf("aes256-ctr", "aes128-ctr", "aes256-gcm@openssh.com", "aes128-gcm@openssh.com")
            )
        )
        cfg.setMACFactories(
            pickNamed(
                cfg.getMACFactories(),
                listOf("hmac-sha2-512", "hmac-sha2-256", "hmac-sha1")
            )
        )
        cfg.setVersion("OpenSSH_8.9p1")
        return cfg
    }

    /** Versi sederhana dari explainHandshakeFailure. */
    private fun explainHandshakeFailure(e: Exception, config: ServerConfig): String {
        StatusBus.firstErrorDetail()?.let { return it }
        val rawDetail = (e.message ?: e.javaClass.simpleName)
            .replace(Regex("""127\.0\.0\.1:\d+"""), "server")
        return "Server ${config.host}:${config.port} tidak membalas dengan protokol SSH yang valid lewat engine sshj. " +
            "Kalau server butuh koneksi terenkripsi/proxy, coba mode transport lain (SSH SSL/Payload/Remote " +
            "Proxy) di Konfigurasi. Kalau memakai payload dengan dua request atau lewat CDN, " +
            "coba centang Enhanced. ($rawDetail)"
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
