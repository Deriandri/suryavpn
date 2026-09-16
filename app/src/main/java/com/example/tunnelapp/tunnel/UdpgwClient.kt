package com.example.tunnelapp.tunnel

import android.util.Log
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client protokol udpgw (badvpn-udpgw / "SocksOverUdpgw", protokol yang sama
 * dipakai HTTP Injector/HTTP Custom/DarkTunnel utk trafik UDP non-DNS lewat
 * tunnel SSH) -- lihat catatan panjang di [Socks5Server] soal KENAPA ini
 * dibutuhkan: SSH sendiri cuma bisa forward TCP ("direct-tcpip" channel),
 * jadi paket UDP asli (game, QUIC/HTTP3, VoIP, dll) tidak bisa lewat SSH
 * mentah sama sekali. udpgw adalah proses TERPISAH yang jalan di SERVER
 * (biasanya bind ke 127.0.0.1:7300 di sisi server, dijalankan admin server
 * lewat `badvpn-udpgw`), yang client (app ini) hubungi lewat SATU
 * "direct-tcpip" channel SSH biasa ke [REMOTE_HOST]:[remotePort] -- di
 * dalam channel TCP itulah paket-paket UDP (dari BANYAK flow/tujuan
 * berbeda sekaligus) dibungkus/multiplexed pakai framing biner udpgw,
 * dikirim ke server, lalu server yang benar-benar mengirim/menerima UDP
 * asli ke internet atas nama client.
 *
 * FORMAT WIRE (persis spesifikasi resmi badvpn, `udpgw_proto.h`):
 *   [uint16 LE: panjang sisa frame][flags: 1 byte][conid: uint16 LE][alamat tujuan, KECUALI flag DNS][payload]
 * Alamat tujuan (IPv4 saja yang didukung di sini, sama seperti bagian lain
 * app ini -- lihat komentar "hanya IPv4 utk sekarang" di Socks5Server):
 *   [addr_ip: 4 byte network-order][addr_port: uint16 BIG-ENDIAN]
 * Jadi total header+addr (tanpa DNS/IPv6) = 1+2+4+2 = 9 byte, ditambah payload.
 *
 * SATU instance ini PERSISTEN untuk seluruh umur sesi VPN (persis pola
 * [Socks5Server]): [attachConnection]/[detachConnection] dipanggil
 * [SshjTunnelManager] setiap kali koneksi SSH connect/reconnect/putus, TANPA
 * membongkar instance ini sendiri. Channel TCP ke udpgw server dibuka
 * MALAS (lazy) -- baru benar-benar dibuka saat paket UDP pertama yang perlu
 * dikirim datang (lewat [sendPacket]) -- lalu dipakai ulang terus sampai
 * putus/error, baru dibuka ulang lagi otomatis di paket berikutnya.
 *
 * Satu flow UDP (dikenali dari kombinasi [flowOwner] -- biasanya `DatagramSocket`
 * milik satu sesi SOCKS5 UDP ASSOCIATE di [Socks5Server] -- plus alamat & port
 * tujuan) dipetakan ke satu "conid" (connection id 16-bit) yang konsisten
 * selama flow itu masih aktif, supaya balasan dari server bisa dicocokkan
 * balik ke flow yang benar DAN supaya server tahu paket-paket itu satu
 * "sesi UDP" yang sama (penting utk game/VoIP yang butuh NAT mapping stabil).
 */
class UdpgwClient(private val remotePort: Int) {

