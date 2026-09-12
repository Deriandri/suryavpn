package com.example.tunnelapp.tunnel

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Proxy HTTP lokal OPSIONAL (lihat "HTTP Port" di kartu VPN Setting,
 * [com.example.tunnelapp.model.VpnSettingsStore.httpPort]) -- untuk app lain
 * di device yang hanya mendukung proxy HTTP (bukan SOCKS5).
 *
 * Ini MURNI "penerjemah" (chaining), BUKAN jalur tunnel independen: setiap
 * request yang masuk ke sini diteruskan lagi lewat SOCKS5 lokal yang sama
 * persis yang dipakai TUN engine ([Socks5Server]/Xray-core, di
 * 127.0.0.1:<socksPort efektif>) -- jadi trafiknya tetap lewat tunnel yang
 * sama, cuma "pintu masuk" lokalnya beda protokol.
 *
 * Mendukung dua bentuk request:
 *  - `CONNECT host:port HTTP/1.1` (dipakai browser/app untuk HTTPS): balas
 *    "200 Connection Established" ke client, lalu relay byte mentah dua arah
 *    lewat channel SOCKS5 CONNECT ke host:port yang sama.
 *  - Request HTTP biasa dengan absolute-URI (`GET http://host/path HTTP/1.1`,
 *    format standar proxy HTTP non-transparan): parse host:port dari URI-nya,
 *    tulis ulang baris request jadi origin-form (`GET /path HTTP/1.1`) supaya
 *    valid dikirim ke server tujuan lewat SOCKS5 CONNECT, sisanya (header +
 *    body) diteruskan apa adanya.
 */
class HttpProxyServer {

