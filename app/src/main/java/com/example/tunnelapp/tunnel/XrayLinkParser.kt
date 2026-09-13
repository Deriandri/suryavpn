package com.example.tunnelapp.tunnel

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Protokol outbound yang didukung Xray-core untuk mode [ConnectionMode.XRAY].
 */
enum class XrayProtocol { VMESS, VLESS, TROJAN }

/**
 * Hasil parse satu link share Xray ("vmess://...", "vless://...", "trojan://...")
 * jadi field-field yang dibutuhkan buat menyusun outbound Xray-core JSON.
 *
 * Cuma menyimpan apa yang tertulis di link -- tidak menebak/mengarang default
 * yang tidak ada di link, KECUALI default yang memang jadi standar de-facto
 * ekosistem Xray (mis. network="tcp" kalau parameter "type"/"net" kosong).
 */
data class XrayOutboundConfig(
    val protocol: XrayProtocol,
    val remark: String,
    val address: String,
    val port: Int,
    // VMess: uuid. VLESS: uuid. Trojan: password (dipakai field ini juga, demi seragam).
    val idOrPassword: String,
    // VMess-only, default 0 (Xray modern cuma dukung alterId=0 / AEAD).
    val alterId: Int = 0,
    // VMess-only: "auto" (default), "aes-128-gcm", "chacha20-poly1305", "none".
    val security: String = "auto",
    // Transport: "tcp" (default), "ws", "grpc", "h2", "kcp", "httpupgrade", "xhttp", dst.
    val network: String = "tcp",
    // Dipakai kalau network == "ws"/"h2"/"httpupgrade"/"xhttp": path HTTP.
    val path: String = "/",
    // Dipakai kalau network == "ws"/"h2"/"httpupgrade"/"xhttp": Host header. Kosong = pakai [address].
    val hostHeader: String = "",
    // Dipakai kalau network == "grpc": nama service.
    val serviceName: String = "",
    // mKCP-only: tipe obfuscation header ("none"/"srtp"/"utp"/"wechat-video"/"dtls"/"wireguard").
    // Kosong/"none" = tidak pakai obfuscation.
    val headerType: String = "",
    // mKCP-only: seed/password obfuscation tambahan (opsional, kosong = tidak dipakai).
    val seed: String = "",
    // XHTTP-only: "auto" (default Xray), "packet-up", "stream-up", "stream-one".
    val xhttpMode: String = "",
    // TLS: "none" (default), "tls", "reality".
    val tlsMode: String = "none",
    // SNI dipakai saat tlsMode != "none". Kosong = pakai [address].
    val sni: String = "",
    // VLESS-only, flow XTLS (mis. "xtls-rprx-vision"). Kosong = tidak dipakai.
    val flow: String = "",
    // Kalau true, skip verifikasi sertifikat TLS (allowInsecure). Hati-hati -- cuma
    // buat testing, jangan dipakai untuk server produksi yang sertifikatnya valid.
    val allowInsecure: Boolean = false,
    // Dipakai kalau tlsMode != "none". Kosong = pakai default otomatis sesuai
    // transport (lihat XrayConfigBuilder) -- KHUSUSNYA untuk network=="ws":
    // WAJIB "http/1.1", BUKAN dibiarkan kosong/default TLS Android (yang bisa
    // ke-nego h2 dan bikin request WS tidak pernah nyampe ke backend asli di
    // balik CDN/reverse-proxy, walau TLS handshake sendiri sukses -- gejala
    // persis "tunnel nyala tapi tidak ada trafik nyata balik").
    val alpn: String = "",
    // REALITY-only.
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realityFingerprint: String = "chrome"
)

/**
 * Parser murni Kotlin (tanpa bergantung ke method convert bawaan libXray, supaya
 * skema payload-nya jelas & tidak bergantung ke detail API yang mungkin beda-beda
 * antar versi build libXray) untuk 3 format link share yang paling umum dipakai
 * client Xray/V2Ray (v2rayNG, NekoBox, dst): vmess://, vless://, trojan://.
 */
object XrayLinkParser {

