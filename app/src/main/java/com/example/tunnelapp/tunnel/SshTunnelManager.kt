package com.example.tunnelapp.tunnel

import android.util.Log
import com.example.tunnelapp.model.ConnectionMode
import com.example.tunnelapp.model.ServerConfig
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionMonitor
import com.trilead.ssh2.ServerHostKeyVerifier
import java.io.IOException
import java.net.Socket

/**
 * Mengelola koneksi SSH memakai trilead-ssh2 -- engine SSH yang SAMA dengan
 * yang dipakai HTTP Custom/HTTP Injector (dikonfirmasi dari file lisensi
 * open source mereka).
 *
 * trilead-ssh2 versi publik (Maven Central) TIDAK menyediakan:
 *  (a) hook resmi untuk menyuntikkan socket yang sudah di-protect() sebelum
 *      connect ke server -- jadi kita buat ConnectRelay: relay loopback lokal
 *      yang benar-benar membuka & protect() koneksi ke server asli (lewat
 *      proxy kalau perlu). trilead-ssh2 tinggal connect ke relay ini
 *      (127.0.0.1), otomatis tidak pernah kena routing TUN karena loopback.
 *  (b) dynamic port forwarding bawaan (beda dari JSch) -- jadi kita
 *      implementasikan sendiri Socks5Server, menggantikan peran jsocks di
 *      HTTP Custom, yang meneruskan tiap koneksi lewat
 *      Connection.createLocalStreamForwarder() (API resmi trilead-ssh2).
 *
 * Jadi: mesin SSH (protokol, crypto, autentikasi) 100% trilead-ssh2, sama
 * seperti HTTP Custom. Bagian "lem" di sekitarnya (relay + SOCKS5 server)
 * ditulis sendiri karena API pasangannya (jsocks) terlalu tidak terdokumentasi
 * untuk dipastikan benar tanpa risiko salah tebak.
 *
 * PERUBAHAN ARSITEKTUR (meniru DarkTunnel/HTTP Custom -- FIX bug "bind
 * failed: EADDRINUSE" yang berulang tiap reconnect): [socks5Server] SEKARANG
 * PERSISTEN untuk seluruh umur satu sesi VPN. Dulu instance Socks5Server
 * baru dibuat & bind() ulang di SETIAP kali connect() dipanggil (termasuk
 * tiap reconnect) -- itu membuka celah race di mana port lama belum
 * benar-benar dilepas OS saat port baru dicoba di-bind lagi, apalagi kalau
 * teardown sebelumnya (connection.close() ke jaringan yang sedang tidak
 * stabil) lambat. Sekarang bind() ke config.socksPort cuma pernah terjadi
 * SEKALI per sesi (connect() pertama); reconnect berikutnya cuma mengganti
 * referensi Connection yang dipakai server itu lewat
 * [Socks5Server.attachConnection]/[Socks5Server.detachConnection] -- port
 * lokal tidak pernah disentuh lagi sampai sesi VPN benar-benar berakhir
 * ([disconnect]) atau hard reset. Kelas bug EADDRINUSE saat reconnect jadi
 * tidak mungkin terjadi lagi secara struktural.
 */
class SshTunnelManager {