    companion object {
        private const val TAG = "UdpgwClient"

        // udpgw server SELALU diasumsikan bind ke loopback di SISI SERVER SSH
        // (konvensi baku badvpn-udpgw/HTTP Injector demi keamanan -- server
        // TIDAK dimaksudkan diakses selain lewat tunnel SSH-nya sendiri),
        // makanya cuma port yang perlu dikonfigurasi user (lihat
        // VpnSettingsStore.udpgwPort), bukan host.
        private const val REMOTE_HOST = "127.0.0.1"

        private const val FLAG_KEEPALIVE = 0x01
        // FLAG_REBIND ada di spesifikasi resmi tapi sengaja TIDAK pernah
        // dikirim dari client ini: cuma relevan utk skenario client
        // berpindah alamat sumber (tidak berlaku di sini, satu channel TCP
        // tetap dipakai ulang).
        // CATATAN (update): FLAG_DNS TIDAK dipakai di sini, TAPI itu bukan
        // berarti query DNS tidak lewat UdpgwClient sama sekali. Sejak fix
        // "ikuti alur HTTP Custom", Socks5Server.relayUdpPacket() memang
        // mengirim query DNS ke sini (kalau udpgwClient dikonfigurasi) --
        // hanya saja dikirim TANPA flag DNS, pakai frame biasa dengan alamat
        // IPv4 lengkap (persis seperti paket UDP non-DNS lain), supaya tidak
        // perlu variasi framing ekstra di kelas ini. DNS-over-TCP langsung
        // lewat direct-tcpip (lihat komentar lama di Socks5Server) sekarang
        // cuma jadi FALLBACK kalau udpgwClient belum dikonfigurasi user.
        private const val FLAG_IPV6 = 0x08

        private const val HEADER_SIZE = 3 // flags(1) + conid(2)
        private const val ADDR_IPV4_SIZE = 6 // ip(4) + port(2)

        // Batas wajar jumlah flow UDP paralel yang dilacak client ini
        // sekaligus (games modern + banyak app biasanya jauh di bawah ini).
        // Kalau kepenuhan, flow PALING LAMA TIDAK AKTIF dibuang duluan utk
        // memberi tempat flow baru -- lebih baik daripada menolak flow baru
        // sama sekali selama trafiknya memang wajar.
        private const val MAX_FLOWS = 512

        // Flow yang tidak kirim/terima data sama sekali selama ini dianggap
        // mati, conid-nya dilepas (dipakai ulang utk flow lain). Cukup
        // longgar dibanding timeout NAT UDP kebanyakan router/game (~30-60d),
        // sekadar menjaga map ini tidak membengkak tanpa batas.
        private const val FLOW_IDLE_TIMEOUT_MS = 90_000L

        // Interval kirim keepalive per-flow yang MASIH aktif -- supaya
        // server TIDAK menganggap flow itu mati & membuang NAT mapping-nya
        // sendiri walau device kebetulan sedang tidak mengirim data baru
        // (mis. game yang lagi diam beberapa detik nunggu giliran).
        private const val KEEPALIVE_INTERVAL_MS = 10_000L

        // Sama seperti CHANNEL_OPEN_TIMEOUT_MS di Socks5Server: createLocalStreamForwarder()
        // ke channel TCP udpgw ini juga tidak punya timeout bawaan.
        private const val CHANNEL_OPEN_TIMEOUT_MS = 10_000L

        // Lihat catatan lengkap di openChannelIfNeeded(): jeda minimum antar
        // percobaan buka channel SETELAH percobaan sebelumnya gagal (mis.
        // server belum menjalankan badvpn-udpgw di port ini) -- mencegah
        // setiap paket UDP baru langsung memicu percobaan baru yang pasti
        // gagal lagi selagi masalahnya belum berubah.
        private const val OPEN_FAIL_COOLDOWN_MS = 15_000L
    }

    /** Identitas satu flow UDP: siapa pemiliknya (biasanya DatagramSocket SOCKS5 UDP ASSOCIATE) + tujuan. */
    private data class FlowKey(val owner: Any, val destAddr: InetAddress, val destPort: Int)

    private class FlowState(
        val conId: Int,
        val destAddr: InetAddress,
        val destPort: Int,
        val onResponse: (ByteArray) -> Unit
    ) {
        @Volatile var lastActivityMs: Long = System.currentTimeMillis()
    }