    /**
     * @throws IllegalArgumentException kalau link tidak valid/tidak didukung, dengan
     *         pesan yang bisa langsung ditampilkan ke user (bahasa Indonesia).
     */
    fun parse(rawLink: String): XrayOutboundConfig {
        val link = rawLink.trim()
        return when {
            link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link)
            link.startsWith("vless://", ignoreCase = true) -> parseVlessOrTrojan(link, XrayProtocol.VLESS)
            link.startsWith("trojan://", ignoreCase = true) -> parseVlessOrTrojan(link, XrayProtocol.TROJAN)
            else -> throw IllegalArgumentException(
                "Link tidak dikenali. Harus diawali vmess://, vless://, atau trojan://"
            )
        }
    }

    /**
     * Format vmess://<base64 JSON>, JSON field standar (dipakai v2rayN/v2rayNG):
     * v, ps, add, port, id, aid, scy, net, type, host, path, tls, sni, alpn, fp
     */
    private fun parseVmess(link: String): XrayOutboundConfig {
        val b64 = link.removePrefix("vmess://").removePrefix("VMESS://")
        val jsonStr = try {
            String(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) {
            // Sebagian link vmess pakai base64 URL-safe / tanpa padding.
            try {
                String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING))
            } catch (e2: Exception) {
                throw IllegalArgumentException("Gagal decode base64 link vmess://: ${e2.message}")
            }
        }
        val json = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("Isi link vmess:// bukan JSON yang valid setelah di-decode")
        }

        val network = json.optString("net", "tcp").ifBlank { "tcp" }.lowercase()
            .let { if (it == "splithttp") "xhttp" else it } // "splithttp" = nama lama "xhttp"
        val tlsRaw = json.optString("tls", "").lowercase()
        return XrayOutboundConfig(
            protocol = XrayProtocol.VMESS,
            remark = json.optString("ps", "Xray VMess"),
            address = json.optString("add").ifBlank {
                throw IllegalArgumentException("Link vmess:// tidak punya field \"add\" (host server)")
            },
            port = json.optString("port", "0").toIntOrNull()
                ?: throw IllegalArgumentException("Port di link vmess:// tidak valid"),
            idOrPassword = json.optString("id").ifBlank {
                throw IllegalArgumentException("Link vmess:// tidak punya field \"id\" (UUID)")
            },
            alterId = json.optString("aid", "0").toIntOrNull() ?: 0,
            security = json.optString("scy", "auto").ifBlank { "auto" },
            network = network,
            path = json.optString("path", "/").ifBlank { "/" },
            hostHeader = json.optString("host", ""),
            serviceName = if (network == "grpc") json.optString("path", "") else "",
            // Field "type" dipakai vmess buat header obfuscation mKCP KALAU net=="kcp";
            // di net lain ("tcp" dsb.) field ini punya arti beda (header http) yang tidak
            // kita dukung di sini, jadi cuma diambil kalau memang mode kcp.
            headerType = if (network == "kcp") json.optString("type", "") else "",
            seed = json.optString("seed", ""),
            xhttpMode = json.optString("mode", ""),
            tlsMode = if (tlsRaw == "tls" || tlsRaw == "reality") tlsRaw else "none",
            sni = json.optString("sni", ""),
            allowInsecure = false,
            alpn = json.optString("alpn", ""),
            realityFingerprint = json.optString("fp", "chrome").ifBlank { "chrome" }
        )
    }

    /**
     * Format vless://<uuid>@<host>:<port>?param=...#remark
     * Format trojan://<password>@<host>:<port>?param=...#remark
     * (skema query param sama antara keduanya di ekosistem Xray: type, security,
     * path, host, sni, flow, alpn, fp, pbk, sid).
     */
    private fun parseVlessOrTrojan(link: String, protocol: XrayProtocol): XrayOutboundConfig {
        val uri = try {
            URI(link)
        } catch (e: Exception) {
            throw IllegalArgumentException("Format link ${protocol.name.lowercase()}:// tidak valid: ${e.message}")
        }

        val idOrPassword = uri.userInfo
            ?: throw IllegalArgumentException(
                if (protocol == XrayProtocol.VLESS) "Link vless:// tidak punya UUID sebelum tanda @"
                else "Link trojan:// tidak punya password sebelum tanda @"
            )
        val address = uri.host
            ?: throw IllegalArgumentException("Link tidak punya host server (bagian setelah @)")
        val port = if (uri.port > 0) uri.port else throw IllegalArgumentException("Link tidak punya port server")

        val params = parseQuery(uri.rawQuery)
        val remark = uri.rawFragment?.let { decodeUriComponent(it) }
            ?.ifBlank { null } ?: "Xray ${protocol.name}"

        val network = (params["type"] ?: params["net"] ?: "tcp").ifBlank { "tcp" }.lowercase()
            .let { if (it == "splithttp") "xhttp" else it } // "splithttp" = nama lama "xhttp"
        val securityParam = (params["security"] ?: "none").lowercase()
        val tlsMode = if (securityParam == "tls" || securityParam == "reality") securityParam else "none"

        return XrayOutboundConfig(
            protocol = protocol,
            remark = remark,
            address = address,
            port = port,
            idOrPassword = idOrPassword,
            network = network,
            path = (params["path"] ?: "/").ifBlank { "/" },
            hostHeader = params["host"] ?: "",
            serviceName = params["serviceName"] ?: params["path"]?.trimStart('/') ?: "",
            headerType = params["headerType"] ?: "",
            seed = params["seed"] ?: "",
            xhttpMode = params["mode"] ?: "",
            tlsMode = tlsMode,
            sni = params["sni"] ?: "",
            flow = if (protocol == XrayProtocol.VLESS) (params["flow"] ?: "") else "",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"]?.lowercase() == "true",
            alpn = params["alpn"] ?: "",
            realityPublicKey = params["pbk"] ?: "",
            realityShortId = params["sid"] ?: "",
            realityFingerprint = (params["fp"] ?: "chrome").ifBlank { "chrome" }
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null
                else decodeUriComponent(pair.substring(0, idx)) to decodeUriComponent(pair.substring(idx + 1))
            }
            .toMap()
    }

    private fun decodeUriComponent(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
}