    companion object {
        private const val TAG = "SshTunnelManager"
        private const val CONNECT_TIMEOUT_MS = 15000

        // FITUR BARU (maksimalkan kecepatan): daftar cipher yang punya
        // percepatan hardware di hampir semua HP modern (AES-NI/ARMv8 Crypto
        // Extensions) -- nama-nama standar RFC/OpenSSH, sengaja HANYA
        // dipakai sebagai kunci "naikkan ke depan kalau ada", bukan daftar
        // pengganti (lihat catatan panjang di connect() soal kenapa ini
        // aman untuk kompatibilitas).
        private val FAST_CIPHER_PRIORITY = listOf(
            "aes128-gcm@openssh.com",
            "aes256-gcm@openssh.com",
            "chacha20-poly1305@openssh.com",
            "aes128-ctr",
            "aes192-ctr",
            "aes256-ctr"
        )

        /**
         * Ambil daftar LENGKAP cipher yang didukung [conn] (client-side,
         * bukan dipersempit berdasarkan server), lalu urutkan ulang supaya
         * cipher di [FAST_CIPHER_PRIORITY] naik ke depan -- SEMUA cipher
         * lain yang tadinya didukung tetap ikut dikirim di posisi
         * berikutnya, urutan relatifnya sendiri dipertahankan. Diterapkan
         * ke DUA arah (client->server & server->client) karena keduanya
         * dinegosiasikan terpisah oleh SSH.
         */
        private fun preferFastCiphers(conn: Connection) {
            // PENTING (sudah diverifikasi langsung ke source resmi
            // jenkinsci/trilead-ssh2 di GitHub, BUKAN tebakan): method-nya
            // bernama setClient2ServerCiphers()/setServer2ClientCiphers()
            // (pakai "2", warisan penamaan dari ganymed-ssh2) -- BUKAN
            // setClientToServerCiphers() seperti asumsi awal yang salah.
            // getAvailableCiphers() sendiri memang STATIC di class Connection.
            val available = Connection.getAvailableCiphers()?.toList().orEmpty()
            if (available.isEmpty()) return
            val reordered = (FAST_CIPHER_PRIORITY.filter { it in available } +
                available.filter { it !in FAST_CIPHER_PRIORITY }).toTypedArray()
            conn.setClient2ServerCiphers(reordered)
            conn.setServer2ClientCiphers(reordered)
        }
    }

    private var connection: Connection? = null
    private var connectRelay: ConnectRelay? = null

    // Lihat catatan arsitektur di atas -- field ini SENGAJA bukan `val` yang
    // dibuat di connect(): dipertahankan lintas reconnect() lewat instance
    // yang sama, hanya benar-benar di-null-kan (port dilepas) di
    // [disconnect] (stop total) atau saat gagal connect AWAL (belum pernah
    // ada sesi yang perlu dipertahankan).
    private var socks5Server: Socks5Server? = null

    // Semua operasi teardown (disconnectForReconnect/disconnect) dikunci di
    // sini supaya tidak ada dua thread yang membongkar connection/relay yang
    // sama secara bersamaan (mis. handleTunnelDeath() dari ConnectionMonitor
    // trilead-ssh2 vs stopVpn() manual yang datang nyaris berbarengan).
    // synchronized (BUKAN compareAndSet-lalu-diamkan seperti sebelumnya)
    // supaya caller kedua tetap MENUNGGU teardown pertama selesai, bukan
    // diam-diam di-skip -- skip diam-diam itulah yang dulu bisa
    // meninggalkan connection/relay lama setengah-tertutup.
    private val teardownLock = Any()

