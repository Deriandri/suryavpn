package com.example.tunnelapp.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.tunnelapp.model.ServerConfig
import com.example.tunnelapp.model.XraySettingsStore
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Menjalankan Xray-core lewat binding resmi libXray (proyek XTLS/libXray,
 * https://github.com/XTLS/libXray) untuk mode [com.example.tunnelapp.model.ConnectionMode.XRAY].
 *
 * Binding diverifikasi dari isi `app/libs/xray.aar` (classes.jar dibongkar) +
 * README resmi XTLS/libXray per hari ini:
 *  - package sebenarnya: `libXray` (huruf "l" kecil), class `LibXray`.
 *  - `LibXray.invoke(requestJson: String): String` -- entrypoint tunggal.
 *    Versi AAR ini HANYA menerima `"apiVersion": 3` (dikonfirmasi langsung dari
 *    bytecode `classes.jar` -> field statis `libXray.LibXray.LibXrayAPIVersion`,
 *    ConstantValue = 3L). Sebelumnya kode ini salah mengirim `"apiVersion": 2`
 *    (asumsi dari dokumentasi/versi AAR yang lebih lama) -> Go native lib
 *    (`github.com/xtls/libxray.validateAPIVersion`) menolak dan melempar balik
 *    "unsupported apiVersion". Config Xray sendiri dikirim LANGSUNG sebagai teks
 *    JSON di `payload.xrayJson`, BUKAN path file (`configPath`/`env` dari
 *    dokumentasi versi lama sudah tidak berlaku untuk AAR ini).
 *  - Interface protect: `libXray.DialerController` dengan SATU metode abstrak
 *    `protectFd(fd: Long): Boolean`. Didaftarkan lewat DUA pemanggilan terpisah
 *    (SEBELUM runXray): `LibXray.registerDialerController(controller)` --
 *    inilah yang benar-benar mem-protect() socket outbound Xray-core (dikon-
 *    firmasi dari simbol native, memanggil `xray-core/transport/internet
 *    .RegisterDialerController`) -- DAN `LibXray.setDNS(controller, dnsAddr)`
 *    untuk resolver DNS internal Xray (package Go terpisah, `libxray/dns`,
 *    TIDAK otomatis meng-cover protect socket). Awalnya cuma `setDNS()` yang
 *    dipanggil di sini -> tunnel "connect" tapi internet macet total (socket
 *    Xray ke-loop balik ke TUN sendiri). `LibXray.resetDNS()` dipanggil balik
 *    setelah stopXray.
 *
 * Alur:
 *  1. Parse link share -> [XrayOutboundConfig] (lihat [XrayLinkParser]).
 *  2. Susun JSON config Xray-core: SATU inbound SOCKS5 di 127.0.0.1:[ServerConfig.socksPort]
 *     (port yang sama dipakai hev-socks5-tunnel) + SATU outbound proxy (lihat
 *     [XrayConfigBuilder]).
 *  3. Panggil `LibXray.invoke(...)` method "runXray" dengan JSON config itu
 *     langsung di `payload.xrayJson`.
 *  4. disconnect() memanggil method "stopXray" lalu `LibXray.resetDNS()`.
 */
class XrayTunnelManager(private val context: Context) {

    companion object {
        private const val TAG = "XrayTunnelManager"

        // HARUS sama dengan field statis `LibXrayAPIVersion` di dalam xray.aar
        // (classes.jar -> libXray/LibXray.class). Dicek langsung dari bytecode
        // .class: nilainya long ConstantValue = 3, BUKAN 2. Kalau ke depan
        // xray.aar di-update, cek ulang nilai ini (lihat catatan di
        // INTEGRASI_XRAY.md) sebelum mengubahnya sembarangan, karena mismatch
        // persis menghasilkan error "unsupported apiVersion" seperti sebelumnya.
        private const val LIBXRAY_API_VERSION = 3
    }

    private var running = false

    // --- FIX "app stuck, tidak bisa disconnect sama sekali" ---
    // Sebelumnya disconnect() bisa terpanggil DUA KALI hampir bersamaan:
    // sekali dari handleTunnelDeath() (dipicu watchdog/NetworkCallback saat
    // jaringan device mati) dan sekali lagi dari stopVpn() (saat user tekan
    // tombol disconnect manual) -- keduanya sama-sama memanggil
    // LibXray.invoke("stopXray") ke runtime Go yang SAMA secara paralel.
    // invoke() ini TIDAK ADA jaminan aman dipanggil concurrent untuk
    // start/stop yang sama, dan kalau runtime Go-nya lagi menunggu I/O yang
    // tidak akan pernah selesai (jaringan device mati), dua panggilan
    // bertabrakan ini bisa membuatnya menggantung PERMANEN -- persis gejala
    // tombol "Kontrol Koneksi" macet di "Memutuskan..." selamanya. Guard ini
    // memastikan hanya SATU invoke("stopXray") yang benar-benar jalan;
    // panggilan lain yang datang selagi masih diproses cukup diabaikan
    // (bukan error, karena hasil akhirnya sama: xray akan berhenti).
    private val disconnecting = AtomicBoolean(false)

    /**
     * @param protectFd Callback fd-based (BUKAN Socket seperti punya SshjTunnelManager),
     * karena `DialerController.protectFd()` dari libXray beroperasi di level file
     * descriptor mentah (Go/gomobile). Panggil ini dari `VpnService.protect(fd: Int)`
     * langsung -- lihat pemanggilan di MyVpnService.
     */
    @Throws(Exception::class)
    fun connect(config: ServerConfig, protectFd: (Int) -> Boolean) {
        val link = config.xrayLink?.trim()
        if (link.isNullOrEmpty()) {
            throw IllegalArgumentException("Link Xray (vmess://, vless://, atau trojan://) belum diisi")
        }

        StatusBus.start(StepId.XRAY_PARSE)
        val outbound = try {
            XrayLinkParser.parse(link)
        } catch (e: Exception) {
            StatusBus.fail(StepId.XRAY_PARSE, e.message ?: e.javaClass.simpleName)
            throw e
        }
        StatusBus.success(StepId.XRAY_PARSE, "Server: ${outbound.address}:${outbound.port} (${outbound.protocol})")

        // FITUR BARU (permintaan user, "tambahkan fungsi untuk mematikan mux"
        // -- menu Pengaturan -> kartu "Xray"): baca saklar manual Mux dari
        // XraySettingsStore, teruskan ke XrayConfigBuilder (lihat KDoc
        // XraySettings.muxEnabled untuk detail perilakunya).
        val muxEnabled = XraySettingsStore.load(context).muxEnabled
        val xrayJson = XrayConfigBuilder.build(outbound, config.socksPort, muxEnabled)

        // Simpan salinan ke disk cuma untuk keperluan debug manual (bukan dibaca
        // oleh libXray -- lihat catatan di atas, versi AAR ini butuh isi JSON-nya
        // langsung, BUKAN path file).
        val dir = File(context.filesDir, "xray").apply { mkdirs() }
        File(dir, "config.json").writeText(xrayJson)

        StatusBus.start(StepId.XRAY_START)
        try {
            // --- Registrasi protect() socket, SEBELUM runXray dipanggil ---
            // Xray-core (Go) membuka socket TCP-nya sendiri ke server VMess/VLESS/
            // Trojan; socket itu HARUS di-protect() (VpnService.protect()) supaya
            // tidak ikut terjebak balik ke TUN interface milik app sendiri (infinite
            // loop, tunnel "connect" tapi internet macet total).
            registerProtect(protectFd, config)

            val request = JSONObject().apply {
                put("apiVersion", LIBXRAY_API_VERSION)
                put("method", "runXray")
                put("payload", JSONObject().put("xrayJson", xrayJson))
            }

            val responseStr = invokeLibXray(request.toString())
            val response = JSONObject(responseStr)
            val success = response.optBoolean("success", false)
            if (!success) {
                val err = response.optString("error", "Xray-core gagal jalan (tidak ada detail error)")
                throw IllegalStateException(err)
            }
            running = true
        } catch (e: Exception) {
            StatusBus.fail(StepId.XRAY_START, e.message ?: e.javaClass.simpleName)
            throw e
        }
        StatusBus.success(StepId.XRAY_START)

        Log.i(TAG, "Xray-core aktif. SOCKS5 di 127.0.0.1:${config.socksPort} -> ${outbound.address}:${outbound.port}")
    }

    /**
     * Panggilan tunggal ke binding libXray (`libXray.LibXray.invoke`, dikonfirmasi
     * dari isi xray.aar).
     */
    private fun invokeLibXray(requestJson: String): String {
        return LibXray.invoke(requestJson)
    }

    /**
     * Registrasi `DialerController` (dikonfirmasi dari xray.aar: interface dengan
     * SATU metode abstrak `protectFd(fd: Long): Boolean`).
     *
     * PENTING -- ini sempat jadi bug nyata (bukan cuma dugaan) di versi sebelumnya:
     * `LibXray.setDNS(controller, dnsAddr)` SAJA TIDAK CUKUP. Dikonfirmasi dari
     * simbol native `libgojni.so`, ada DUA package Go yang beda tugas:
     *  - `github.com/xtls/libxray/dns.SetDNS`     -> cuma atur resolver DNS
     *    internal Xray, TIDAK menyentuh protect socket sama sekali.
     *  - `github.com/xtls/libxray/controller.RegisterDialerController` -> inilah
     *    yang benar-benar memanggil `xray-core/transport/internet
     *    .RegisterDialerController`, mekanisme resmi yang mem-protect() SETIAP
     *    socket outbound Xray-core (VMess/VLESS/Trojan).
     * Kalau cuma `setDNS()` yang dipanggil (seperti sebelumnya), socket TCP asli
     * ke server proxy TIDAK PERNAH di-protect -> ikut ke-loop balik ke TUN
     * interface milik app sendiri -> tunnel "connect" (runXray sukses) tapi
     * internet macet total. Makanya WAJIB panggil KEDUANYA di sini.
     */
    private fun registerProtect(protectFd: (Int) -> Boolean, config: ServerConfig) {
        val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean = protectFd(fd.toInt())
        }
        LibXray.registerDialerController(controller)
        val dnsAddr = resolveDnsAddr(config)
        if (dnsAddr.isNotEmpty()) {
            LibXray.setDNS(controller, dnsAddr)
        } else {
            // Tidak ada DNS1/DNS2 per-server, deteksi DNS jaringan fisik
            // gagal, DAN field "Default DNS" di kartu VPN Setting kosong --
            // tidak ada hardcode bawaan lagi utk dipasang, jadi setDNS()
            // SENGAJA TIDAK dipanggil. Lihat catatan risiko di kdoc
            // resolveDnsAddr() di bawah (DNS Xray-core bisa loop balik ke
            // TUN kalau sampai tahap ini tanpa DNS eksplisit sama sekali).
            Log.w(TAG, "Tidak ada DNS yang bisa dipakai untuk resolver internal Xray (DNS1/DNS2 kosong, deteksi DNS jaringan fisik gagal, dan \"Default DNS\" di Pengaturan kosong) -- LibXray.setDNS TIDAK dipanggil")
        }
    }

    /**
     * FIX "akun berbasis domain bug-SNI (mis. ava.game.naver.com) gagal cuma di
     * engine Xray, padahal jalan normal di app lain / mode SSH app ini sendiri" --
     * root cause-nya BUKAN ALPN (sudah dicoba, tidak menyelesaikan), tapi DNS.
     *
     * Sebelumnya baris ini SELALU hardcode "1.1.1.1:53" -- ini justru BERTENTANGAN
     * dengan filosofi jalur SSH di app ini sendiri (lihat MyVpnService.applyDnsServers():
     * kalau config.dns1/dns2 kosong, SENGAJA TIDAK dipasang DNS default ke TUN sama
     * sekali, supaya DNS asli device/operator yang dipakai -- karena trik bug-SNI itu
     * BERGANTUNG pada resolusi domain lewat DNS OPERATOR/CARRIER, yang mengarahkan
     * domain seperti ava.game.naver.com ke gateway zero-rating operator, BUKAN ke IP
     * publik asli domain itu). Hardcode ke 1.1.1.1 (DNS publik Cloudflare) membuat
     * domain itu ke-resolve ke IP ASLI Naver -- TCP+TLS tetap bisa konek (server asli
     * Naver punya sertifikat valid untuk domainnya sendiri, makanya step XRAY_START
     * tetap kelihatan sukses), tapi server itu jelas bukan proxy VLESS operator, jadi
     * tidak pernah membalas apa pun yang dikenali WS/VLESS -- persis gejala "tunnel
     * nyala tapi tidak ada trafik nyata balik".
     *
     * Xray-core WAJIB tetap dikasih SATU resolver eksplisit lewat setDNS() (bukan
     * dibiarkan kosong) -- itu satu-satunya jalur DNS internalnya yang benar-benar
     * di-protect() lewat controller di atas; tanpa ini pun DNS-nya BISA looping balik
     * ke TUN sendiri (beda kasus dari yang dijelaskan applyDnsServers, yang cuma soal
     * DNS di level TUN builder Android, bukan level resolver internal Go/libXray).
     * Makanya di sini urutannya:
     *   1. config.dns1/dns2 (kalau user/provider isi manual, sama seperti jalur SSH)
     *   2. DNS asli dari jaringan fisik device (WWAN/WiFi, BUKAN network VPN milik
     *      app sendiri) -- inilah yang bikin trik bug-SNI berbasis DNS operator tetap
     *      jalan, karena precise resolver yang dipakai persis DNS bawaan SIM/operator.
     *   3. Field "Default DNS" (VpnSettings.defaultDns, kartu VPN Setting -- SAMA
     *      persis yang dipakai jalur SSH di MyVpnService.applyDnsServers) cuma sebagai
     *      fallback TERAKHIR kalau device gagal dideteksi (mis. WiFi tanpa DNS custom
     *      & API di bawah minSdk). TIDAK ADA hardcode bawaan lagi -- murni nilai yang
     *      diisi user sendiri di field itu; kalau field itu JUGA kosong, fungsi ini
     *      mengembalikan string kosong dan registerProtect() akan SENGAJA TIDAK
     *      memanggil LibXray.setDNS() sama sekali (lihat catatan risiko di sana).
     */
    private fun resolveDnsAddr(config: ServerConfig): String {
        val manual = config.dns1?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.dns2?.trim()?.takeIf { it.isNotEmpty() }
        if (manual != null) {
            Log.i(TAG, "DNS internal Xray pakai DNS1/DNS2 dari konfigurasi akun: $manual")
            return formatDnsAddr(manual)
        }
        val physicalDns = physicalNetworkDns()
        if (physicalDns != null) {
            Log.i(TAG, "DNS internal Xray pakai DNS jaringan fisik device (WWAN/WiFi): $physicalDns")
            return formatDnsAddr(physicalDns)
        }
        // Fallback TERAKHIR: field "Default DNS" (kartu VPN Setting, nilai
        // yang sama dipakai jalur SSH di MyVpnService.applyDnsServers) --
        // TIDAK ADA hardcode bawaan lagi. Kosong/belum pernah diisi -> tidak
        // ada DNS yang bisa dipakai di titik ini sama sekali (lihat
        // registerProtect()).
        val globalDefaultDns = com.example.tunnelapp.model.VpnSettingsStore.load(context).defaultDns.trim()
        if (globalDefaultDns.isEmpty()) {
            Log.w(TAG, "Gagal deteksi DNS jaringan fisik device DAN field \"Default DNS\" di Pengaturan kosong -- tidak ada DNS default yang bisa dipakai")
            return ""
        }
        Log.w(TAG, "Gagal deteksi DNS jaringan fisik device, fallback ke DNS default dari Pengaturan " +
            "\"$globalDefaultDns\" -- akun berbasis bug-SNI/domain-fronting kemungkinan " +
            "TIDAK akan jalan dengan DNS ini")
        return formatDnsAddr(globalDefaultDns)
    }

    /**
     * FIX "invalid DNS server ...: too many colons in address" -- alamat IPv6
     * (BANYAK operator seluler Indonesia kasih DNS IPv6 lewat WWAN, mis.
     * "2400:9800:2:2::245") WAJIB dibungkus kurung siku sebelum ditempeli
     * ":<port>", persis notasi host:port standar (RFC 3986) yang dipakai Go
     * net.Dial -- "ip:port" polos cuma valid untuk IPv4. Tanpa ini, setiap titik
     * dua di alamat IPv6 dihitung sebagai pemisah host:port oleh Go, makanya
     * errornya "too many colons".
     *
     * FIX LAGI (laporan user: "aktifkan DNS = connect tapi TIDAK ada internet
     * sama sekali, matikan DNS = internet lancar"): [ip] di sini SEHARUSNYA
     * cuma IP polos TANPA port (persis field DNS1/DNS2 di jalur SSH, yang
     * memang divalidasi lewat VpnService.Builder.addDnsServer() -- fungsi itu
     * MELEMPAR IllegalArgumentException kalau ada ":port" ikut, jadi salah
     * ketik semacam ini otomatis KETAHUAN & ditolak di jalur SSH). Jalur Xray
     * di sini TIDAK PERNAH divalidasi seperti itu -- kalau user mengetik DNS1
     * SUDAH DENGAN port (wajar banget, kebiasaan umum menulis DNS ya
     * "1.1.1.1:53", bukan cuma "1.1.1.1"), versi LAMA fungsi ini melihat ':'
     * lalu SALAH mengira itu IPv6 -> dibungkus "[1.1.1.1:53]:53" -- alamat
     * rusak total dengan DUA port sekaligus. Xray-core lalu GAGAL memakai
     * resolver ini untuk MENERESOLUSI domain apa pun yang diakses lewat
     * tunnel (bukan gagal connect ke server VLESS itu sendiri, makanya step
     * XRAY_START tetap kelihatan sukses) -- persis gejala "connect tapi
     * internet mati total" yang dilaporkan.
     *
     * Sekarang [ip] dibersihkan dulu lewat [stripExistingPort] SEBELUM
     * ditempeli ":53" -- port yang SUDAH ada di teks yang diketik user
     * (IPv4:port ATAU [IPv6]:port/IPv6:port) dibuang dulu, supaya hasil
     * akhirnya SELALU "ip:53"/"[ipv6]:53" yang valid, apa pun bentuk asli
     * yang diketik user.
     */
    private fun formatDnsAddr(ip: String): String {
        val bareIp = stripExistingPort(ip)
        return if (bareIp.contains(':')) "[$bareIp]:53" else "$bareIp:53"
    }

    /**
     * Buang ":<port>" yang MUNGKIN sudah ikut ditulis user, tanpa merusak IPv6
     * polos (yang WAJAR mengandung banyak titik dua tanpa itu berarti "port").
     * Pola yang ditangani:
     *  - "1.1.1.1:53"        -> "1.1.1.1"        (IPv4 + port)
     *  - "1.1.1.1"           -> "1.1.1.1"        (IPv4 polos, tidak berubah)
     *  - "[2400:9800::245]:53" -> "2400:9800::245" (IPv6 dibungkus kurung + port)
     *  - "2400:9800::245"    -> "2400:9800::245" (IPv6 polos TANPA kurung/port,
     *    dibiarkan APA ADANYA -- tidak ada cara aman membedakan "IPv6 polos"
     *    dari "IPv6 + port nempel" tanpa kurung, jadi SENGAJA tidak disentuh)
     */
    private fun stripExistingPort(raw: String): String {
        val trimmed = raw.trim()
        // Bentuk "[ipv6]:port" atau "[ipv6]" -- ambil isi dalam kurung saja,
        // buang apa pun sesudah "]" (baik ":port" atau tidak ada apa-apa).
        if (trimmed.startsWith("[")) {
            val closingBracket = trimmed.indexOf(']')
            if (closingBracket > 0) return trimmed.substring(1, closingBracket)
        }
        // Bentuk IPv4 polos + port ("1.1.1.1:53") -- TEPAT SATU titik dua &
        // bagian sebelum titik dua itu valid pola IPv4 (4 kelompok angka
        // dipisah titik). IPv6 polos SELALU punya LEBIH dari satu titik dua,
        // jadi tidak akan pernah salah kena aturan ini.
        val singleColonIdx = trimmed.indexOf(':')
        if (singleColonIdx > 0 && trimmed.indexOf(':', singleColonIdx + 1) == -1) {
            val hostPart = trimmed.substring(0, singleColonIdx)
            if (hostPart.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                return hostPart
            }
        }
        // Selain pola-pola di atas (IPv4 polos tanpa port, ATAU IPv6 polos
        // tanpa kurung) -- kembalikan apa adanya, TIDAK disentuh.
        return trimmed
    }

    /**
     * Cari IP DNS dari network FISIK aktif (WWAN seluler/WiFi), BUKAN network VPN
     * milik app sendiri (kalau tanpa filter ini, di sistem tertentu getActiveNetwork()
     * bisa saja mengembalikan network VPN sendiri setelah tunnel aktif -- LinkProperties
     * network VPN tidak relevan sama sekali di sini).
     */
    private fun physicalNetworkDns(): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val allDns = cm.allNetworks
            .asSequence()
            .mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                cm.getLinkProperties(net)?.dnsServers
            }
            .flatten()
            .mapNotNull { it.hostAddress }
            .toList()
        // Prioritaskan IPv4 kalau device punya keduanya -- bukan karena IPv6 tidak
        // valid (sudah didukung penuh lewat formatDnsAddr() di atas), murni supaya
        // lebih konsisten dengan kebiasaan mayoritas server VLESS/trojan (address
        // outbound-nya sendiri kebanyakan IPv4/domain, jadi resolver IPv4 lebih
        // "aman" default-nya) -- IPv6 tetap dipakai apa adanya kalau memang cuma
        // itu yang tersedia di jaringan device (persis kasus operator yang cuma
        // kasih DNS IPv6 lewat WWAN).
        allDns.firstOrNull { !it.contains(':') } ?: allDns.firstOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "Gagal query DNS jaringan fisik device", e)
        null
    }

    fun disconnect() {
        if (!running) return
        if (!disconnecting.compareAndSet(false, true)) {
            // Sudah ada panggilan disconnect() lain yang sedang diproses
            // (lihat catatan [disconnecting] di atas) -- jangan kirim
            // invoke("stopXray") kedua, cukup keluar.
            Log.w(TAG, "disconnect() Xray sudah sedang diproses panggilan lain, diabaikan")
            return
        }
        try {
            val request = JSONObject().apply {
                put("apiVersion", LIBXRAY_API_VERSION)
                put("method", "stopXray")
                put("payload", JSONObject())
            }
            invokeLibXray(request.toString())
            LibXray.resetDNS()
        } catch (e: Exception) {
            Log.e(TAG, "Error stop Xray-core", e)
        } finally {
            running = false
            disconnecting.set(false)
        }
    }

    fun isConnected(): Boolean = running
}