/**
 * Kebalikan dari [XrayLinkParser]: menyusun ulang link share (vmess://, vless://,
 * trojan://) dari [XrayOutboundConfig]. Dipakai supaya user bisa mengedit field
 * hasil urai (host, port, UUID, dst) lalu link yang tersimpan diperbarui otomatis,
 * tanpa harus mengetik ulang seluruh link dari nol.
 *
 * Field lanjutan yang tidak diekspos ke form edit (mis. header obfuscation KCP,
 * mode XHTTP) tetap dipertahankan apa adanya lewat [XrayOutboundConfig.copy],
 * karena builder ini menulis ulang SEMUA field yang ada di objeknya, bukan cuma
 * yang diedit user.
 */
object XrayLinkBuilder {

    fun build(config: XrayOutboundConfig): String = when (config.protocol) {
        XrayProtocol.VMESS -> buildVmess(config)
        XrayProtocol.VLESS, XrayProtocol.TROJAN -> buildVlessOrTrojan(config)
    }

    private fun buildVmess(c: XrayOutboundConfig): String {
        val json = JSONObject()
        json.put("v", "2")
        json.put("ps", c.remark)
        json.put("add", c.address)
        json.put("port", c.port.toString())
        json.put("id", c.idOrPassword)
        json.put("aid", c.alterId.toString())
        json.put("scy", c.security)
        json.put("net", c.network)
        json.put("type", if (c.network == "kcp") c.headerType else "")
        json.put("host", c.hostHeader)
        json.put("path", if (c.network == "grpc") c.serviceName else c.path)
        json.put("tls", if (c.tlsMode == "none") "" else c.tlsMode)
        json.put("sni", c.sni)
        json.put("alpn", c.alpn)
        json.put("fp", c.realityFingerprint)
        val b64 = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        return "vmess://$b64"
    }

    private fun buildVlessOrTrojan(c: XrayOutboundConfig): String {
        val scheme = if (c.protocol == XrayProtocol.VLESS) "vless" else "trojan"
        val params = LinkedHashMap<String, String>()
        if (c.network != "tcp") params["type"] = c.network
        if (c.tlsMode != "none") params["security"] = c.tlsMode
        val pathValue = if (c.network == "grpc") c.serviceName else c.path
        if (pathValue.isNotBlank() && pathValue != "/") {
            if (c.network == "grpc") params["serviceName"] = pathValue else params["path"] = pathValue
        }
        if (c.hostHeader.isNotBlank()) params["host"] = c.hostHeader
        if (c.sni.isNotBlank()) params["sni"] = c.sni
        if (c.alpn.isNotBlank()) params["alpn"] = c.alpn
        if (c.protocol == XrayProtocol.VLESS && c.flow.isNotBlank()) params["flow"] = c.flow
        if (c.allowInsecure) params["allowInsecure"] = "1"
        if (c.realityPublicKey.isNotBlank()) params["pbk"] = c.realityPublicKey
        if (c.realityShortId.isNotBlank()) params["sid"] = c.realityShortId
        if (c.tlsMode == "reality" && c.realityFingerprint.isNotBlank()) params["fp"] = c.realityFingerprint
        if (c.network == "kcp" && c.headerType.isNotBlank()) params["headerType"] = c.headerType
        if (c.seed.isNotBlank()) params["seed"] = c.seed
        if (c.xhttpMode.isNotBlank()) params["mode"] = c.xhttpMode

        val query = params.entries.joinToString("&") { (k, v) -> "$k=${encodeComponent(v)}" }
        val fragment = if (c.remark.isNotBlank()) "#${encodeComponent(c.remark)}" else ""
        val queryPart = if (query.isNotEmpty()) "?$query" else ""
        return "$scheme://${c.idOrPassword}@${c.address}:${c.port}$queryPart$fragment"
    }

    private fun encodeComponent(s: String): String =
        try {
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            s
        }
}
