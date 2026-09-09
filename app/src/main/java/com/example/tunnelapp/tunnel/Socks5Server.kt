package com.example.tunnelapp.tunnel

import android.util.Log
import com.trilead.ssh2.Connection
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Implementasi SOCKS5 sendiri (tanpa autentikasi) -- menggantikan peran
 * jsocks di HTTP Custom. API internal jsocks terlalu tidak terdokumentasi
 * (banyak fork dengan struktur beda-beda) untuk dipastikan benar tanpa
 * risiko salah tebak, jadi bagian SOCKS5 murni ditulis sendiri di sini.
 *
 * Mendukung 2 command:
 *  - CONNECT (0x01): trafik TCP biasa (HTTP/HTTPS/dll), diteruskan lewat
 *    Connection.createLocalStreamForwarder() -- API RESMI trilead-ssh2
 *    untuk membuka "direct-tcpip channel" di dalam tunnel SSH.
 *  - UDP ASSOCIATE (0x03): PENTING untuk DNS. hev-socks5-tunnel mengirim
 *    query DNS device sebagai paket UDP ke sini. Protokol SSH sendiri
 *    TIDAK BISA forward UDP mentah (hanya TCP), jadi query DNS-nya
 *    di-relay sebagai DNS-over-TCP (RFC 1035 -- prefix panjang 2 byte)
 *    lewat direct-tcpip channel yang sama, lalu hasilnya dibungkus balik
 *    jadi paket UDP ke pemanggil. Ini kenapa sebelumnya tunnel "connect"
 *    sukses (semua step hijau) tapi device tidak bisa browsing sama
 *    sekali -- device gagal resolve domain apapun karena request UDP-nya
 *    dulu langsung ditolak (cmd != 0x01 -> connection ditutup).
 *    UDP non-DNS (port != 53) tetap tidak bisa diteruskan lewat SSH biasa,
 *    ini batasan protokol SSH itu sendiri, bukan bug.
 */
class Socks5Server(private val sshConnection: Connection) {

    companion object {
        private const val TAG = "Socks5Server"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    // PENTING (fix bug "semua situs ERR_CONNECTION_RESET" setelah DNS relay
    // ditambahkan): sshConnection dipakai bersama oleh banyak thread (thread
    // CONNECT per tab/resource browser, DITAMBAH beberapa thread relay DNS
    // yang jalan paralel). trilead-ssh2 TIDAK dijamin aman kalau
    // createLocalStreamForwarder() (operasi "buka channel baru" di level
    // protokol SSH) dipanggil dari beberapa thread SECARA BERSAMAAN --
    // permintaan buka channel yang nyelonong bareng bisa saling menabrak di
    // level protokol dan merusak koneksi SSH itu sendiri (dampaknya: SEMUA
    // request lain di koneksi yang sama ikut ke-reset, bukan cuma yang
    // nabrak). Kunci ini memastikan "buka channel" selalu antre satu-satu;
    // transfer data SETELAH channel terbuka tetap jalan paralel seperti
    // biasa (lock ini cuma dipegang sebentar, bukan selama koneksi hidup).
    private val channelOpenLock = Any()

    fun start(port: Int) {
        val ss = ServerSocket()
        ss.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = ss
        running = true

        acceptThread = Thread({
            while (running) {
                try {
                    val client = ss.accept()
                    Thread({ handleClient(client) }, "socks5-client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error accept SOCKS5", e)
                }
            }
        }, "socks5-accept").apply { start() }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        acceptThread?.interrupt()
    }

    private fun handleClient(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            // --- Greeting SOCKS5 ---
            val ver = input.readUnsignedByte()
            if (ver != 0x05) {
                client.close(); return
            }
            val nMethods = input.readUnsignedByte()
            input.skipBytes(nMethods)
            output.write(byteArrayOf(0x05, 0x00)) // no auth required
            output.flush()

            // --- Request ---
            val reqVer = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // reserved
            val atyp = input.readUnsignedByte()

            val targetHost: String = when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress!!
                }
                0x03 -> { // domain name
                    val len = input.readUnsignedByte()
                    val domainBytes = ByteArray(len)
                    input.readFully(domainBytes)
                    String(domainBytes, Charsets.US_ASCII)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress!!
                }
                else -> {
                    client.close(); return
                }
            }
            val targetPort = input.readUnsignedShort()

            if (reqVer != 0x05) {
                client.close(); return
            }

