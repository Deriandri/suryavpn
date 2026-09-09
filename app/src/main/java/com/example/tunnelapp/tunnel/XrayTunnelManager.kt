package com.example.tunnelapp.tunnel

import android.content.Context
import android.util.Log
import com.example.tunnelapp.model.ServerConfig
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.io.File

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

    /**
     * @param protectFd Callback fd-based (BUKAN Socket seperti punya SshTunnelManager),
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

        val xrayJson = XrayConfigBuilder.build(outbound, config.socksPort)

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
            registerProtect(protectFd)

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
    private fun registerProtect(protectFd: (Int) -> Boolean) {
        val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean = protectFd(fd.toInt())
        }
        LibXray.registerDialerController(controller)
        LibXray.setDNS(controller, "1.1.1.1:53")
    }

    fun disconnect() {
        if (!running) return
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
        }
        running = false
    }

    fun isConnected(): Boolean = running
}
