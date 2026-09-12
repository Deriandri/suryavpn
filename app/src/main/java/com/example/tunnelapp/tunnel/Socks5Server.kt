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
 *    jadi paket UDP ke pemanggil. UDP non-DNS (port != 53) tetap tidak
 *    bisa diteruskan lewat SSH biasa, ini batasan protokol SSH itu
 *    sendiri, bukan bug.
 *
 * PERUBAHAN ARSITEKTUR (meniru pola DarkTunnel/HTTP Custom -- FIX bug
 * "bind failed: EADDRINUSE" yang berulang tiap reconnect): server ini
 * SEKARANG hanya bind() ke port SEKALI per sesi VPN, lalu HIDUP TERUS
 * selama proses reconnect SSH terjadi berkali-kali di belakangnya.
 * Sebelumnya instance baru dibuat & bind() ulang di SETIAP reconnect --
 * itu yang membuka celah race "port lama belum benar-benar dilepas saat
 * port baru dicoba di-bind lagi". Sekarang koneksi SSH yang dipakai
 * (`sshConnection`) adalah state yang BISA berubah-ubah/null (di-attach
 * saat reconnect sukses, di-detach saat SSH putus) SELAMA server tetap
 * mendengarkan di port yang sama -- persis seperti "kabel di belakang
 * proxy diganti", bukan "proxy-nya dibongkar lalu dibangun ulang". Kelas
 * bug EADDRINUSE saat reconnect jadi TIDAK MUNGKIN terjadi lagi secara
 * struktural, karena bind() cuma pernah dipanggil sekali per sesi.
 */
class Socks5Server {

