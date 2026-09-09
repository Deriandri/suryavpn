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
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
        val socket: Socket = if (config.usesTls()) {
            StatusBus.start(StepId.TLS)
            try {
                val sniHost = config.sslSni?.takeIf { it.isNotBlank() } ?: config.host
                // SSLSocketFactory.getDefault() punya tipe return SocketFactory (parent class)
                // walau isinya sebenarnya SSLSocketFactory -- wajib di-cast supaya overload
                // createSocket(Socket, String, Int, Boolean) kelihatan oleh compiler.
                val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
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
                val payloadText = payload
                    .replace("[host]", config.host)
                    .replace("[port]", config.port.toString())
                    .replace("[crlf]", "\r\n")
                    .replace("[cr]", "\r")
                    .replace("[lf]", "\n")
                socket.getOutputStream().apply {
                    write(payloadText.toByteArray(StandardCharsets.UTF_8))
                    flush()
                }
                Log.i(TAG, "Payload custom terkirim (${payloadText.length} bytes)")
                StatusBus.success(StepId.PAYLOAD)
            } catch (e: Exception) {
                StatusBus.fail(StepId.PAYLOAD, e.message ?: e.javaClass.simpleName)
                throw e
            }
        }

        // (WebSocket) Sekarang SELALU dicoba di akhir untuk SEMUA mode (tidak ada
        // toggle manual lagi) -- dilakukan di atas socket hasil akhir (TLS/proxy/payload
        // sudah selesai) SEBELUM SSH dimulai. Kalau berhasil, semua byte SSH setelah ini
        // otomatis dibungkus/dibuka sebagai frame WebSocket biner asli. Kalau server/CDN
        // tujuan menolak upgrade-nya (berarti bukan endpoint WebSocket, atau memang tidak
        // pernah dimaksudkan untuk WebSocket sama sekali), socket ini ditutup dan koneksi
        // diulang dari nol tanpa WebSocket (attemptWebSocket=false) -- supaya tetap jalan
        // normal walau payload/server tujuannya sama sekali tidak menyinggung WebSocket.
        if (config.usesWebSocket() && attemptWebSocket) {
            StatusBus.start(StepId.WEBSOCKET)
            val wsHost = config.sslSni?.takeIf { it.isNotBlank() } ?: config.host
            val wsPath = config.wsPath?.takeIf { it.isNotBlank() } ?: "/"
            try {
                WebSocketHandshake.perform(socket, wsHost, wsPath)
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
        val request = "CONNECT $target HTTP/1.1\r\n" +
            "Host: $target\r\n" +
            "Proxy-Connection: Keep-Alive\r\n" +
            "Connection: Keep-Alive\r\n\r\n"

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
            throw IOException("Proxy menolak CONNECT ke $target: $shown")
        }
        Log.i(TAG, "Proxy CONNECT ke $target sukses ($statusLine)")
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
