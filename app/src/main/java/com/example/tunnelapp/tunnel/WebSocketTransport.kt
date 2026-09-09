package com.example.tunnelapp.tunnel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Implementasi client WebSocket (RFC 6455) minimal untuk dipakai sebagai
 * transport tambahan mode WEBSOCKET / WEBSOCKET_SSL.
 *
 * Bukan cuma kirim header upgrade lalu anggap selesai (seperti payload custom
 * di mode SSH_SSL_PAYLOAD) -- setelah handshake sukses, SEMUA byte SSH betul-betul
 * dibungkus jadi frame WebSocket biner (masked, sesuai spec client->server) dan
 * frame dari server di-unbungkus balik jadi byte mentah. Ini penting supaya
 * tunnel tetap valid kalau lewat proxy/CDN/load balancer yang benar-benar
 * memvalidasi framing WebSocket-nya, bukan cuma mengecek header upgrade lalu
 * meneruskan byte mentah apa adanya.
 */
object WebSocketHandshake {
    private const val TAG = "WebSocketHandshake"
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_BYTES = 8192

    /**
     * Kirim request upgrade WebSocket lewat [socket] (bisa socket TLS atau mentah,
     * tergantung mode) dan validasi respons "101 Switching Protocols" + nilai
     * Sec-WebSocket-Accept sesuai spec -- kalau tidak cocok berarti bukan server
     * WebSocket asli (atau di-MITM), koneksi dianggap gagal.
     *
     * @param host header "Host" yang dikirim (biasanya [ServerConfig.sslSni] kalau
     *             diisi, atau host server SSH asli)
     * @param path path HTTP untuk request upgrade, mis. "/" atau "/ws"
     */
    @Throws(IOException::class)
    fun perform(socket: Socket, host: String, path: String) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val usePath = path.ifBlank { "/" }

        val request = buildString {
            append("GET ").append(usePath).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }

        val output = socket.getOutputStream()
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val headerBytes = readUntilDoubleCrlf(socket.getInputStream())
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        val statusLine = lines.firstOrNull().orEmpty()

        if (!Regex("""^HTTP/1\.[01]\s+101\b""").containsMatchIn(statusLine)) {
            throw IOException("Server menolak upgrade WebSocket: ${statusLine.ifBlank { "tidak ada respons" }}")
        }

        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()

        val expectedAccept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
        )
        val actualAccept = headers["sec-websocket-accept"]
        if (actualAccept == null || actualAccept != expectedAccept) {
            throw IOException("Sec-WebSocket-Accept tidak valid -- kemungkinan bukan server WebSocket asli")
        }
    }

    /** Baca byte demi byte (tanpa buffering berlebih) sampai batas akhir header "\r\n\r\n". */
    @Throws(IOException::class)
    private fun readUntilDoubleCrlf(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("Koneksi ditutup sebelum handshake WebSocket selesai")
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
            if (size > MAX_HEADER_BYTES) {
                throw IOException("Respons handshake WebSocket terlalu besar atau tidak valid")
            }
        }
    }
}

/**
 * OutputStream yang membungkus tiap penulisan jadi satu frame WebSocket biner
 * (opcode 0x2), di-mask sesuai spec RFC 6455 untuk arah client->server (server
 * WAJIB menolak frame client yang tidak di-mask, jadi ini bukan opsional).
 */
private class WebSocketFrameOutputStream(private val out: OutputStream) : OutputStream() {
    private val rng = SecureRandom()

    @Synchronized
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val maskKey = ByteArray(4).also { rng.nextBytes(it) }
        out.write(buildHeader(len, maskKey))
        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (b[off + i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        out.write(masked)
    }

    override fun flush() = out.flush()
    override fun close() = out.close()

    private fun buildHeader(len: Int, maskKey: ByteArray): ByteArray {
        val finOpcodeBinary = 0x82 // FIN=1, opcode=2 (binary)
        val maskBit = 0x80
        return when {
            len <= 125 -> byteArrayOf(finOpcodeBinary.toByte(), (maskBit or len).toByte()) + maskKey
            len <= 0xFFFF -> byteArrayOf(
                finOpcodeBinary.toByte(),
                (maskBit or 126).toByte(),
                (len ushr 8).toByte(),
                (len and 0xFF).toByte()
            ) + maskKey
            else -> {
                val lenBytes = ByteArray(8)
                var l = len.toLong()
                for (i in 7 downTo 0) {
                    lenBytes[i] = (l and 0xFF).toByte()
                    l = l ushr 8
                }
                byteArrayOf(finOpcodeBinary.toByte(), (maskBit or 127).toByte()) + lenBytes + maskKey
            }
        }
    }
}

/**
 * InputStream yang membaca frame WebSocket dari [input] dan mengembalikan
 * payload mentahnya saja ke pemanggil (transparan, seolah-olah socket biasa).
 * Frame kontrol (ping/pong/close) ditangani otomatis; ping dibalas pong lewat
 * [controlOut], close dibalas close lalu stream dianggap EOF.
 */
private class WebSocketFrameInputStream(
    private val input: InputStream,
    private val controlOut: OutputStream
) : InputStream() {
    private var currentPayload: ByteArray = ByteArray(0)
    private var currentPos = 0
    private var eof = false

    @Synchronized
    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else (b[0].toInt() and 0xFF)
    }