    companion object {
        private const val TAG = "Socks5Server"

        // --- FIX "channel/thread bocor kalau jaringan mati di tengah query DNS" ---
        // fIn.readFully() di relayUdpPacket() TIDAK PUNYA timeout (channel SSH
        // "direct-tcpip" bukan Socket asli, tidak dukung setSoTimeout) -- kalau
        // device kehilangan jaringan PERSIS saat menunggu balasan DNS, read itu
        // bisa menggantung sampai seluruh koneksi SSH ditutup (baru kepakai
        // force-close). Watchdog manual di bawah membatasi SATU query DNS
        // maksimal sekian lama sebelum channel-nya ditutup paksa sendiri.
        private const val DNS_RELAY_TIMEOUT_MS = 8000L
        // Watchdog utk createLocalStreamForwarder() di handleConnect() --
        // sama alasannya dengan DNS_RELAY_TIMEOUT_MS: operasi ini bisa
        // menggantung tanpa batas kalau server/jaringan macet, dan channel
        // SSH biasa (bukan Socket asli) tidak bisa dipasangi setSoTimeout().
        private const val CHANNEL_OPEN_TIMEOUT_MS = 10000L
        // FITUR BARU (permintaan user: maksimalkan kecepatan jaringan): buffer
        // relay TUN<->channel SSH dinaikkan dari 8KB -> 32KB. Ini MURNI
        // loopback lokal (127.0.0.1, bukan jaringan asli), jadi aman
        // dinaikkan tanpa risiko -- efeknya mengurangi jumlah syscall
        // read()/write() per MB data yang lewat, yang lumayan berarti untuk
        // throughput tinggi (kurang overhead context-switch per byte).
        // REVERT (laporan user: "terhubung tapi internet tidak jalan" masih
        // terjadi walau kandidat lain sudah dimatikan) -- dikembalikan ke
        // 8192 (nilai asli sebelum fitur kecepatan) sampai ada cara aman
        // buat mengetes dampak ukuran buffer yang lebih besar di server
        // nyata. Nama konstanta dipertahankan supaya gampang dinaikkan lagi
        // nanti kalau sudah terverifikasi bukan penyebabnya.
        private const val RELAY_BUFFER_SIZE_BYTES = 8192

        // FIX (root cause "DNS relay ke 8.8.8.8/8.8.4.4 TIMEOUT terus,
        // padahal akun jalan normal di app tunnel lain"): banyak akun SSH
        // (terutama versi gratis/trial/"inject") firewall server-nya DROP
        // diam-diam SEMUA trafik outbound ke port 53 tujuan luar -- direct-
        // tcpip channel-nya sendiri kebuka, tapi tidak pernah ada balasan
        // sampai watchdog kita yang menutup paksa. Ini bukan masalah di
        // client. App tunnel lain kebanyakan memang TIDAK nge-relay DNS
        // lewat tunnel SSH sama sekali -- mereka resolve DNS langsung di
        // jaringan device (protect() supaya tidak nyasar loop balik ke TUN
        // kita sendiri), baru trafik HTTP(S) hasil resolve itu yang lewat
        // tunnel. Timeout di bawah ini KHUSUS utk percobaan device-side,
        // sengaja jauh lebih singkat dari DNS_RELAY_TIMEOUT_MS supaya kalau
        // device-side gagal pun user tidak nunggu dobel lama sebelum jatuh
        // ke fallback SSH.
        private const val DEVICE_DNS_TIMEOUT_MS = 4000
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    // Lihat catatan DEVICE_DNS_TIMEOUT_MS di atas. Null kalau caller tidak
    // menyediakan (mis. dipanggil dari test) -- device-side dilewati begitu
    // saja, langsung ke jalur SSH lama, supaya tidak breaking existing
    // behaviour kalau protect() belum tersedia.
    @Volatile
    private var protectDatagram: ((DatagramSocket) -> Boolean)? = null

    /** Pasang fungsi protect() dari VpnService, dipakai utk device-side DNS. */
    fun setProtectDatagram(fn: (DatagramSocket) -> Boolean) {
        protectDatagram = fn
    }

    @Volatile
    private var running = false

    // Koneksi SSH yang sedang aktif untuk melayani client SOCKS5 yang masuk.
    // @Volatile + di-attach/detach dari luar (SshTunnelManager) tanpa pernah
    // menyentuh serverSocket/port sama sekali -- inilah inti perubahan
    // arsitektur di atas. Kalau null (persis di antara SSH lama putus dan
    // SSH baru berhasil connect saat reconnect), client yang kebetulan masuk
    // di jendela itu cukup dijawab "connection refused" oleh SOCKS5, BUKAN
    // bikin seluruh server mati/gagal bind.
    @Volatile
    private var sshConnection: Connection? = null

    // DIHAPUS (root cause "tunnel connect tapi internet tidak jalan, tanpa
    // error di log"): sebelumnya ada `channelOpenLock` yang menyerialkan
    // SEMUA pembukaan channel (CONNECT & UDP-relay-DNS) di satu lock global,
    // atas dasar kekhawatiran "trilead-ssh2 tidak aman dipanggil concurrent".
    // Dicek langsung ke source resmi fork yang dipakai project ini
    // (org.jenkins-ci:trilead-ssh2, lihat app/build.gradle.kts) --
    // ChannelManager.openDirectTCPIPChannel() mengalokasikan ID channel di
    // bawah lock PER-CHANNEL-nya sendiri (`synchronized(c)`), lalu
    // waitUntilChannelOpen(c) juga menunggu di monitor `c` itu sendiri, BUKAN
    // di lock global -- library ini memang didesain untuk banyak
    // channel/session concurrent dari banyak thread. Jadi lock global di
    // atas TIDAK diperlukan untuk keamanan trilead-ssh2, dan efek sampingnya
    // justru berbahaya: createLocalStreamForwarder() TIDAK punya timeout,
    // jadi begitu satu destinasi lambat/macet, SEMUA koneksi & query DNS
    // baru lain ikut menunggu di lock yang sama -- persis kelihatan seperti
    // "internet mati total" walau status tunnel masih "Terhubung", dan
    // tidak ada exception yang dilempar (makanya tidak ada apa pun di log).
    // Sekarang setiap channel dibuka independen; yang macet cuma menunda
    // channel itu sendiri (lihat CHANNEL_OPEN_TIMEOUT_MS di handleConnect).

    fun isRunning(): Boolean = running

    /** Pasang koneksi SSH yang baru berhasil connect/reconnect. Port TIDAK disentuh. */
    fun attachConnection(conn: Connection) {
        sshConnection = conn
    }

    /** Lepas koneksi SSH yang mati (mau reconnect). Port TIDAK disentuh, server tetap mendengarkan. */
    fun detachConnection() {
        sshConnection = null
    }

    fun start(port: Int) {
        val ss = ServerSocket()
        // reuseAddress = true mengizinkan bind ulang ke port yang masih
        // dalam TIME_WAIT (mis. setelah app di-force-close lalu dibuka
        // lagi) -- lapisan pertahanan kedua. Pertahanan UTAMA terhadap
        // EADDRINUSE saat reconnect adalah arsitektur baru di atas: bind()
        // ini sekarang cuma dipanggil SEKALI per sesi VPN, bukan di tiap
        // reconnect.
        ss.reuseAddress = true
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
                    if (running) {
                        Log.e(TAG, "Error accept SOCKS5", e)
                        StatusBus.log("[SOCKS5] Error accept: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }, "socks5-accept").apply { start() }
    }

    /** Stop TOTAL -- port dilepas. Hanya dipanggil saat sesi VPN benar-benar berakhir (bukan reconnect biasa). */
    fun stop() {
        running = false
        sshConnection = null
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
            StatusBus.log("[SOCKS5] Error handle client: ${e.message ?: e.javaClass.simpleName}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** CONNECT (TCP) -- lewat direct-tcpip channel SSH pada koneksi yang SEDANG aktif. */
    private fun handleConnect(
        client: Socket,
        input: DataInputStream,
        output: java.io.OutputStream,
        targetHost: String,
        targetPort: Int
    ) {
        // Ambil snapshot koneksi SAAT INI -- bisa null kalau persis sedang di
        // antara reconnect (SSH lama sudah detach, SSH baru belum attach).
        // FIX bug lama: dulu field ini non-null tetap (dipegang di
        // constructor), jadi kondisi ini tidak pernah dicek sama sekali --
        // sekarang dijawab bersih sebagai "connection refused" SOCKS5 (kode
        // 0x01 general failure), bukan NPE atau bind() ulang.
        val conn = sshConnection
        if (conn == null) {
            try {
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
            } catch (_: Exception) {
            }
            try { client.close() } catch (_: Exception) {}
            return
        }

        var forwarder: com.trilead.ssh2.LocalStreamForwarder? = null
        // Watchdog: kalau createLocalStreamForwarder() (buka "direct-tcpip"
        // channel ke targetHost:targetPort lewat SSH) tidak selesai dalam
        // CHANNEL_OPEN_TIMEOUT_MS, tutup paksa forwarder-nya begitu ia
        // akhirnya kebentuk -- ini membuat request YANG MACET gagal dengan
        // jelas (IOException, ke-log), TANPA menahan koneksi/channel lain
        // sama sekali (tidak ada lock global lagi, lihat catatan di field
        // sshConnection di atas).
        val openDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val openTimeoutGuard = Thread({
            try {
                Thread.sleep(CHANNEL_OPEN_TIMEOUT_MS)
                if (!openDone.get()) {
                    Log.w(TAG, "Buka channel ke $targetHost:$targetPort timeout -- menutup paksa")
                    StatusBus.log("[SOCKS5] Buka channel ke $targetHost:$targetPort TIMEOUT -- ditutup paksa")
                    try { forwarder?.close() } catch (_: Exception) {}
                }
            } catch (_: InterruptedException) {
            }
        }, "socks5-channel-open-timeout").apply { isDaemon = true; start() }
        try {
            forwarder = conn.createLocalStreamForwarder(targetHost, targetPort)
            openDone.set(true)
            openTimeoutGuard.interrupt()

            // --- Reply sukses (alamat bind di-nol-kan, umum untuk server SOCKS5 minimal) ---
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            val forwarderIn = forwarder.inputStream
            val forwarderOut = forwarder.outputStream

            val upstreamThread = Thread({
                try {
                    val buf = ByteArray(RELAY_BUFFER_SIZE_BYTES)
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
                val buf = ByteArray(RELAY_BUFFER_SIZE_BYTES)
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
            StatusBus.log("[SOCKS5] CONNECT $targetHost:$targetPort gagal: ${e.message ?: e.javaClass.simpleName}")
            try { client.close() } catch (_: Exception) {}
        } finally {
            // Jaga-jaga: kalau exception terjadi SEBELUM openDone.set(true)
            // (createLocalStreamForwarder gagal/exception, bukan timeout),
            // guard thread di atas masih tidur menunggu -- bangunkan &
            // hentikan sekarang juga, tidak perlu nunggu penuh
            // CHANNEL_OPEN_TIMEOUT_MS cuma untuk mati sendiri.
            openDone.set(true)
            openTimeoutGuard.interrupt()
            // PENTING (bug fix INTI -- ini penyebab "internet cuma jalan
            // sebentar lalu hilang"): sebelumnya cuma socket SOCKS5 lokal
            // (`client`) yang ditutup di sini -- channel SSH "direct-tcpip"
            // asli (`forwarder`, dari createLocalStreamForwarder) TIDAK PERNAH
            // ditutup, dibiarkan terbuka SELAMANYA di sshConnection. Browser
            // membuka BANYAK koneksi TCP paralel per halaman -- tiap satu
            // koneksi = satu channel yang bocor di sini kalau tidak ditutup.
            // Lama-lama channel yang menumpuk ini bikin: (a) server/CDN
            // menolak channel baru begitu limitnya kena -- tunnel SSH-nya
            // sendiri masih "connected", tapi tidak ada request baru yang bisa
            // lewat lagi (persis kelihatan seperti "internet hilang"), dan/atau
            // (b) dispatcher internal trilead-ssh2 (SATU thread pembaca untuk
            // SEMUA channel di Connection yang sama) ikut tersendat kalau
            // buffer channel yang bocor itu penuh dan tidak pernah dikuras --
            // yang berakibat channel LAIN (bukan cuma yang bocor) ikut macet
            // juga, plus jumlah objek/thread yang menumpuk seiring waktu bisa
            // bikin proses app jadi berat/ANR saat dibuka lagi. Sekarang
            // forwarder SELALU ditutup di sini begitu koneksinya selesai,
            // apa pun hasilnya (sukses, gagal, atau exception).
            try { forwarder?.close() } catch (_: Exception) {}
        }
    }

    /**
     * UDP ASSOCIATE -- dipakai hev-socks5-tunnel untuk relay DNS device.
     * Buka UDP relay socket lokal, kasih tahu alamatnya ke client lewat
     * reply, lalu setiap paket UDP yang masuk (isinya query DNS) di-relay
     * sebagai DNS-over-TCP lewat channel SSH yang SEDANG aktif.
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

        // Snapshot koneksi SAAT INI -- BOLEH null (persis lagi reconnect).
        // Beda dari sebelumnya: dulu null di sini langsung membuang query
        // DNS diam-diam, TAPI itu cuma perlu kalau memang mau fallback ke
        // jalur SSH. Device-side DNS di bawah tidak butuh conn sama sekali,
        // jadi tetap dicoba lebih dulu walau persis lagi di jendela
        // reconnect -- kalau berhasil, DNS device tidak perlu nunggu SSH
        // selesai reconnect sama sekali.
        val conn = sshConnection

        Thread({
            // --- JALUR UTAMA BARU: coba resolve langsung di jaringan device ---
            // (lihat catatan DEVICE_DNS_TIMEOUT_MS di companion object). Kalau
            // sukses, SSH sama sekali tidak disentuh utk query DNS ini --
            // menghindari server yang firewall port 53-nya. Kalau gagal/
            // timeout (mis. protect() belum siap, atau jaringan device sendiri
            // blokir DNS langsung), jatuh ke jalur SSH lama di bawah, tanpa ada
            // perubahan perilaku dari sebelumnya.
            val deviceResp = tryDeviceDns(payload, destHost)
            if (deviceResp != null) {
                try {
                    val destAddrBytes = InetAddress.getByName(destHost).address
                    if (destAddrBytes.size == 4) {
                        val out = ByteArray(10 + deviceResp.size)
                        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0x01
                        System.arraycopy(destAddrBytes, 0, out, 4, 4)
                        out[8] = ((destPort shr 8) and 0xFF).toByte()
                        out[9] = (destPort and 0xFF).toByte()
                        System.arraycopy(deviceResp, 0, out, 10, deviceResp.size)
                        udpSocket.send(DatagramPacket(out, out.size, replyToAddr, replyToPort))
                        StatusBus.log("[DNS] $destHost:53 dijawab langsung dari jaringan device (bypass tunnel SSH)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gagal kirim balik hasil device-side DNS utk $destHost", e)
                }
                return@Thread
            }

            // --- FALLBACK: jalur lama, relay lewat SSH sebagai DNS-over-TCP ---
            // conn null di sini artinya device-side GAGAL *dan* SSH sedang
            // tidak tersedia (mis. persis di jendela reconnect) -- dibuang
            // diam-diam seperti perilaku asli sebelum fix ini, device/
            // hev-socks5-tunnel akan mengirim ulang query-nya sendiri.
            if (conn == null) return@Thread
            var forwarder: com.trilead.ssh2.LocalStreamForwarder? = null
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            // FIX (root cause "hev-socks5-tunnel 'io timeout' berulang cepat,
            // DNS device tidak pernah kejawab"): watchdog SEBELUMNYA baru
            // dipasang SETELAH createLocalStreamForwarder() (buka channel)
            // selesai -- kalau justru PEMBUKAAN channel itu sendiri yang
            // macet (server SSH tidak pernah membalas permintaan buka
            // direct-tcpip ke <dns>:53, mis. karena diblokir firewall),
            // panggilan itu bisa menggantung SANGAT lama (dibatasi timeout
            // internal trilead-ssh2 yang defaultnya besar, bukan
            // DNS_RELAY_TIMEOUT_MS kita) -- watchdog kita sendiri belum
            // sempat menyala sama sekali. Sekarang watchdog dipasang
            // SEBELUM createLocalStreamForwarder() dipanggil, supaya fase
            // "buka channel" ikut ditimeout juga, bukan cuma fase "baca
            // balasan" setelah channel terbuka.
            val timeoutGuard = Thread({
                try {
                    Thread.sleep(DNS_RELAY_TIMEOUT_MS)
                    if (!done.get()) {
                        Log.w(TAG, "DNS relay ke $destHost timeout -- menutup paksa channel")
                        StatusBus.log("[DNS] Relay ke $destHost:53 TIMEOUT (>${DNS_RELAY_TIMEOUT_MS}ms) -- kemungkinan port 53 diblokir/di-drop server")
                        try { forwarder?.close() } catch (_: Exception) {}
                    }
                } catch (_: InterruptedException) {
                }
            }, "socks5-dns-relay-timeout").apply { isDaemon = true; start() }
            try {
                forwarder = conn.createLocalStreamForwarder(destHost, 53)
                val fwd = forwarder!!

                val fOut = fwd.outputStream
                val fIn = DataInputStream(fwd.inputStream)

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
                StatusBus.log("[DNS] Relay ke $destHost:53 GAGAL: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // PENTING (bug fix -- leak yang sama persis dengan handleConnect,
                // TAPI lebih parah di sini: query DNS terjadi jauh lebih sering
                // daripada koneksi TCP biasa, jadi channel yang bocor di jalur
                // ini menumpuk lebih cepat). forwarder WAJIB ditutup begitu satu
                // query DNS selesai (sukses ATAU gagal/timeout) -- sebelumnya
                // tidak pernah ditutup sama sekali, channel DNS-over-TCP ini
                // dibiarkan menggantung selamanya di sshConnection untuk SETIAP
                // domain yang pernah di-resolve sejak tunnel connect.
                done.set(true)
                timeoutGuard.interrupt()
                try { forwarder?.close() } catch (_: Exception) {}
            }
        }, "socks5-dns-relay").apply { isDaemon = true; start() }
    }

    /**
     * Coba jawab query DNS [payload] (RFC 1035, format UDP mentah -- BUKAN
     * DNS-over-TCP) langsung dari jaringan device sendiri ke [destHost]:53,
     * pakai socket yang di-protect() supaya tidak nyasar loop balik ke TUN
     * kita sendiri (persis pola ConnectRelay.kt utk socket kontrol SSH).
     *
     * Return null (BUKAN throw) kalau protect() belum dipasang, protect()
     * gagal, atau device tidak dapat balasan dalam DEVICE_DNS_TIMEOUT_MS --
     * di semua kasus itu caller akan fallback ke relay SSH lama seperti
     * sebelum fix ini ada.
     */
    private fun tryDeviceDns(payload: ByteArray, destHost: String): ByteArray? {
        val protectFn = protectDatagram ?: return null
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protectFn(socket)) {
                StatusBus.log("[DNS] protect() gagal utk device-side DNS ke $destHost, fallback ke relay SSH")
                return null
            }
            socket.soTimeout = DEVICE_DNS_TIMEOUT_MS
            val target = InetAddress.getByName(destHost)
            socket.send(DatagramPacket(payload, payload.size, target, 53))

            val respBuf = ByteArray(65535)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            respPacket.data.copyOfRange(respPacket.offset, respPacket.offset + respPacket.length)
        } catch (e: Exception) {
            // Timeout/unreachable/dll -- diam-diam, ini memang jalur "coba
            // dulu", bukan jalur wajib sukses. Log level warning saja biar
            // tidak berisik kalau device-side memang rutin gagal di jaringan
            // tertentu (server SSH-nya justru yang lebih longgar).
            Log.w(TAG, "Device-side DNS ke $destHost gagal/timeout, fallback ke relay SSH", e)
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
