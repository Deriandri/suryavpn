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
 */
class SshTunnelManager {

    companion object {
        private const val TAG = "SshTunnelManager"
        private const val CONNECT_TIMEOUT_MS = 15000
    }

    private var connection: Connection? = null
    private var connectRelay: ConnectRelay? = null
    private var socks5Server: Socks5Server? = null

    @Throws(Exception::class)
    fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
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

        // PENTING (deteksi "tunnel mati sendiri"): ConnectionMonitor bawaan
        // trilead-ssh2 dipanggil PERSIS saat socket TCP koneksi ini benar-benar
        // tertutup, entah karena server yang memutus, jaringan hilang, atau
        // koneksi memang kita tutup sendiri lewat disconnect() di bawah (yang
        // terakhir ini difilter di level MyVpnService lewat flag
        // "stoppingIntentionally", bukan di sini -- SshTunnelManager cukup
        // teruskan semua event apa adanya).
        conn.addConnectionMonitor(object : ConnectionMonitor {
            override fun connectionLost(reason: Throwable?) {
                onUnexpectedDisconnect(reason?.message ?: reason?.javaClass?.simpleName ?: "koneksi SSH terputus")
            }
        })

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
        StatusBus.success(StepId.SSH_AUTH)

        connection = conn

        // (b) SOCKS5 server sendiri, menggantikan peran jsocks
        StatusBus.start(StepId.SOCKS5)
        val socks = Socks5Server(conn)
        try {
            socks.start(config.socksPort)
        } catch (e: Exception) {
            StatusBus.fail(StepId.SOCKS5, e.message ?: e.javaClass.simpleName)
            conn.close()
            relay.stop()
            throw e
        }
        socks5Server = socks
        StatusBus.success(StepId.SOCKS5)
        StatusBus.log("Connected")

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

    fun disconnect() {
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

    fun isConnected(): Boolean = connection?.isAuthenticationComplete == true
}