    @Throws(Exception::class)
    fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
        // FIX DNS timeout di server yg firewall port 53 outbound -- lihat
        // catatan lengkap di Socks5Server.DEVICE_DNS_TIMEOUT_MS. Overload
        // protect() terpisah karena VpnService.protect() punya versi
        // Socket & DatagramSocket sendiri-sendiri, keduanya perlu di-hook.
        // Default no-op (selalu gagal protect) supaya caller lama yang
        // belum di-update tetap kompilasi & tetap fallback otomatis ke
        // jalur SSH lama, tidak ada breaking change.
        protectDatagram: (java.net.DatagramSocket) -> Boolean = { false },
        onUnexpectedDisconnect: (String) -> Unit = {}
    ) {
        // (a) Relay lokal -- trilead-ssh2 akan connect ke sini, BUKAN
        // langsung ke server asli. Relay inilah yang benar-benar membuka
        // koneksi ke server (dengan protect(), proxy, payload, dan TLS kalau perlu).
        val relay = ConnectRelay(config, protect)
        val relayPort = relay.start()
        connectRelay = relay

        StatusBus.start(StepId.SSH_HANDSHAKE)
        val conn = Connection("127.0.0.1", relayPort)

        // REVERT (laporan user: "terhubung tapi internet tidak jalan" di mode
        // SSH, tepat setelah fitur prioritas cipher cepat ini ditambahkan):
        // handshake SSH tetap sukses (status "Terhubung" muncul), tapi
        // kemungkinan implementasi cipher AES-GCM/CTR di fork trilead-ssh2
        // ini punya bug halus yang bikin data SETELAH handshake gagal
        // diverifikasi/rusak -- persis gejala yang dilaporkan. Karena saya
        // tidak punya server/device nyata untuk mengetes langsung dampak
        // reorder cipher ini, DIMATIKAN DULU (bukan dihapus total -- kode
        // preferFastCiphers() masih ada di companion object kalau nanti mau
        // dicoba lagi setelah diverifikasi lebih hati-hati, mis. cipher demi
        // cipher satu-satu) sampai ada cara mengetesnya dengan aman.
        // try { preferFastCiphers(conn) } catch (e: Exception) {
        //     Log.w(TAG, "Gagal atur prioritas cipher cepat, pakai urutan default library", e)
        // }

        try {
            conn.connect(
                object : ServerHostKeyVerifier {
                    // MVP: terima host key apa pun.
                    // TODO production: simpan & verifikasi host key yang benar.
                    override fun verifyServerHostKey(
                        hostname: String?,
                        port: Int,
                        serverHostKeyAlgorithm: String?,
                        serverHostKey: ByteArray?
                    ) = true
                },
                CONNECT_TIMEOUT_MS,
                CONNECT_TIMEOUT_MS
            )
        } catch (e: Exception) {
            StatusBus.fail(StepId.SSH_HANDSHAKE, explainHandshakeFailure(e, config))
            relay.stop()
            throw e
        }
        StatusBus.success(StepId.SSH_HANDSHAKE)

        StatusBus.start(StepId.SSH_AUTH)
        val authOk = try {
            conn.authenticateWithPassword(config.username, config.password.orEmpty())
        } catch (e: Exception) {
            StatusBus.fail(StepId.SSH_AUTH, e.message ?: e.javaClass.simpleName)
            conn.close()
            relay.stop()
            throw e
        }
        if (!authOk) {
            val msg = "Autentikasi SSH gagal (cek username/password)"
            StatusBus.fail(StepId.SSH_AUTH, msg)
            conn.close()
            relay.stop()
            throw IOException(msg)
        }
        StatusBus.log("Auth complete")
        // PENTING (fitur, bukan bug fix): "Server Message" ala DarkTunnel (rules/ASCII
        // art dari server bug-host) itu SSH_MSG_USERAUTH_BANNER asli (RFC 4252 SS5.4) --
        // dikirim server SETELAH key exchange, lewat kanal SSH yang sudah terenkripsi,
        // jadi TIDAK BISA "diintip" di level socket mentah (beda dengan baris banner
        // identifikasi "SSH-2.0-..." yang dibereskan lewat PrefixedSocket di
        // ConnectRelay). trilead-ssh2 SENDIRI memang menerima & menyimpannya secara
        // internal (field `banner` di com.trilead.ssh2.auth.AuthenticationManager),
        // tapi TIDAK PERNAH mengekspornya lewat API publik Connection manapun (sudah
        // dicek: tidak ada getBanner()/getAuthenticationBanner() di daftar lengkap
        // method publiknya) -- makanya sebelum ini banner-nya memang tidak mungkin
        // muncul di log app kita walau koneksinya sendiri sukses total. Satu-satunya
        // cara mengambilnya adalah reflection ke field internal itu, lihat
        // extractServerBanner().
        extractServerBanner(conn)?.let { banner ->
            StatusBus.log("Server Message:\n$banner")
        }
        StatusBus.success(StepId.SSH_AUTH)

        connection = conn

        // (b) SOCKS5 server -- lihat catatan arsitektur di header class ini.
        // Bind() ke port cuma terjadi kalau memang belum ada server yang
        // hidup dari sesi sebelumnya (connect awal, atau setelah hard
        // reset/disconnect penuh); kalau sudah ada & masih hidup (reconnect
        // biasa), cukup ganti Connection yang dipakainya.
        StatusBus.start(StepId.SOCKS5)
        val existing = socks5Server
        try {
            if (existing != null && existing.isRunning()) {
                existing.attachConnection(conn)
                // protect() lambda-nya sama persis lintas reconnect (masih
                // instance MyVpnService yang sama sepanjang sesi VPN), tapi
                // tetap di-set ulang di sini -- murah & menghindari asumsi
                // tersembunyi kalau suatu saat caller berubah per-reconnect.
                existing.setProtectDatagram(protectDatagram)
                StatusBus.log("SOCKS5 lokal sudah aktif di 127.0.0.1:${config.socksPort} (dipakai ulang, tidak bind ulang)")
            } else {
                val fresh = Socks5Server()
                fresh.setProtectDatagram(protectDatagram)
                fresh.start(config.socksPort)
                fresh.attachConnection(conn)
                socks5Server = fresh
            }
        } catch (e: Exception) {
            StatusBus.fail(StepId.SOCKS5, e.message ?: e.javaClass.simpleName)
            conn.close()
            relay.stop()
            throw e
        }
        StatusBus.success(StepId.SOCKS5)

        // PENTING (deteksi "tunnel mati sendiri"): ConnectionMonitor dipasang
        // DI SINI, SETELAH SOCKS5 lokal benar-benar siap -- BUKAN sesaat
        // setelah handshake SSH. Alasan: trilead-ssh2 memanggil
        // connectionLost() SECARA SINKRON di thread yang sama begitu
        // conn.close() dipanggil (bahkan kalau close() itu dipanggil oleh
        // kode kita sendiri, dari DALAM connect() ini, saat membersihkan
        // kegagalan auth/SOCKS5 di atas). Kalau monitor sudah aktif SEBELUM
        // titik itu, conn.close() pada baris "auth gagal"/"SOCKS5 gagal
        // bind" di atas ikut memicu onUnexpectedDisconnect() ->
        // MyVpnService.handleTunnelDeath() SECARA REENTRANT -- padahal
        // exception dari connect() ini sendiri BELUM SEMPAT sampai ke blok
        // catch establishTunnel() yang MEMANG bertugas menangani kegagalan
        // tersebut. connectionLost() cuma relevan/aktif SETELAH tunnel
        // benar-benar berdiri penuh, yang memang semestinya jadi definisi
        // "tunnel mati sendiri" (bukan "tunnel gagal terbentuk").
        conn.addConnectionMonitor(object : ConnectionMonitor {
            override fun connectionLost(reason: Throwable?) {
                onUnexpectedDisconnect(reason?.message ?: reason?.javaClass?.simpleName ?: "koneksi SSH terputus")
            }
        })

        // FIX (jaring pengaman): kalau justru koneksi mati TEPAT di antara
        // socks5Server siap dan addConnectionMonitor() barusan terpasang
        // (jendela race yang sangat sempit), trilead-ssh2 TIDAK akan pernah
        // memanggil connectionLost() untuk kejadian itu (monitor belum ada
        // saat kejadian). isAuthenticationComplete berubah false begitu
        // socket bawahnya benar-benar tertutup -- cek sekali di sini supaya
        // kejadian langka ini tetap dilaporkan sebagai kegagalan biasa lewat
        // exception, bukan diam-diam dianggap sukses.
        if (!conn.isAuthenticationComplete) {
            val msg = "Koneksi SSH terputus tepat setelah SOCKS5 disiapkan"
            StatusBus.fail(StepId.SOCKS5, msg)
            // socks5Server SENGAJA TIDAK di-stop() di sini (lihat catatan
            // arsitektur) -- cukup lepas referensi koneksi matinya, biar
            // reconnect berikutnya tidak perlu bind ulang port.
            socks5Server?.detachConnection()
            relay.stop()
            throw IOException(msg)
        }
        // FIX (log Terminal menyesatkan): dulu baris ini cuma "Connected" --
        // kedengarannya seperti seluruh proses sudah kelar, padahal di titik
        // ini baru SSH handshake + SOCKS5 lokal yang siap. TUN engine belum
        // dinyalakan dan verifyTunnelReallyWorks() (di MyVpnService) belum
        // membuktikan trafik device beneran lewat tunnel -- StepId.TUNNEL_ACTIVE
        // baru sukses SETELAH itu. Teks di sini diperjelas supaya tidak
        // disalahartikan sebagai "sudah connect sepenuhnya".
        StatusBus.log("Mengaktifkan tunnel ke seluruh trafik device...")

        Log.i(TAG, "SSH (trilead-ssh2) tersambung via relay lokal. SOCKS5 di 127.0.0.1:${config.socksPort}")
    }

    /**
     * Susun pesan error yang bisa ditindaklanjuti user untuk kegagalan
     * handshake SSH.
     *
     * PENTING (bug fix UX): kalau tahap sebelumnya (connect ke server/proxy,
     * TLS, payload) sudah punya alasan gagal yang lebih spesifik di StatusBus,
     * itu alasan aslinya -- pakai itu. Tapi kalau tidak ada (semua tahap
     * sebelumnya sukses, artinya trilead-ssh2 sendiri yang gagal parse balasan
     * server sebagai protokol SSH), JANGAN tampilkan pesan mentah dari
     * trilead-ssh2 apa adanya -- pesan itu selalu menyebut alamat relay
     * loopback kita ("127.0.0.1:<port>"), bukan host yang diisi user, dan
     * jadi membingungkan. Ganti dengan penjelasan yang masuk akal sesuai mode
     * yang dipakai.
     */
    private fun explainHandshakeFailure(e: Exception, config: ServerConfig): String {
        StatusBus.firstErrorDetail()?.let { return it }

        // Pesan asli trilead-ssh2 hampir selalu menyebut alamat relay loopback
        // internal ("127.0.0.1:<port>"), bukan host yang diisi user -- jangan
        // pernah ditampilkan apa adanya, ganti dulu supaya tidak membingungkan.
        val rawDetail = (e.message ?: e.javaClass.simpleName)
            .replace(Regex("""127\.0\.0\.1:\d+"""), "server")

        val target = "${config.host}:${config.port}"
        var explanation = when (config.mode) {
            ConnectionMode.SSH ->
                "Server $target tidak membalas dengan protokol SSH yang valid. " +
                    "Server ini kemungkinan butuh koneksi terenkripsi -- coba mode \"SSH SSL\", " +
                    "\"SSH SSL + Payload\", atau \"Enhanced\"."
            ConnectionMode.SSH_SSL ->
                "TLS berhasil, tapi server $target tidak membalas dengan protokol SSH yang valid " +
                    "setelah itu. Cek kembali host/port, atau coba isi payload custom lewat mode " +
                    "\"SSH SSL + Payload\"/\"Enhanced\"."
            ConnectionMode.SSH_SSL_PAYLOAD ->
                "TLS & payload berhasil terkirim, tapi server $target tidak membalas dengan " +
                    "protokol SSH yang valid setelah itu. Cek kembali isi payload atau host/port-nya."
            ConnectionMode.REMOTE_PROXY ->
                "Remote proxy berhasil membuka tunnel ke $target, tapi server tidak membalas dengan " +
                    "protokol SSH yang valid. Cek kembali host/port SSH asli (bukan proxy-nya)."
            ConnectionMode.ENHANCED ->
                "Semua tahap sebelumnya berhasil, tapi server $target tidak membalas dengan " +
                    "protokol SSH yang valid. Cek kembali kombinasi proxy/SNI/payload yang dipakai."
            // Tidak pernah benar-benar terjadi -- mode XRAY tidak lewat SshTunnelManager
            // sama sekali (lihat MyVpnService.startVpn), cabang ini cuma supaya `when`
            // di atas exhaustive.
            ConnectionMode.XRAY ->
                "Mode Xray seharusnya tidak lewat jalur SSH ini sama sekali."
        }

        // WebSocket sekarang toggle independen (bisa dikombinasikan dengan mode
        // apa pun di atas). PENTING (bug fix pesan menyesatkan): sebelumnya baris
        // di bawah ini menempel "WebSocket sudah berhasil" HANYA berdasarkan
        // config.usesWebSocket() (flag statis per-mode, SELALU true) -- tanpa
        // pernah mengecek apakah WebSocket di percobaan barusan ini benar-benar
        // sukses atau malah ditolak lalu fallback ke raw (StepStatus.SKIPPED).
        // Akibatnya user selalu diberi tahu "WebSocket berhasil" walau
        // sebenarnya ditolak -- menyesatkan arah troubleshooting. Sekarang
        // dicek status ASLI tahap WEBSOCKET di StatusBus, dan kalau ternyata
        // SKIPPED, alasan penolakan aslinya (disimpan di step.detail sejak
        // ConnectRelay.openRealConnection tapi sebelumnya tidak pernah
        // ditampilkan sama sekali) ikut ditampilkan.
        val wsStep = StatusBus.steps.value.firstOrNull { it.id == StepId.WEBSOCKET }
        if (wsStep != null) {
            explanation += when (wsStep.status) {
                StepStatus.SUCCESS ->
                    " Handshake WebSocket di atas mode ini sudah berhasil -- cek juga " +
                        "path WebSocket dan pastikan server memang meneruskan tunnel WebSocket ke SSH asli."
                StepStatus.SKIPPED ->
                    " CATATAN: handshake WebSocket sebenarnya DITOLAK/gagal " +
                        "(${wsStep.detail ?: "alasan tidak diketahui"}), lalu otomatis fallback ke " +
                        "koneksi raw TANPA WebSocket -- dan SSH tetap gagal setelah fallback ini. " +
                        "Kemungkinan besar server tujuan MEWAJIBKAN WebSocket supaya bisa menjangkau " +
                        "SSH asli-nya, jadi cek ulang payload/path WebSocket yang dipakai (jangan " +
                        "sampai payload custom dan handshake WebSocket otomatis ini saling tumpang tindih)."
                else -> ""
            }
        }
        return "$explanation ($rawDetail)"
    }

    /**
     * Ambil "Server Message" (SSH_MSG_USERAUTH_BANNER, RFC 4252 SS5.4) lewat
     * reflection ke internal trilead-ssh2 -- lihat catatan panjang di titik
     * pemanggilan fungsi ini (di connect()) untuk alasan kenapa ini WAJIB
     * lewat reflection, bukan API publik biasa.
     *
     * Sengaja dicari lewat TIPE field (bukan NAMA field) di kelas [Connection]
     * -- lebih tahan kalau nama field internal itu beda-beda antar versi/fork
     * trilead-ssh2 (nama TIPE-nya, com.trilead.ssh2.auth.AuthenticationManager,
     * jauh lebih stabil daripada nama variabelnya). Kalau field `banner` di
     * dalam AuthenticationManager itu sendiri ternyata berubah nama di versi
     * lain, atau reflection-nya gagal karena alasan apa pun, fungsi ini cuma
     * diam-diam mengembalikan null (banner tidak tampil) -- TIDAK PERNAH bikin
     * proses connect gagal/crash, karena ini murni fitur tampilan tambahan,
     * bukan sesuatu yang boleh mengganggu jalur koneksi utama.
     */
    private fun extractServerBanner(conn: Connection): String? = try {
        val amField = Connection::class.java.declaredFields
            .firstOrNull { it.type.name == "com.trilead.ssh2.auth.AuthenticationManager" }
        amField?.isAccessible = true
        val am = amField?.get(conn)
        val bannerField = am?.javaClass?.declaredFields?.firstOrNull { it.name == "banner" }
        bannerField?.isAccessible = true
        (bannerField?.get(am) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Tidak bisa ambil server banner (kemungkinan internal trilead-ssh2 berubah)", e)
        null
    }

    /**
     * Dipanggil saat tunnel mati & AKAN dicoba reconnect (dari
     * MyVpnService.handleTunnelDeath()/establishTunnel() saat reconnect
     * gagal): tutup koneksi SSH + relay yang mati, TAPI [socks5Server]
     * SENGAJA DIBIARKAN HIDUP -- port TIDAK dilepas sama sekali. Inilah
     * inti fix EADDRINUSE, lihat catatan arsitektur di header class ini.
     * Client SOCKS5 yang kebetulan masuk selagi belum ada koneksi SSH aktif
     * cukup dijawab "connection refused" oleh Socks5Server sendiri.
     */
    fun disconnectForReconnect() {
        synchronized(teardownLock) {
            socks5Server?.detachConnection()
            try {
                connection?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error close SSH", e)
            }
            try {
                connectRelay?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop relay", e)
            }
            connection = null
            connectRelay = null
        }
    }

    /**
     * Dipanggil saat sesi VPN BENAR-BENAR berakhir (stopVpn() manual, atau
     * hard reset penuh): bongkar SEMUANYA termasuk [socks5Server] -- port
     * dilepas. connect() berikutnya (baik sesi baru maupun setelah hard
     * reset) akan bind() dari nol lagi, dan itu memang seharusnya aman
     * karena titik ini adalah batas sesi yang jelas, bukan reconnect
     * di-tengah-sesi.
     */
    fun disconnect() {
        synchronized(teardownLock) {
            try {
                socks5Server?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop SOCKS5", e)
            }
            try {
                connection?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error close SSH", e)
            }
            try {
                connectRelay?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stop relay", e)
            }
            socks5Server = null
            connection = null
            connectRelay = null
        }
    }

    fun isConnected(): Boolean = connection?.isAuthenticationComplete == true
}