            when (cmd) {
                0x01 -> handleConnect(client, input, output, targetHost, targetPort)
                0x03 -> handleUdpAssociate(client, output)
                else -> client.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handle SOCKS5 client", e)
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** CONNECT (TCP) -- sama seperti sebelumnya, lewat direct-tcpip channel SSH. */
    private fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
        targetHost: String,
        targetPort: Int
    ) {
        try {
            val forwarder = synchronized(channelOpenLock) {
                sshConnection.createLocalStreamForwarder(targetHost, targetPort)
            }

            // --- Reply sukses (alamat bind di-nol-kan, umum untuk server SOCKS5 minimal) ---
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            val forwarderIn = forwarder.inputStream
            val forwarderOut = forwarder.outputStream

            val upstreamThread = Thread({
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = client.getInputStream().read(buf)
                        if (n == -1) break
                        forwarderOut.write(buf, 0, n)
                        forwarderOut.flush()
                    }
                } catch (_: Exception) {
                }
            }, "socks5-upstream").apply { isDaemon = true; start() }

            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = forwarderIn.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                upstreamThread.interrupt()
                try { client.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error CONNECT SOCKS5", e)
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * UDP ASSOCIATE -- dipakai hev-socks5-tunnel untuk relay DNS device.
     * Buka UDP relay socket lokal, kasih tahu alamatnya ke client lewat
     * reply, lalu setiap paket UDP yang masuk (isinya query DNS) di-relay
     * sebagai DNS-over-TCP lewat channel SSH yang sama.
     */
    private fun handleUdpAssociate(tcpClient: Socket, output: java.io.OutputStream) {
        val udpSocket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val boundPort = udpSocket.localPort

        // --- Reply: kasih tahu client alamat+port UDP relay yang baru dibuka ---
        val reply = ByteArray(10)
        reply[0] = 0x05; reply[1] = 0x00; reply[2] = 0x00; reply[3] = 0x01
        val bindAddr = InetAddress.getByName("127.0.0.1").address
        System.arraycopy(bindAddr, 0, reply, 4, 4)
        reply[8] = ((boundPort shr 8) and 0xFF).toByte()
        reply[9] = (boundPort and 0xFF).toByte()
        output.write(reply)
        output.flush()

        // Selama koneksi TCP kontrol ini masih hidup, asosiasi UDP tetap berlaku.
        // Kalau TCP kontrolnya ditutup (client selesai), tutup juga UDP relay-nya.
        val ctlThread = Thread({
            try {
                while (tcpClient.getInputStream().read() != -1) { /* abaikan */ }
            } catch (_: Exception) {
            }
            udpSocket.close()
        }, "socks5-udp-ctl").apply { isDaemon = true; start() }

        try {
            val buf = ByteArray(65535)
            while (!udpSocket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                udpSocket.receive(packet)
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                relayUdpPacket(udpSocket, packet.address, packet.port, data)
            }
        } catch (_: Exception) {
        } finally {
            udpSocket.close()
            ctlThread.interrupt()
            try { tcpClient.close() } catch (_: Exception) {}
        }
    }

    /** Parse header SOCKS5 UDP request, lalu relay isinya (khusus DNS/port 53). */
    private fun relayUdpPacket(
        udpSocket: DatagramSocket,
        replyToAddr: InetAddress,
        replyToPort: Int,
        data: ByteArray
    ) {
        if (data.size < 4) return
        val frag = data[2].toInt() and 0xFF
        if (frag != 0) return // fragmentasi tidak didukung

        val atyp = data[3].toInt() and 0xFF
        var offset = 4
        val destHost: String
        try {
            destHost = when (atyp) {
                0x01 -> {
                    val h = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress!!
                    offset += 4; h
                }
                0x03 -> {
                    val len = data[offset].toInt() and 0xFF
                    offset += 1
                    val h = String(data, offset, len, Charsets.US_ASCII)
                    offset += len; h
                }
                0x04 -> {
                    val h = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress!!
                    offset += 16; h
                }
                else -> return
            }
        } catch (_: Exception) {
            return
        }
        if (offset + 2 > data.size) return
        val destPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        val payload = data.copyOfRange(offset, data.size)

        if (destPort != 53) {
            // UDP selain DNS (mis. QUIC/HTTP3, WebRTC) tidak bisa lewat tunnel
            // SSH biasa -- ini batasan protokol SSH sendiri (cuma forward TCP),
            // sama seperti aplikasi tunnel SSH lain (HTTP Custom, dll). Browser
            // & sebagian besar situs akan otomatis fallback ke TCP kalau QUIC
            // gagal, jadi ini biasanya tidak terasa selain sedikit lebih lambat.
            return
        }

        Thread({
            try {
                val forwarder = synchronized(channelOpenLock) {
                    sshConnection.createLocalStreamForwarder(destHost, 53)
                }
                val fOut = forwarder.outputStream
                val fIn = DataInputStream(forwarder.inputStream)

                // DNS-over-TCP (RFC 1035): payload didahului panjang 2 byte
                fOut.write(byteArrayOf(((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte()))
                fOut.write(payload)
                fOut.flush()

                val lenBytes = ByteArray(2)
                fIn.readFully(lenBytes)
                val respLen = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                val resp = ByteArray(respLen)
                fIn.readFully(resp)

                // Bungkus balik jadi paket UDP format SOCKS5, kirim ke pengirim asal
                val destAddrBytes = InetAddress.getByName(destHost).address
                if (destAddrBytes.size != 4) return@Thread // hanya IPv4 utk sekarang

                val out = ByteArray(10 + resp.size)
                out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0x01
                System.arraycopy(destAddrBytes, 0, out, 4, 4)
                out[8] = ((destPort shr 8) and 0xFF).toByte()
                out[9] = (destPort and 0xFF).toByte()
                System.arraycopy(resp, 0, out, 10, resp.size)

                udpSocket.send(DatagramPacket(out, out.size, replyToAddr, replyToPort))
            } catch (e: Exception) {
                Log.e(TAG, "Gagal relay DNS via DNS-over-TCP ke $destHost", e)
            }
        }, "socks5-dns-relay").apply { isDaemon = true; start() }
    }
}