    companion object {
        private const val TAG = "HttpProxyServer"
        private const val SOCKS_HANDSHAKE_TIMEOUT_MS = 8000
        // FITUR BARU (maksimalkan kecepatan): sama seperti Socks5Server/StreamPump.
        private const val PUMP_BUFFER_SIZE_BYTES = 32768
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    /** Port SOCKS5 lokal tujuan chaining -- lihat catatan kelas di atas. */
    @Volatile
    private var socksPort: Int = 0

    fun start(httpPort: Int, socksPort: Int) {
        this.socksPort = socksPort
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", httpPort))
        serverSocket = ss
        running = true

        acceptThread = Thread({
            while (running) {
                try {
                    val client = ss.accept()
                    Thread({ handleClient(client) }, "http-proxy-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error accept HTTP proxy", e)
                }
            }
        }, "http-proxy-accept").apply { start() }
        Log.i(TAG, "HTTP proxy lokal aktif di 127.0.0.1:$httpPort -> chaining ke SOCKS5 127.0.0.1:$socksPort")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = SOCKS_HANDSHAKE_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: run { client.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 3) {
                client.close(); return
            }
            val method = parts[0]
            val target = parts[1]
            val version = parts[2]

            // Baca sisa header request (dibutuhkan untuk mode HTTP biasa yang
            // diteruskan apa adanya, dan supaya buffer input tidak ketinggalan
            // data untuk kasus CONNECT sekalipun -- CONNECT tidak punya body
            // header tambahan yang perlu diteruskan, langsung dibuang).
            val headerLines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headerLines += line
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(client, target)
            } else {
                handleForwardedHttp(client, method, target, version, headerLines)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error menangani client HTTP proxy", e)
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val idx = hostPort.lastIndexOf(':')
        if (idx <= 0) return hostPort to defaultPort
        val host = hostPort.substring(0, idx)
        val port = hostPort.substring(idx + 1).toIntOrNull() ?: defaultPort
        return host to port
    }

    private fun handleConnect(client: Socket, target: String) {
        val (host, port) = parseHostPort(target, 443)
        val upstream = try {
            openSocksConnection(host, port)
        } catch (e: IOException) {
            Log.w(TAG, "CONNECT $host:$port gagal lewat SOCKS5 lokal", e)
            try {
                client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                )
            } catch (_: Exception) {
            }
            client.close()
            return
        }

        client.getOutputStream().write(
            "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        )
        client.soTimeout = 0
        relayBidirectional(client, upstream)
    }

    private fun handleForwardedHttp(
        client: Socket,
        method: String,
        target: String,
        version: String,
        headerLines: List<String>
    ) {
        // absolute-URI standar proxy HTTP: "http://host[:port]/path..."
        val withoutScheme = target.substringAfter("://", target)
        val slashIdx = withoutScheme.indexOf('/')
        val hostPort = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
        val path = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
        val (host, port) = parseHostPort(hostPort, 80)

        val upstream = try {
            openSocksConnection(host, port)
        } catch (e: IOException) {
            Log.w(TAG, "Request HTTP ke $host:$port gagal lewat SOCKS5 lokal", e)
            try {
                client.getOutputStream().write(
                    "HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                )
            } catch (_: Exception) {
            }
            client.close()
            return
        }

        client.soTimeout = 0
        val out = upstream.getOutputStream()
        // Tulis ulang baris request jadi origin-form + header apa adanya
        // (kecuali "Proxy-Connection" yang murni istilah antara client<->proxy,
        // tidak relevan/valid dikirim ke server tujuan).
        out.write("$method $path $version\r\n".toByteArray(Charsets.ISO_8859_1))
        for (line in headerLines) {
            if (line.startsWith("Proxy-Connection", ignoreCase = true)) continue
            out.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()

        relayBidirectional(client, upstream)
    }

    /**
     * Handshake SOCKS5 minimal (tanpa autentikasi) ke SOCKS5 lokal yang sudah
     * aktif dari TUN engine ([Socks5Server]/Xray-core), lalu kirim CONNECT ke
     * [host]:[port] -- sama persis semantik yang dipakai [Socks5Server]
     * sendiri di sisi lain (di sini kita berperan sebagai CLIENT SOCKS5,
     * bukan server-nya).
     */
    private fun openSocksConnection(host: String, port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), SOCKS_HANDSHAKE_TIMEOUT_MS)
        socket.soTimeout = SOCKS_HANDSHAKE_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // Greeting: versi 5, 1 metode, no-auth (0x00).
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greetingReply = ByteArray(2)
        readFully(input, greetingReply)
        if (greetingReply[0] != 0x05.toByte() || greetingReply[1] != 0x00.toByte()) {
            socket.close()
            throw IOException("SOCKS5 lokal menolak greeting (no-auth tidak didukung?)")
        }

        // Request CONNECT (0x01) dengan address type domain (0x03) --
        // biarkan resolusi DNS dilakukan di sisi SOCKS5/tunnel, bukan lokal
        // di proxy HTTP ini.
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05
        request[1] = 0x01 // CONNECT
        request[2] = 0x00 // reserved
        request[3] = 0x03 // ATYP domain
        request[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
        request[5 + hostBytes.size] = ((port shr 8) and 0xFF).toByte()
        request[6 + hostBytes.size] = (port and 0xFF).toByte()
        output.write(request)
        output.flush()

        val replyHeader = ByteArray(4)
        readFully(input, replyHeader)
        if (replyHeader[1] != 0x00.toByte()) {
            socket.close()
            throw IOException("SOCKS5 lokal menolak CONNECT ke $host:$port (kode ${replyHeader[1]})")
        }
        // Buang sisa alamat BND.ADDR/BND.PORT sesuai ATYP balasan supaya
        // stream berikutnya benar-benar mulai dari payload asli.
        val skip = when (replyHeader[3].toInt()) {
            0x01 -> 4 + 2
            0x03 -> (input.read() and 0xFF) + 2
            0x04 -> 16 + 2
            else -> 0
        }
        if (skip > 0) readFully(input, ByteArray(skip))

        socket.soTimeout = 0
        return socket
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("Koneksi SOCKS5 lokal terputus saat handshake")
            offset += read
        }
    }

    private fun relayBidirectional(a: Socket, b: Socket) {
        val t1 = Thread({ pump(a.getInputStream(), b.getOutputStream()) }, "http-proxy-pump-ab").apply {
            isDaemon = true
            start()
        }
        pump(b.getInputStream(), a.getOutputStream())
        try {
            t1.join(2000)
        } catch (_: InterruptedException) {
        }
        try {
            a.close()
        } catch (_: Exception) {
        }
        try {
            b.close()
        } catch (_: Exception) {
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(PUMP_BUFFER_SIZE_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // Koneksi salah satu sisi ditutup/putus -- normal saat salah satu
            // pihak selesai atau tunnel mati, tidak perlu ditangani khusus.
        }
    }
}