    @Synchronized
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        while (currentPos >= currentPayload.size) {
            if (eof || !fillNextFrame()) return -1
        }
        val avail = currentPayload.size - currentPos
        val n = minOf(avail, len)
        System.arraycopy(currentPayload, currentPos, b, off, n)
        currentPos += n
        return n
    }

    /** @return false kalau koneksi berakhir (EOF asli atau frame close diterima). */
    private fun fillNextFrame(): Boolean {
        while (true) {
            val b0 = input.read()
            if (b0 == -1) {
                eof = true
                return false
            }
            val opcode = b0 and 0x0F
            val b1 = readByteChecked()
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()
            if (payloadLen == 126L) {
                payloadLen = ((readByteChecked().toLong() shl 8) or readByteChecked().toLong())
            } else if (payloadLen == 127L) {
                var l = 0L
                repeat(8) { l = (l shl 8) or readByteChecked().toLong() }
                payloadLen = l
            }
            val maskKey = if (masked) ByteArray(4).also { readFully(it) } else null
            if (payloadLen < 0 || payloadLen > Int.MAX_VALUE) {
                throw IOException("Panjang frame WebSocket tidak valid: $payloadLen")
            }
            val payload = ByteArray(payloadLen.toInt())
            readFully(payload)
            if (masked && maskKey != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }

            when (opcode) {
                0x0, 0x1, 0x2 -> { // continuation / text / binary -- diperlakukan sebagai data mentah
                    if (payload.isNotEmpty()) {
                        currentPayload = payload
                        currentPos = 0
                        return true
                    }
                    // payload kosong (mis. frame FIN kosong) -- lanjut baca frame berikutnya
                }
                0x8 -> { // close
                    sendControlFrame(0x8, ByteArray(0))
                    eof = true
                    return false
                }
                0x9 -> sendControlFrame(0xA, payload) // ping -> balas pong
                0xA -> { /* pong -- abaikan */ }
                else -> { /* opcode tak dikenal -- abaikan payload-nya, lanjut */ }
            }
        }
    }

    private fun readByteChecked(): Int {
        val v = input.read()
        if (v == -1) throw IOException("Koneksi WebSocket terputus saat membaca frame")
        return v
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw IOException("Koneksi WebSocket terputus saat membaca payload frame")
            off += n
        }
    }

    private fun sendControlFrame(opcode: Int, payload: ByteArray) {
        try {
            val maskKey = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val maskBit = 0x80
            val finOpcode = 0x80 or opcode
            val header = if (payload.size <= 125) {
                byteArrayOf(finOpcode.toByte(), (maskBit or payload.size).toByte()) + maskKey
            } else {
                byteArrayOf(
                    finOpcode.toByte(),
                    (maskBit or 126).toByte(),
                    (payload.size ushr 8).toByte(),
                    (payload.size and 0xFF).toByte()
                ) + maskKey
            }
            val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
            synchronized(controlOut) {
                controlOut.write(header)
                controlOut.write(masked)
                controlOut.flush()
            }
        } catch (_: Exception) {
            // Gagal kirim ping/pong balasan bukan alasan menjatuhkan tunnel --
            // biarkan pump utama yang mendeteksi socket putus kalau memang putus.
        }
    }

    override fun close() = input.close()
}

/**
 * Membungkus [delegate] (socket TCP mentah atau SSLSocket, tergantung mode)
 * supaya getInputStream()/getOutputStream() mengembalikan versi yang otomatis
 * membungkus/membuka frame WebSocket -- kode pemanggil (StreamPump) tetap
 * memperlakukannya seperti Socket biasa, tidak perlu tahu soal framing.
 */
class WebSocketSocket(private val delegate: Socket) : Socket() {
    private val framedOut: OutputStream by lazy { WebSocketFrameOutputStream(delegate.getOutputStream()) }
    private val framedIn: InputStream by lazy { WebSocketFrameInputStream(delegate.getInputStream(), delegate.getOutputStream()) }

    override fun getInputStream(): InputStream = framedIn
    override fun getOutputStream(): OutputStream = framedOut
    override fun close() = delegate.close()
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}
