package com.example.tunnelapp.tunnel

import android.util.Log
import com.example.tunnelapp.model.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Relay lokal satu-koneksi di 127.0.0.1.
 *
 * trilead-ssh2 connect ke relay ini (loopback, otomatis tidak kena routing
 * TUN). Kelas ini yang benar-benar membuka koneksi ke server SSH ASLI --
 * termasuk protect() (wajib, anti-loop VPN), proxy HTTP (CONNECT) kalau
 * dipakai, payload custom, dan pembungkusan TLS kalau mode yang dipilih
 * memerlukannya.
 */
class ConnectRelay(
    private val config: ServerConfig,
    private val protect: (Socket) -> Boolean
) {
    companion object {
        private const val TAG = "ConnectRelay"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val MAX_PROXY_RESPONSE_BYTES = 8192

        // PENTING (bug fix "freeze"/SSH tidak jalan): tanpa timeout ini, socket
        // yang dipakai untuk proxy CONNECT dan/atau percobaan handshake WebSocket
        // bisa BLOCKING SELAMANYA kalau server tujuan adalah SSH biasa (bukan
        // proxy HTTP maupun WebSocket) -- server itu cuma mengirim banner SSH-nya
        // sendiri lalu diam menunggu balasan client, sementara kita menunggu
        // respons HTTP/WebSocket yang tidak akan pernah datang (deadlock: kedua
        // sisi saling menunggu). Dengan timeout ini, kalau tidak ada respons yang
        // sesuai dalam waktu ini, dianggap gagal -> otomatis fallback ke raw SSH
        // (lihat openRealConnection, attemptWebSocket=false) alih-alih macet.
        // HANYA dipakai selama fase handshake (proxy/TLS/WebSocket) -- WAJIB
        // di-reset ke 0 (tanpa batas) sebelum socket dipakai untuk trafik tunnel
        // asli, supaya tunnel yang sedang idle tidak ikut ke-timeout.
        private const val HANDSHAKE_READ_TIMEOUT_MS = 8000

        // --- Dipakai HANYA kalau ServerConfig.ignoreCertErrors = true ---
        // TrustManager yang menerima SEMUA sertifikat server apa adanya (tanpa
        // validasi chain/tanggal/hostname sama sekali). Ini SENGAJA tidak aman
        // secara TLS asli -- tujuannya cuma supaya handshake tetap jalan ke
        // server yang pakai sertifikat self-signed/expired/nama tidak cocok
        // (umum di server SSH/bug-host komunitas), sama seperti perilaku
        // DarkTunnel/HTTP Custom yang juga tidak pernah benar-benar validasi
        // sertifikat server tujuannya. TIDAK dipakai kalau ignoreCertErrors
        // false (default) -- jalur situ tetap pakai SSLSocketFactory.getDefault()
        // yang validasi normal via trust store sistem.
        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        private val insecureSocketFactory: SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }.socketFactory
        }

        /** @return factory sesuai [ServerConfig.ignoreCertErrors] -- default (aman) atau trust-all. */
        private fun socketFactoryFor(ignoreCertErrors: Boolean): SSLSocketFactory =
            if (ignoreCertErrors) insecureSocketFactory else SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    /** @return port lokal (127.0.0.1) tempat relay ini listen. */
    fun start(): Int {
        val ss = ServerSocket()
        ss.bind(InetSocketAddress("127.0.0.1", 0))
        serverSocket = ss
        running = true

        acceptThread = Thread({
            try {
                val clientSocket = ss.accept() // trilead-ssh2 yang connect ke sini
                handleClient(clientSocket)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Error accept relay", e)
            }
        }, "ssh-connect-relay").apply { start() }

        return ss.localPort
    }

    private fun handleClient(clientSocket: Socket) {
        val realSocket = try {
            openRealConnection(attemptWebSocket = true)
        } catch (e: Exception) {
            // PENTING: sebelumnya exception di sini hanya di-log lalu socket
            // ditutup diam-diam -- trilead-ssh2 hanya melihat koneksi putus
            // dan melempar error generik ("connection reset") tanpa alasan
            // asli. Sekarang alasan sebenarnya (host unreachable, protect()
            // gagal, proxy menolak, TLS gagal, dll) dilaporkan ke StatusBus
            // supaya muncul di log tahap koneksi.
            Log.e(TAG, "Gagal membuka koneksi ke server SSH asli", e)
            clientSocket.close()
            return
        }
        StreamPump.pumpBothWays(clientSocket, realSocket)
    }

    /**
     * @param attemptWebSocket kalau true, coba handshake WebSocket di akhir (perilaku
     *   normal untuk percobaan pertama). Kalau server/CDN tujuan MENOLAK upgrade-nya
     *   (bukan server WebSocket asli, atau memang tidak paham WebSocket sama sekali),
     *   koneksi ini ditutup dan SELURUH proses (TCP connect -> proxy -> TLS -> payload)
     *   diulang dari nol lewat pemanggilan rekursif dengan attemptWebSocket=false --
     *   supaya tetap bisa connect normal (raw, tanpa WebSocket) walau server tujuan
     *   sama sekali tidak menyinggung WebSocket. Ini yang membuat WebSocket bisa selalu
     *   dicoba otomatis di SEMUA mode tanpa perlu toggle manual dan tanpa merusak
     *   koneksi ke server yang memang tidak mendukungnya.
     */
    private fun openRealConnection(attemptWebSocket: Boolean): Socket {
        StatusBus.start(StepId.CONNECT_SERVER)
        val rawSocket = Socket()

        // PENTING: Socket() kosong belum tentu langsung punya native file
        // descriptor -- fd baru benar-benar dibuat OS saat butuh (biasanya
        // saat connect()/bind()). protect() dari VpnService WAJIB dipanggil
        // SETELAH fd itu ada, kalau tidak protect() bisa diam-diam gagal.
        // bind(0) di sini memaksa OS membuat fd-nya sekarang juga.
        try {
            rawSocket.bind(InetSocketAddress(0))
        } catch (e: Exception) {
            Log.w(TAG, "Gagal bind socket sebelum protect()", e)
        }

        val protected = protect(rawSocket)
        if (!protected) {
            // JANGAN pernah lanjut connect() kalau protect() gagal --
            // socket ini pasti akan kena tangkap balik oleh TUN interface
            // kita sendiri (route 0.0.0.0/0 sudah aktif) dan looping ke diri
            // sendiri. Ini yang menyebabkan ECONNABORTED walau akun SSH-nya
            // sendiri valid dan jalan normal di aplikasi lain.
            val msg = "protect() gagal -- koneksi akan looping balik ke VPN sendiri"
            Log.e(TAG, msg)
            StatusBus.fail(StepId.CONNECT_SERVER, msg)
            rawSocket.close()
            throw IOException(msg)
        }

        // (proxy) Kalau mode PROXY, atau ENHANCED dengan host proxy diisi,
        // TCP connect diarahkan ke proxy dulu -- bukan langsung ke server SSH.
        val usesProxy = config.usesProxy()
        val connectHost = if (usesProxy) config.proxyHost!!.trim() else config.host
        val connectPort = if (usesProxy) (config.proxyPort ?: config.port) else config.port

        try {
            rawSocket.connect(InetSocketAddress(connectHost, connectPort), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            StatusBus.fail(StepId.CONNECT_SERVER, e.message ?: e.javaClass.simpleName)
            throw e
        }
        // PENTING (bug fix): tahap "Menghubungkan ke server" sekarang langsung
        // ditandai sukses di sini, TEPAT setelah TCP connect berhasil --
        // sebelumnya baru ditandai sukses di akhir handleClient() (setelah TLS
        // & payload juga selesai), jadi baris log ini terlihat "macet" berputar
        // terus sepanjang tahap TLS/payload berjalan, alih-alih menyala hijau
        // satu per satu sesuai urutan tahap yang sebenarnya terjadi.
        StatusBus.success(StepId.CONNECT_SERVER)

        // PENTING (bug fix "freeze"): aktifkan timeout baca cuma untuk fase
        // handshake (proxy CONNECT, TLS, WebSocket) yang mengikuti. Tanpa ini,
        // server yang tidak paham proxy/WebSocket (SSH biasa) bisa membuat
        // pembacaan di bawah menunggu selamanya. Di-reset ke 0 sebelum socket
        // dipakai untuk trafik tunnel asli (lihat semua `return` di bawah).
        rawSocket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS

        if (usesProxy) {
            if (config.proxyRawMode) {
                // Raw passthrough: proxyHost:proxyPort SUDAH jadi socket TCP yang
                // dianggap langsung terhubung ke server SSH asli -- TIDAK ada request
                // CONNECT, TIDAK ada verifikasi balasan "200" apa pun. Ini yang
                // dipakai kalau proxyHost sebenarnya cuma titik singgah pass-through
                // (mis. host/CDN lain) yang akan meneruskan byte apa adanya berdasar
                // SNI/Host di tahap TLS/WebSocket berikutnya (yang tetap memakai
                // config.host/sslSni, BUKAN proxyHost) -- bukan proxy HTTP betulan
                // yang paham semantik CONNECT.
                Log.i(
                    TAG,
                    "Proxy raw passthrough aktif -- lanjut tanpa CONNECT " +
                        "(TCP tersambung ke $connectHost:$connectPort)"
                )
                StatusBus.skip(StepId.PROXY_CONNECT)
            } else {
                StatusBus.start(StepId.PROXY_CONNECT)
                try {
                    sendProxyConnect(rawSocket)
                    StatusBus.success(StepId.PROXY_CONNECT)
                } catch (e: Exception) {
                    StatusBus.fail(StepId.PROXY_CONNECT, e.message ?: e.javaClass.simpleName)
                    throw e
                }
            }
        }

        // PENTING (bug fix): TLS wrap HARUS dilakukan SEBELUM payload dikirim,
        // bukan sesudahnya. Sebelumnya payload ditulis langsung ke rawSocket
        // (plaintext) sebelum TLS handshake dimulai -- byte plaintext itu akan
        // bentrok dengan ClientHello TLS di koneksi yang sama dan membuat
        // handshake TLS gagal/rusak begitu mode yang memakai TLS + payload
        // dipakai. Urutan yang benar: TCP connect -> (opsional) proxy CONNECT
        // -> (opsional) TLS handshake -> payload dikirim lewat socket hasil
        // akhir (socket TLS kalau mode-nya pakai TLS, socket mentah kalau
        // tidak) -- persis urutan yang sudah ditampilkan di UI log
        // (buildStepsFor menaruh tahap proxy -> TLS -> payload berurutan).
        var socket: Socket = if (config.usesTls()) {
            StatusBus.start(StepId.TLS)
            try {
                val sniHost = config.sslSni?.takeIf { it.isNotBlank() } ?: config.host
                // SSLSocketFactory.getDefault() (atau insecureSocketFactory kalau
                // config.ignoreCertErrors true) punya tipe return SocketFactory (parent
                // class) walau isinya sebenarnya SSLSocketFactory -- wajib di-cast supaya
                // overload createSocket(Socket, String, Int, Boolean) kelihatan oleh compiler.
                val sslSocket = socketFactoryFor(config.ignoreCertErrors)
                    .createSocket(rawSocket, sniHost, rawSocket.port, true) as SSLSocket
                val params = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                sslSocket.sslParameters = params

                // Paksa versi TLS tertentu kalau diminta (mis. server SSH SSL lama
                // yang cuma dukung TLSv1/1.1) -- kosong/null/"Default" berarti
                // biarkan sistem pilih otomatis (perilaku lama, TLSv1.2/1.3).
                val forcedVersion = config.tlsVersion?.trim()?.takeIf {
                    it.isNotBlank() && !it.equals("Default", ignoreCase = true)
                }
                if (forcedVersion != null) {
                    if (sslSocket.supportedProtocols.contains(forcedVersion)) {
                        sslSocket.enabledProtocols = arrayOf(forcedVersion)
                        Log.i(TAG, "Versi TLS dipaksa ke $forcedVersion")
                    } else {
                        Log.w(
                            TAG,
                            "Versi TLS $forcedVersion tidak didukung device ini " +
                                "(tersedia: ${sslSocket.supportedProtocols.joinToString()}), " +
                                "pakai default"
                        )
                    }
                }

                sslSocket.startHandshake()
                Log.i(TAG, "TLS handshake sukses (SNI: $sniHost)")
                val certNote = if (config.ignoreCertErrors) ", verifikasi sertifikat DINONAKTIFKAN" else ""
                StatusBus.log("TLS handshake sukses (SNI: $sniHost, ${sslSocket.session.protocol}$certNote)")
                StatusBus.success(StepId.TLS)
                sslSocket
            } catch (e: Exception) {
                StatusBus.fail(StepId.TLS, e.message ?: e.javaClass.simpleName)
                throw e
            }
        } else {
            rawSocket
        }

        val payload = config.payload
        if (!payload.isNullOrEmpty()) {
            StatusBus.start(StepId.PAYLOAD)
            try {
                val substituted = payload
                    .replace("[host]", config.host)
                    .replace("[port]", config.port.toString())
                    .replace("[crlf]", "\r\n")
                    .replace("[cr]", "\r")
                    .replace("[lf]", "\n")
                // PENTING (bug fix, ditemukan dari perbandingan langsung dengan payload
                // DarkTunnel yang berhasil): placeholder "[split]" sebelumnya TIDAK
                // dikenali sama sekali di sini -- ikut terkirim APA ADANYA sebagai 7
                // byte ASCII literal "[split]" yang nyempil di tengah body HTTP,
                // padahal di konvensi HTTP Injector/DarkTunnel/HTTP Custom placeholder
                // ini artinya "kirim sebagai request/tulisan socket TERPISAH di titik
                // ini" (dipakai buat memecah payload jadi beberapa potongan TCP,
                // bukan satu blok). Sekarang setiap potongan ditulis+flush satu per
                // satu, TANPA sisa teks "[split]" ikut terkirim.
                val out = socket.getOutputStream()
                val chunks = substituted.split("[split]")
                for (chunk in chunks) {
                    if (chunk.isEmpty()) continue
                    // Log baris ini APA ADANYA (isi payload yang betul-betul dikirim ke
                    // socket, cuma \r\n ditulis balik jadi "[crlf]" biar kebaca di layar
                    // -- sama seperti tampilan DarkTunnel), bukan teks pura-pura.
                    StatusBus.log("Sending Payload: ${chunk.replace("\r\n", "[crlf]")}")
                    out.write(chunk.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                Log.i(TAG, "Payload custom terkirim (${chunks.size} bagian, ${substituted.length - "[split]".length * (chunks.size - 1)} bytes)")
                StatusBus.success(StepId.PAYLOAD)
            } catch (e: Exception) {
                StatusBus.fail(StepId.PAYLOAD, e.message ?: e.javaClass.simpleName)
                throw e
            }

            // PENTING (bukan kosmetik -- ini bug-fix sekaligus sumber log "Response: ...").
            // Server/CDN tujuan bisa saja membalas satu atau lebih baris non-SSH dulu
            // (respons HTTP dari request di payload, mis. "404 Not Found" untuk request
            // pertama, lalu "101 Switching Protocols" untuk request Upgrade) SEBELUM baris
            // banner SSH asli ("SSH-2.0-...") benar-benar mulai. RFC 4253 §4.2 memang
            // mengizinkan baris pembuka seperti itu, dan client WAJIB membuang baris yang
            // TIDAK diawali "SSH-" sampai baris identifikasi asli ditemukan. Sebelumnya
            // kode ini langsung menyerahkan socket ke trilead-ssh2 tanpa membuang baris
            // itu -- trilead bisa salah mem-parsing baris HTTP itu sebagai banner dan
            // gagal. Sekarang kita yang membaca & membuang baris-baris itu SENDIRI, sambil
            // mencatatnya sebagai log -- baru socket yang bersih (persis mulai dari
            // "SSH-2.0-...") diserahkan ke trilead-ssh2.
            try {
                socket = consumeUntilSshBanner(socket)
            } catch (e: Exception) {
                StatusBus.fail(StepId.PAYLOAD, "Server tidak mengirim banner SSH setelah payload: ${e.message}")
                throw e
            }
        }

        // (WebSocket genuine, RFC 6455) Cuma dicoba kalau TIDAK ada payload custom
        // -- lihat ServerConfig.attemptsFormalWebSocket() untuk alasannya (payload
        // custom dipercaya sebagai satu-satunya trik HTTP yang berdiri sendiri,
        // sama seperti konvensi DarkTunnel/HTTP Custom -- mencoba WS genuine di
        // atasnya cuma bikin bentrok/rusak). Dilakukan di atas socket hasil akhir
        // (TLS/proxy sudah selesai) SEBELUM SSH dimulai. Kalau berhasil, semua byte
        // SSH setelah ini otomatis dibungkus/dibuka sebagai frame WebSocket biner
        // asli. Kalau server/CDN tujuan menolak upgrade-nya, socket ini ditutup dan
        // koneksi diulang dari nol tanpa WebSocket (attemptWebSocket=false).
        if (config.attemptsFormalWebSocket() && attemptWebSocket) {
            StatusBus.start(StepId.WEBSOCKET)
            val wsHost = config.sslSni?.takeIf { it.isNotBlank() } ?: config.host
            val wsPath = config.wsPath?.takeIf { it.isNotBlank() } ?: "/"
            try {
                WebSocketHandshake.perform(socket, wsHost, wsPath, config.parsedCustomHeaders())
                Log.i(TAG, "Handshake WebSocket sukses (host: $wsHost, path: $wsPath)")
                StatusBus.success(StepId.WEBSOCKET)
                // Fase handshake selesai -- kembalikan ke tanpa batas waktu supaya
                // tunnel yang sedang idle tidak ikut ke-timeout (lihat komentar di
                // HANDSHAKE_READ_TIMEOUT_MS).
                socket.soTimeout = 0
                return WebSocketSocket(socket)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "WebSocket ditolak/gagal ($reason) -- fallback otomatis ke raw tanpa WebSocket", e)
                StatusBus.skip(StepId.WEBSOCKET, "Ditolak/gagal ($reason) -- fallback ke raw")
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                return openRealConnection(attemptWebSocket = false)
            }
        }

        // Fase handshake selesai (tanpa WebSocket, atau attemptWebSocket=false) --
        // kembalikan ke tanpa batas waktu, sama seperti alasan di jalur sukses
        // WebSocket di atas.
        socket.soTimeout = 0
        return socket
    }

    /**
     * Kirim request HTTP CONNECT ke proxy supaya proxy membuka tunnel TCP
     * mentah ke [ServerConfig.host]:[ServerConfig.port] (server SSH asli),
     * lalu tunggu & validasi respons "200" dari proxy sebelum lanjut.
     *
     * Setelah ini sukses, `rawSocket` berperilaku persis seperti socket yang
     * connect langsung ke server SSH -- TLS/payload/SSH selanjutnya jalan
     * seperti biasa di atasnya.
     */
    @Throws(IOException::class)
    private fun sendProxyConnect(rawSocket: Socket) {
        val target = "${config.host}:${config.port}"
        // Header custom (kalau diisi user, lihat ServerConfig.customHeaders) disisipkan
        // SEBELUM baris kosong penutup "\r\n\r\n" -- jadi tetap dianggap bagian header
        // oleh proxy, bukan bagian body. Header bawaan (Host, Proxy-Connection,
        // Connection) tetap selalu dikirim apa adanya; header custom cuma tambahan.
        val extraHeaderLines = config.parsedCustomHeaders()
            .joinToString("") { (name, value) -> "$name: $value\r\n" }
        val request = "CONNECT $target HTTP/1.1\r\n" +
            "Host: $target\r\n" +
            "Proxy-Connection: Keep-Alive\r\n" +
            "Connection: Keep-Alive\r\n" +
            extraHeaderLines +
            "\r\n"

        rawSocket.getOutputStream().apply {
            write(request.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }

        // PENTING: jangan pakai BufferedReader/InputStreamReader di sini --
        // keduanya membaca lebih banyak byte dari yang benar-benar diminta
        // (internal buffering), sehingga bisa "memakan" byte TLS ClientHello
        // atau banner SSH yang datang tepat setelah header HTTP proxy ini.
        // Baca byte demi byte sampai batas akhir header "\r\n\r\n" saja.
        val headerBytes = readUntilDoubleCrlf(rawSocket.getInputStream())
        val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
        val statusLine = headerText.lineSequence().firstOrNull()?.trim().orEmpty()

        val isSuccess = Regex("""^HTTP/\d\.\d\s+200\b""").containsMatchIn(statusLine)
        if (!isSuccess) {
            val shown = statusLine.ifBlank { "tidak ada respons dari proxy" }
            StatusBus.log("Response: $shown")
            throw IOException("Proxy menolak CONNECT ke $target: $shown")
        }
        StatusBus.log("Response: $statusLine")
        Log.i(TAG, "Proxy CONNECT ke $target sukses ($statusLine)")
    }

    /**
     * Baca stream byte demi byte (WAJIB byte demi byte, bukan buffered read --
     * supaya tidak "memakan" byte binari SSH pertama yang datang tepat setelah
     * baris banner) sampai ketemu baris yang diawali "SSH-". Setiap baris
     * non-SSH yang dilewati dicatat ke [StatusBus.log] kalau memang terlihat
     * seperti baris respons HTTP (diawali "HTTP/"), sama seperti DarkTunnel
     * menampilkan "Response: HTTP/1.1 404 Not Found" / "101 Switching
     * Protocols". Baris SSH banner asli yang ditemukan di akhir juga dicatat.
     *
     * PENTING (bug fix -- "Tidak menemukan banner SSH setelah 25 baris" padahal
     * bannernya ADA): kalau salah satu request di payload custom sendiri
     * berisi header "Upgrade: websocket" dan server/CDN membalas dengan
     * "101 Switching Protocols", byte-byte SETELAH balasan itu SUDAH
     * dibingkai sebagai frame WebSocket biner oleh server -- BUKAN lagi teks
     * banner SSH polos. Kalau tetap dipindai sebagai teks mentah (perilaku
     * sebelumnya), pemindaian baris tidak akan PERNAH cocok dengan "SSH-"
     * (byte biner acak disangka baris terus-menerus) dan selalu gagal walau
     * bannernya sebenarnya ada, cuma terbungkus frame. Sekarang begitu
     * terdeteksi respons 101 di antara balasan payload, socket dibungkus
     * [WebSocketSocket] (pembuka frame WS yang sama dipakai jalur WebSocket
     * formal) SEBELUM lanjut mencari baris banner -- kali ini dari byte hasil
     * buka-bungkus frame, bukan byte mentah. @return socket yang harus dipakai
     * SETERUSNYA (socket asli kalau tidak ada switch, atau [WebSocketSocket]
     * kalau ada).
     */
    @Throws(IOException::class)
    private fun consumeUntilSshBanner(socket: Socket): Socket {
        val sawSwitchingProtocols = scanLinesForSshBanner(socket.getInputStream())
        if (!sawSwitchingProtocols) return socket

        StatusBus.log("Terdeteksi 101 Switching Protocols dari payload -- membuka frame WebSocket")
        val wsSocket = WebSocketSocket(socket)
        scanLinesForSshBanner(wsSocket.getInputStream())
        // Timeout khusus fase handshake di-reset di socket ASLI di sini (bukan
        // di wrapper -- WebSocketSocket tidak meneruskan soTimeout ke socket
        // asli di dalamnya), supaya trafik SSH yang lewat di atas WebSocket
        // ini tidak ikut ke-timeout waktu idle, sama seperti jalur WebSocket
        // formal di bawah.
        socket.soTimeout = 0
        return wsSocket
    }

    /**
     * @return true kalau ditemukan baris respons "HTTP/x.x 101 ..." SEBELUM
     * banner SSH ditemukan (menandakan byte sesudahnya perlu dibuka sebagai
     * frame WebSocket), false kalau banner SSH langsung ditemukan tanpa itu.
     */
    @Throws(IOException::class)
    private fun scanLinesForSshBanner(input: InputStream): Boolean {
        val lineBuf = ByteArrayOutputStream()
        var linesSeen = 0
        var sawSwitchingProtocols = false
        val maxLines = 25 // batas wajar, hindari loop tanpa akhir kalau server nyeleneh
        while (linesSeen < maxLines) {
            val b = input.read()
            if (b == -1) throw IOException("Koneksi ditutup server sebelum mengirim banner SSH")
            if (b == '\n'.code) {
                val line = lineBuf.toByteArray().toString(StandardCharsets.ISO_8859_1).trimEnd('\r')
                lineBuf.reset()
                linesSeen++
                if (line.startsWith("SSH-")) {
                    StatusBus.log(line)
                    return sawSwitchingProtocols
                }
                if (line.isNotBlank() && Regex("""^HTTP/\d\.\d\s+\d{3}""").containsMatchIn(line)) {
                    StatusBus.log("Response: $line")
                    if (Regex("""^HTTP/\d\.\d\s+101\b""").containsMatchIn(line)) {
                        sawSwitchingProtocols = true
                    }
                } else if (sawSwitchingProtocols && line.isBlank()) {
                    // Baris kosong ini menutup blok header respons 101 di atas --
                    // byte SETELAH ini sudah frame WebSocket, bukan teks lagi.
                    // Berhenti di sini (JANGAN terus baca sebagai teks) supaya
                    // pemanggil bisa membungkus socket dengan WebSocketSocket
                    // sebelum lanjut mencari baris banner dari frame yang dibuka.
                    return true
                }
                // Baris lain (header HTTP seperti "Upgrade: websocket", dll) sengaja
                // tidak ditampilkan supaya log tidak penuh sampah, tapi tetap DIBUANG
                // dari stream di sini (itu intinya fungsi ini).
            } else {
                lineBuf.write(b)
            }
        }
        throw IOException("Tidak menemukan banner SSH setelah $maxLines baris respons non-SSH")
    }

    private fun readUntilDoubleCrlf(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Proxy menutup koneksi sebelum mengirim respons lengkap")
            out.write(b)
            val size = out.size()
            if (size >= 4) {
                val bytes = out.toByteArray()
                if (bytes[size - 4] == '\r'.code.toByte() &&
                    bytes[size - 3] == '\n'.code.toByte() &&
                    bytes[size - 2] == '\r'.code.toByte() &&
                    bytes[size - 1] == '\n'.code.toByte()
                ) {
                    return bytes
                }
            }
            if (size > MAX_PROXY_RESPONSE_BYTES) {
                throw IOException("Respons proxy terlalu besar atau tidak valid")
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
    }
}