    @Volatile private var sshConnection: SshConnectionHandle? = null
    @Volatile private var forwarder: DirectTcpipForwarder? = null
    @Volatile private var dataOut: DataOutputStream? = null
    @Volatile private var readerThread: Thread? = null
    // Timestamp (System.currentTimeMillis()) sampai kapan openChannelIfNeeded()
    // menolak mencoba lagi setelah percobaan terakhir gagal -- lihat
    // OPEN_FAIL_COOLDOWN_MS. 0 = tidak ada cooldown aktif (belum pernah gagal,
    // atau percobaan terakhir sukses).
    @Volatile private var openFailedUntilMs: Long = 0

    private val openLock = Any()
    private val writeLock = Any()

    private val flowsByKey = ConcurrentHashMap<FlowKey, FlowState>()
    private val flowsByConId = ConcurrentHashMap<Int, FlowState>()
    private var nextConId = 0 // hanya diakses di bawah synchronized(openLock)/flowsByConId lock sederhana lewat `this`

    @Volatile private var running = false
    private var keepaliveThread: Thread? = null

    /** Mulai housekeeping (keepalive+idle-eviction) -- dipanggil SEKALI oleh SshjTunnelManager saat sesi VPN dimulai. */
    fun start() {
        if (running) return
        running = true
        keepaliveThread = Thread({ keepaliveLoop() }, "udpgw-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    /** Pasang koneksi SSH yang baru connect/reconnect. Channel TCP ke udpgw TIDAK langsung dibuka di sini (lazy, lihat header class). */
    fun attachConnection(conn: SshConnectionHandle) {
        sshConnection = conn
        openFailedUntilMs = 0 // koneksi SSH baru -- beri kesempatan baru, jangan warisi cooldown dari sesi SSH sebelumnya
    }

    /** Lepas koneksi SSH yang mati (reconnect akan datang) -- tutup channel & buang semua state flow (mapping lama sudah pasti tidak valid lagi di server). */
    fun detachConnection() {
        sshConnection = null
        closeChannelAndFlows()
    }

    /** Hentikan TOTAL -- dipanggil saat sesi VPN benar-benar berakhir. */
    fun stop() {
        running = false
        keepaliveThread?.interrupt()
        keepaliveThread = null
        sshConnection = null
        closeChannelAndFlows()
    }

    /**
     * Kirim satu paket UDP lewat udpgw. [flowOwner] dipakai murni sebagai
     * kunci identitas flow (tidak disentuh isinya) -- dari [Socks5Server]
     * ini adalah `DatagramSocket` milik sesi SOCKS5 UDP ASSOCIATE yang
     * sedang melayani paket ini. [onResponse] dipanggil (dari thread
     * pembaca internal, BUKAN thread pemanggil sendPacket) setiap kali
     * balasan utk flow ini datang dari udpgw server -- caller wajib
     * thread-safe & TIDAK BOLEH blocking lama (dipanggil bergantian dengan
     * paket flow lain di thread pembaca yang sama).
     *
     * Best-effort: gagal (SSH belum siap, channel gagal dibuka, dll) cuma
     * di-log, TIDAK melempar exception -- persis perilaku "UDP boleh
     * hilang" yang wajar utk protokol UDP itu sendiri.
     */
    fun sendPacket(
        flowOwner: Any,
        destAddr: InetAddress,
        destPort: Int,
        payload: ByteArray,
        onResponse: (ByteArray) -> Unit
    ) {
        // FIX tampilan log "berantakan" (muncul '/' aneh sebelum tiap IP,
        // mis. "Flow baru #81 -> / 1.1.1.1:53"): $destAddr di bawah ini
        // TIPENYA InetAddress, BUKAN String -- kalau dipakai langsung di
        // string template, Kotlin manggil InetAddress.toString() yang
        // formatnya "hostname/ip" (hostname kosong kalau tidak di-resolve
        // dari nama, hasilnya cuma "/ip"). Pakai .hostAddress supaya hasilnya
        // string IP polos ("1.1.1.1"), bukan "/1.1.1.1".
        val destAddrStr = destAddr.hostAddress ?: destAddr.toString()

        val out = openChannelIfNeeded()
        if (out == null) {
            Log.w(TAG, "Channel udpgw tidak tersedia, paket ke $destAddrStr:$destPort dibuang")
            return
        }

        val key = FlowKey(flowOwner, destAddr, destPort)
        val flow = flowsByKey.getOrPut(key) {
            val conId = allocateConId()
            val state = FlowState(conId, destAddr, destPort, onResponse)
            flowsByConId[conId] = state
            // FIX "log Terminal banjir": flow baru itu KEJADIAN NORMAL yang
            // sering banget (browser modern + banyak app spam QUIC/DNS terus-
            // menerus, apalagi sejak DNS ikut lewat sini juga) -- HTTP Custom
            // tidak menampilkan ini satu-satu ke Terminal, cuma milestone
            // "UDP bridge active" sekali di awal. Turunkan ke Log.d (logcat
            // saja) supaya konsisten, StatusBus tetap dipakai HANYA utk hal
            // yang jarang & penting (channel dibuka/gagal/putus).
            Log.d(TAG, "Flow baru #$conId -> $destAddrStr:$destPort")
            state
        }
        flow.lastActivityMs = System.currentTimeMillis()

        try {
            writeFrame(out, flags = 0, conId = flow.conId, addr = destAddr, port = destPort, payload = payload)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal kirim paket udpgw ke $destAddrStr:$destPort", e)
            StatusBus.log("[UDPGW] Gagal kirim paket ke $destAddrStr:$destPort: ${e.message ?: e.javaClass.simpleName}")
            // Channel kemungkinan sudah rusak -- tutup supaya paket BERIKUTNYA
            // memicu buka ulang dari nol, bukan terus-menerus gagal di channel yang sama.
            // FIX (sama seperti cabang readLoop() di atas): tanpa cooldown di sini,
            // paket UDP berikutnya (datang hampir seketika, trafik background
            // device tidak pernah benar-benar berhenti) langsung memicu buka
            // ulang channel baru ke sesi SSH yang sama-sama masih rusak -- loop
            // rapat gagal-kirim/tutup/buka-lagi tanpa jeda.
            openFailedUntilMs = System.currentTimeMillis() + OPEN_FAIL_COOLDOWN_MS
            closeChannelAndFlows()
        }
    }

    // --- Alokasi conid, dijaga sederhana lewat synchronized (frekuensi alokasi flow baru jauh lebih jarang daripada paket data) ---
    @Synchronized
    private fun allocateConId(): Int {
        if (flowsByConId.size >= MAX_FLOWS) {
            evictLeastRecentlyUsedFlow()
        }
        var attempts = 0
        while (attempts < 0x10000) {
            val candidate = nextConId
            nextConId = (nextConId + 1) and 0xFFFF
            attempts++
            if (!flowsByConId.containsKey(candidate)) return candidate
        }
        // Praktis tidak mungkin tercapai (MAX_FLOWS jauh di bawah 65536),
        // tapi tetap sediakan jalan keluar: paksa buang satu flow lalu pakai id-nya.
        val victim = flowsByConId.keys.firstOrNull()
        if (victim != null) removeFlow(victim)
        return victim ?: 0
    }

    private fun evictLeastRecentlyUsedFlow() {
        val oldest = flowsByConId.values.minByOrNull { it.lastActivityMs } ?: return
        Log.w(TAG, "Batas $MAX_FLOWS flow udpgw tercapai, membuang flow paling lama tidak aktif (#${oldest.conId})")
        removeFlow(oldest.conId)
    }

    private fun removeFlow(conId: Int) {
        val flow = flowsByConId.remove(conId) ?: return
        flowsByKey.entries.removeAll { it.value === flow }
    }

    // --- Pembukaan channel TCP (lazy, dipakai ulang) ---

    private fun openChannelIfNeeded(): DataOutputStream? {
        dataOut?.let { return it }
        // Kalau percobaan buka channel TERAKHIR gagal, jangan langsung coba
        // lagi di SETIAP paket UDP berikutnya -- kalau user salah isi port
        // (atau server memang belum menjalankan badvpn-udpgw sama sekali),
        // trafik non-DNS device bisa sangat sering (browser modern spam QUIC),
        // jadi tanpa cooldown ini tiap paket akan memicu percobaan buka
        // channel baru + log gagal baru, membanjiri Log terminal & membebani
        // koneksi SSH dengan permintaan buka channel yang pasti gagal terus.
        if (System.currentTimeMillis() < openFailedUntilMs) return null
        synchronized(openLock) {
            dataOut?.let { return it }
            if (System.currentTimeMillis() < openFailedUntilMs) return null
            val conn = sshConnection ?: run {
                Log.w(TAG, "Belum ada koneksi SSH aktif, tidak bisa buka channel udpgw")
                return null
            }
            var fwd: DirectTcpipForwarder? = null
            val openDone = AtomicBoolean(false)
            val guard = Thread({
                try {
                    Thread.sleep(CHANNEL_OPEN_TIMEOUT_MS)
                    if (!openDone.get()) {
                        Log.w(TAG, "Buka channel udpgw ke $REMOTE_HOST:$remotePort timeout -- menutup paksa")
                        try { fwd?.close() } catch (_: Exception) {}
                    }
                } catch (_: InterruptedException) {
                }
            }, "udpgw-channel-open-timeout").apply { isDaemon = true; start() }

            return try {
                fwd = conn.openDirectTcpip(REMOTE_HOST, remotePort)
                openDone.set(true)
                guard.interrupt()

                forwarder = fwd
                val newOut = DataOutputStream(fwd.outputStream)
                dataOut = newOut
                openFailedUntilMs = 0 // reset cooldown -- percobaan berikutnya (kalau channel ini putus lagi nanti) mulai dari nol
                // FIX (crash "app tiba-tiba close" saat jaringan mati saat tunnel
                // aktif): `fwd.inputStream` adalah getter lazy (SshjConnectionHandle:
                // `get() = local.getInputStream()`) yang baru dievaluasi SAAT thread
                // ini benar-benar jalan, BUKAN saat Thread(...) dibuat -- dan karena
                // ini dievaluasi sebagai ARGUMEN ke readLoop(...), evaluasinya terjadi
                // SEBELUM masuk ke try/catch yang ada di dalam readLoop() sendiri.
                // Kalau `local` socket sudah ditutup duluan oleh
                // sshTunnelManager.disconnectForReconnect() (dipicu handleTunnelDeath()
                // saat network loss) SEBELUM thread ini sempat jalan, getter ini
                // melempar SocketException TELANJANG di luar try/catch mana pun --
                // exception itu naik ke Thread.defaultUncaughtExceptionHandler dan
                // menjatuhkan SELURUH proses app, bukan cuma channel udpgw ini.
                // Sekarang dibungkus try/catch sendiri supaya race ini cuma gagal
                // membuka channel udpgw (channel akan dicoba dibuka ulang di paket
                // UDP berikutnya via cooldown openFailedUntilMs), bukan crash app.
                readerThread = Thread({
                    try {
                        readLoop(fwd.inputStream)
                    } catch (e: Exception) {
                        Log.w(TAG, "Reader udpgw gagal mulai (channel keburu ditutup?)", e)
                    }
                }, "udpgw-reader").apply {
                    isDaemon = true
                    start()
                }
                StatusBus.log("[UDPGW] Channel ke $REMOTE_HOST:$remotePort terbuka")
                newOut
            } catch (e: Exception) {
                openDone.set(true)
                guard.interrupt()
                openFailedUntilMs = System.currentTimeMillis() + OPEN_FAIL_COOLDOWN_MS
                Log.w(TAG, "Gagal buka channel udpgw ke $REMOTE_HOST:$remotePort", e)
                StatusBus.log("[UDPGW] Gagal buka channel ke $REMOTE_HOST:$remotePort: ${e.message ?: e.javaClass.simpleName} -- dicoba lagi setelah ${OPEN_FAIL_COOLDOWN_MS / 1000}dtk")
                try { fwd?.close() } catch (_: Exception) {}
                null
            }
        }
    }

    private fun closeChannelAndFlows() {
        synchronized(openLock) {
            dataOut = null
            val fwd = forwarder
            forwarder = null
            try { fwd?.close() } catch (_: Exception) {}
            readerThread?.interrupt()
            readerThread = null
        }
        // Channel putus = semua mapping conid di SISI SERVER ikut hilang --
        // buang juga state lokal supaya paket berikutnya (setelah channel
        // dibuka ulang) mengalokasikan flow/conid baru yang bersih, bukan
        // memakai conid lama yang server sudah tidak kenal lagi.
        flowsByKey.clear()
        flowsByConId.clear()
    }

    // --- Framing ---

    private fun writeFrame(out: DataOutputStream, flags: Int, conId: Int, addr: InetAddress?, port: Int, payload: ByteArray) {
        val addrBytes = addr?.address
        val hasAddr = addrBytes != null && addrBytes.size == 4
        val bodyLen = HEADER_SIZE + (if (hasAddr) ADDR_IPV4_SIZE else 0) + payload.size
        val frame = ByteArray(2 + bodyLen)
        frame[0] = (bodyLen and 0xFF).toByte()
        frame[1] = ((bodyLen shr 8) and 0xFF).toByte()
        var pos = 2
        frame[pos] = flags.toByte(); pos += 1
        frame[pos] = (conId and 0xFF).toByte(); pos += 1
        frame[pos] = ((conId shr 8) and 0xFF).toByte(); pos += 1
        if (hasAddr) {
            System.arraycopy(addrBytes!!, 0, frame, pos, 4); pos += 4
            frame[pos] = ((port shr 8) and 0xFF).toByte(); pos += 1
            frame[pos] = (port and 0xFF).toByte(); pos += 1
        }
        System.arraycopy(payload, 0, frame, pos, payload.size)

        synchronized(writeLock) {
            out.write(frame)
            out.flush()
        }
    }

    private fun sendKeepalive(out: DataOutputStream, conId: Int) {
        // Frame keepalive: cuma header (flags+conid), TANPA alamat/payload.
        val frame = ByteArray(2 + HEADER_SIZE)
        frame[0] = (HEADER_SIZE and 0xFF).toByte()
        frame[1] = ((HEADER_SIZE shr 8) and 0xFF).toByte()
        frame[2] = FLAG_KEEPALIVE.toByte()
        frame[3] = (conId and 0xFF).toByte()
        frame[4] = ((conId shr 8) and 0xFF).toByte()
        synchronized(writeLock) {
            out.write(frame)
            out.flush()
        }
    }

    // --- Pembacaan balasan dari udpgw server ---

    private fun readLoop(input: InputStream) {
        try {
            val lenBuf = ByteArray(2)
            while (true) {
                readFully(input, lenBuf)
                val bodyLen = (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8)
                if (bodyLen < HEADER_SIZE) {
                    // Frame korup/tidak masuk akal -- channel ini tidak bisa dipercaya lagi, hentikan.
                    Log.w(TAG, "Frame udpgw tidak valid (panjang $bodyLen), menutup channel")
                    break
                }
                val body = ByteArray(bodyLen)
                readFully(input, body)

                val flags = body[0].toInt() and 0xFF
                val conId = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)

                if (flags and FLAG_KEEPALIVE != 0 && bodyLen == HEADER_SIZE) {
                    // Balasan keepalive server (kalaupun server kirim ini) -- tidak ada data, cukup diabaikan.
                    continue
                }
                if (flags and FLAG_IPV6 != 0) {
                    // IPv6 tidak didukung di sini (konsisten dengan bagian lain app ini) -- lewati diam-diam.
                    continue
                }

                var pos = HEADER_SIZE
                if (pos + ADDR_IPV4_SIZE > bodyLen) continue // frame tanpa alamat yang valid, tidak bisa dipetakan balik
                pos += ADDR_IPV4_SIZE // alamat dari server tidak dipakai -- balasan dicocokkan lewat conid, bukan alamat
                val payload = body.copyOfRange(pos, bodyLen)

                val flow = flowsByConId[conId]
                if (flow == null) {
                    // conid tidak dikenal (kemungkinan flow itu sudah di-evict/idle di sisi client,
                    // atau channel sempat dibuka ulang) -- jinak, diamkan saja.
                    continue
                }
                flow.lastActivityMs = System.currentTimeMillis()
                try {
                    flow.onResponse(payload)
                } catch (e: Exception) {
                    Log.w(TAG, "onResponse flow udpgw #$conId melempar exception", e)
                }
            }
        } catch (e: EOFException) {
            Log.i(TAG, "Channel udpgw ditutup (EOF)")
            // FIX (bug "stuck" -- log spam "terbuka" / "Connection reset" tanpa
            // henti, laporan user setelah jaringan mati-hidup): dulu EOF/error di
            // sini cuma memanggil closeChannelAndFlows() TANPA cooldown -- beda
            // dengan cabang gagal-buka di openChannelIfNeeded() yang MEMASANG
            // openFailedUntilMs. Channel yang sempat "terbuka" lalu langsung mati
            // (khas kejadian pas jaringan flap: sesi SSH lama masih dianggap
            // hidup oleh disconnectWatchThread/isConnected() -- lihat
            // SshjTunnelManager -- padahal socket-nya sudah putus) jadi memicu
            // paket UDP BERIKUTNYA langsung buka ulang channel baru tanpa jeda
            // sama sekali, yang juga langsung mati lagi (sesi SSH di baliknya
            // memang masih yang sama/rusak) -- loop rapat "terbuka"/"terputus"
            // persis pola di log yang dilaporkan, tanpa pernah kasih waktu ke
            // keepalive/watchdog SSH utk sadar & memicu reconnect SSH yang
            // sesungguhnya. Sekarang cooldown yang sama dipasang di sini juga.
            openFailedUntilMs = System.currentTimeMillis() + OPEN_FAIL_COOLDOWN_MS
        } catch (e: Exception) {
            Log.w(TAG, "Channel udpgw berhenti karena error", e)
            StatusBus.log("[UDPGW] Channel terputus: ${e.message ?: e.javaClass.simpleName}")
            // Sama seperti cabang EOF di atas -- cegah loop buka-ulang rapat.
            openFailedUntilMs = System.currentTimeMillis() + OPEN_FAIL_COOLDOWN_MS
        } finally {
            closeChannelAndFlows()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw EOFException("udpgw channel closed")
            off += n
        }
    }

    // --- Housekeeping: keepalive per-flow aktif + buang flow yang sudah idle lama ---

    private fun keepaliveLoop() {
        while (running) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
            val out = dataOut ?: continue // channel belum/tidak lagi terbuka -- tidak ada yang perlu di-keepalive
            val now = System.currentTimeMillis()
            for (flow in flowsByConId.values.toList()) {
                if (now - flow.lastActivityMs > FLOW_IDLE_TIMEOUT_MS) {
                    removeFlow(flow.conId)
                    continue
                }
                try {
                    sendKeepalive(out, flow.conId)
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal kirim keepalive udpgw flow #${flow.conId}", e)
                    closeChannelAndFlows()
                    break
                }
            }
        }
    }
}
