package com.example.tunnelapp.tunnel

import org.json.JSONArray
import org.json.JSONObject

/**
 * Menyusun konfigurasi Xray-core (format JSON asli Xray, BUKAN format v2rayN/subscription)
 * dari satu [XrayOutboundConfig] hasil [XrayLinkParser].
 *
 * Sengaja dibuat MINIMAL & SATU ARAH saja:
 *  - SATU inbound: SOCKS5 di 127.0.0.1:[socksPort] (sama seperti Socks5Server milik
 *    jalur SSH) -- dipakai hev-socks5-tunnel yang sudah ada di MyVpnService, jadi
 *    pipeline TUN->SOCKS5 TIDAK berubah sama sekali untuk mode XRAY.
 *  - SATU outbound: protokol dari link share (vmess/vless/trojan), transport tcp/ws/
 *    grpc/h2/httpupgrade/xhttp/kcp.
 *  - ROUTING (FITUR BARU, parity dasar dengan V2RayNG): sebelumnya TIDAK ada
 *    objek "routing" sama sekali (semua trafik otomatis lewat satu outbound).
 *    Sekarang, kalau [XrayConfigBuilder.build] dipanggil dengan bypassDomains/
 *    bypassIps terisi (lewat kartu Routing di Tools), sebuah outbound kedua
 *    "direct" (freedom, langsung ke internet TANPA lewat proxy) ditambahkan +
 *    rule routing yang mencocokkan domain/IP tsb ke outbound "direct" itu.
 *    SENGAJA TIDAK memakai prefix "geosite:"/"geoip:" (itu butuh file
 *    geosite.dat/geoip.dat yang tidak disertakan project ini) -- domain/IP
 *    dicocokkan sebagai teks polos/CIDR, sesuai dukungan native "field"
 *    routing Xray-core yang tidak butuh geo asset apa pun untuk domain/IP
 *    literal. Kalau bypassDomains/bypassIps kosong dua-duanya, TIDAK ada
 *    objek "routing" yang ditambahkan sama sekali (perilaku lama, tanpa
 *    split-tunneling, persis seperti sebelumnya).
 *  - FAKE DNS + DoH (FITUR BARU, parity dengan V2RayNG): kalau
 *    [fakeDnsEnabled] true, ditambahkan objek top-level "fakedns" (pool IP
 *    lokal 198.18.0.0/15, tidak pernah bentrok dengan IP publik asli) +
 *    "dns" (daftar server, dimulai dari tag "fakedns" itu sendiri, lalu
 *    [dohUrl] kalau diisi, lalu "1.1.1.1" sebagai fallback paling akhir) +
 *    "sniffing" dengan destOverride ["http","tls","fakedns"] di inbound
 *    SOCKS -- kombinasi tiga ini yang membuat resolusi domain terjadi
 *    LOKAL (ke IP palsu di pool, baru "dibongkar" balik ke domain asli pas
 *    dikirim ke outbound proxy) alih-alih lewat DNS device yang normal,
 *    mengurangi risiko kebocoran DNS. [dohUrl] SENDIRI (tanpa fakeDnsEnabled)
 *    tetap bisa diisi untuk menambah satu server DoH ke daftar "dns" --
 *    dua fitur ini independen satu sama lain.
 */
object XrayConfigBuilder {

    /**
     * @param muxEnabled/[muxConcurrency] FITUR BARU: sebelumnya hardcoded
     *   true/8 di [buildOutbound]. Sekarang dikontrol dari
     *   [com.example.tunnelapp.model.VpnSettings] (kartu Routing di Tools)
     *   supaya user bisa matikan mux sepenuhnya (mis. server yang bermasalah
     *   dengan multiplexing) atau mengubah concurrency-nya sendiri.
     * @param bypassDomains daftar domain plain-text (tanpa "geosite:") yang
     *   di-bypass langsung tanpa proxy -- lihat catatan class-level di atas.
     * @param bypassIps daftar IP/CIDR plain-text (tanpa "geoip:") yang
     *   di-bypass langsung tanpa proxy.
     * @param fakeDnsEnabled lihat catatan class-level "FAKE DNS + DoH" di atas.
     * @param dohUrl URL server DoH custom (mis. "https://1.1.1.1/dns-query").
     *   Kosong = tidak ditambahkan objek "dns" sama sekali kecuali
     *   [fakeDnsEnabled] true (yang tetap butuh objek "dns" minimal berisi
     *   tag "fakedns" + fallback "1.1.1.1").
     */
    fun build(
        outbound: XrayOutboundConfig,
        socksPort: Int,
        muxEnabled: Boolean = true,
        muxConcurrency: Int = 8,
        bypassDomains: List<String> = emptyList(),
        bypassIps: List<String> = emptyList(),
        fakeDnsEnabled: Boolean = false,
        dohUrl: String = ""
    ): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("loglevel", "warning"))

        val inbound = JSONObject().apply {
            put("tag", "socks-in")
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("ip", "127.0.0.1")
            })
            // FITUR BARU: sniffing "destOverride" wajib mencakup "fakedns"
            // supaya Xray-core tahu request yang masuk boleh di-resolve
            // lewat fake DNS pool -- tanpa ini objek "fakedns"/"dns" di
            // bawah TIDAK berefek apa-apa sama sekali.
            if (fakeDnsEnabled) {
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().put("http").put("tls").put("fakedns"))
                })
            }
        }
        root.put("inbounds", JSONArray().put(inbound))

        val cleanDoh = dohUrl.trim()
        if (fakeDnsEnabled || cleanDoh.isNotEmpty()) {
            root.put("fakedns", JSONArray().put(JSONObject().apply {
                put("ipPool", "198.18.0.0/15")
                put("poolSize", 65535)
            }))
            val dnsServers = JSONArray()
            if (fakeDnsEnabled) dnsServers.put("fakedns")
            if (cleanDoh.isNotEmpty()) dnsServers.put(JSONObject().put("address", cleanDoh))
            // Fallback terakhir -- server DNS "normal" biasa, dipakai kalau
            // fake DNS/DoH di atas tidak bisa menjawab suatu query (mis.
            // domain yang memang di-bypass, atau reverse lookup IP).
            dnsServers.put("1.1.1.1")
            root.put("dns", JSONObject().put("servers", dnsServers))
        }

        val outbounds = JSONArray().put(buildOutbound(outbound, muxEnabled, muxConcurrency))

        val cleanDomains = bypassDomains.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanIps = bypassIps.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanDomains.isNotEmpty() || cleanIps.isNotEmpty()) {
            // Outbound "direct" (freedom): trafik yang match rule di bawah
            // keluar langsung ke internet dari device, TIDAK lewat tunnel/
            // proxy sama sekali -- ini yang bikin sebuah domain/IP "di-bypass".
            outbounds.put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })

            val rule = JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                if (cleanDomains.isNotEmpty()) {
                    put("domain", JSONArray().apply { cleanDomains.forEach { put(it) } })
                }
                if (cleanIps.isNotEmpty()) {
                    put("ip", JSONArray().apply { cleanIps.forEach { put(it) } })
                }
            }
            root.put("routing", JSONObject().apply {
                // "proxy" tetap outbound PERTAMA di array (default kalau
                // tidak ada rule yang match) -- konsisten dengan perilaku
                // Xray-core: unmatched traffic otomatis jatuh ke outbound
                // pertama dalam daftar, jadi tidak perlu default eksplisit.
                put("domainStrategy", "AsIs")
                put("rules", JSONArray().put(rule))
            })
        }

        root.put("outbounds", outbounds)

        return root.toString()
    }

    private fun buildOutbound(cfg: XrayOutboundConfig, muxEnabled: Boolean, muxConcurrency: Int): JSONObject {
        val streamSettings = buildStreamSettings(cfg)
        val outboundObj = JSONObject().apply {
            put("tag", "proxy")
            put("streamSettings", streamSettings)
            // mux.cool -- menggabungkan beberapa koneksi TCP/stream kecil
            // (umum saat browsing web: banyak request paralel ke domain/CDN
            // berbeda) jadi satu koneksi fisik ke server, jadi tidak perlu
            // handshake TCP+TLS baru dari nol tiap kali browser buka
            // koneksi baru -- lumayan berarti di jaringan ber-RTT tinggi
            // (tiap handshake baru = minimal 1 RTT ekstra, kadang lebih
            // untuk TLS). [muxEnabled]/[muxConcurrency] FITUR BARU: dulu
            // hardcoded true/8, sekarang diatur user lewat kartu Routing di
            // Tools (mis. dimatikan kalau server bermasalah dengan
            // multiplexing). TIDAK diaktifkan untuk protokol/transport yang
            // mux.cool resmi TIDAK didukung/direkomendasikan Xray-core
            // (KCP -- based UDP, dan XHTTP -- sudah punya mekanisme
            // multiplexing sendiri yang konflik kalau digabung mux.cool
            // sekaligus), TERLEPAS dari muxEnabled.
            if (muxEnabled && cfg.network != "kcp" && cfg.network != "xhttp") {
                put("mux", JSONObject().apply {
                    put("enabled", true)
                    put("concurrency", muxConcurrency.coerceIn(1, 1024))
                })
            }
        }

        when (cfg.protocol) {
            XrayProtocol.VMESS -> {
                outboundObj.put("protocol", "vmess")
                val user = JSONObject().apply {
                    put("id", cfg.idOrPassword)
                    put("alterId", cfg.alterId)
                    put("security", cfg.security)
                }
                val server = JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("users", JSONArray().put(user))
                }
                outboundObj.put(
                    "settings",
                    JSONObject().put("vnext", JSONArray().put(server))
                )
            }
            XrayProtocol.VLESS -> {
                outboundObj.put("protocol", "vless")
                val user = JSONObject().apply {
                    put("id", cfg.idOrPassword)
                    put("encryption", "none")
                    if (cfg.flow.isNotBlank()) put("flow", cfg.flow)
                }
                val server = JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("users", JSONArray().put(user))
                }
                outboundObj.put(
                    "settings",
                    JSONObject().put("vnext", JSONArray().put(server))
                )
            }
            XrayProtocol.TROJAN -> {
                outboundObj.put("protocol", "trojan")
                val server = JSONObject().apply {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("password", cfg.idOrPassword)
                }
                outboundObj.put(
                    "settings",
                    JSONObject().put("servers", JSONArray().put(server))
                )
            }
        }
        return outboundObj
    }

    private fun buildStreamSettings(cfg: XrayOutboundConfig): JSONObject {
        val stream = JSONObject().put("network", cfg.network)

        when (cfg.network) {
            "ws" -> {
                stream.put("wsSettings", JSONObject().apply {
                    put("path", cfg.path.ifBlank { "/" })
                    if (cfg.hostHeader.isNotBlank()) {
                        put("headers", JSONObject().put("Host", cfg.hostHeader))
                    }
                })
            }
            "grpc" -> {
                stream.put("grpcSettings", JSONObject().apply {
                    put("serviceName", cfg.serviceName)
                })
            }
            "h2", "http" -> {
                stream.put("httpSettings", JSONObject().apply {
                    put("path", cfg.path.ifBlank { "/" })
                    put("host", JSONArray().put(cfg.hostHeader.ifBlank { cfg.address }))
                })
            }
            "httpupgrade" -> {
                stream.put("httpupgradeSettings", JSONObject().apply {
                    put("path", cfg.path.ifBlank { "/" })
                    put("host", cfg.hostHeader.ifBlank { cfg.address })
                })
            }
            "xhttp" -> {
                // Skema resmi Xray-core: "xhttpSettings" { path, host, mode, extra }.
                // "extra" (xPaddingBytes/xmux/dst.) sengaja tidak diisi -- link share yang
                // kita dukung di sini tidak membawa parameter itu.
                stream.put("xhttpSettings", JSONObject().apply {
                    put("path", cfg.path.ifBlank { "/" })
                    put("host", cfg.hostHeader.ifBlank { cfg.address })
                    put("mode", cfg.xhttpMode.ifBlank { "auto" })
                })
            }
            "kcp" -> {
                // xray.aar yang dipakai project ini Xray-core v26.3.27: field lama
                // `kcpSettings.header`/`kcpSettings.seed` SUDAH DIHAPUS di versi ini,
                // dipindah ke mekanisme baru "FinalMask" (tipe "mkcp-legacy" khusus
                // untuk kompatibilitas ke server lama yang masih pakai header/seed
                // gaya lama -- ini BUKAN dugaan, dikonfirmasi dari changelog resmi
                // Xray-core v26.3.27 & migrasi yang dipakai proyek GUI lain yang kena
                // masalah sama). kcpSettings sendiri tetap ada tapi cuma untuk
                // parameter numerik (mtu/tti/dst.) -- dibiarkan default Xray kalau
                // tidak ada info dari link (link share cuma bawa headerType & seed).
                stream.put("kcpSettings", JSONObject())
                if (cfg.headerType.isNotBlank() && cfg.headerType != "none" || cfg.seed.isNotBlank()) {
                    val udpMasks = JSONArray()
                    if (cfg.headerType.isNotBlank() && cfg.headerType != "none") {
                        udpMasks.put(JSONObject().apply {
                            put("type", "mkcp-legacy")
                            put("settings", JSONObject().put("header", cfg.headerType))
                        })
                    }
                    if (cfg.seed.isNotBlank()) {
                        udpMasks.put(JSONObject().apply {
                            put("type", "mkcp-legacy")
                            put("settings", JSONObject().put("value", cfg.seed))
                        })
                    }
                    stream.put("finalmask", JSONObject().put("udp", udpMasks))
                }
            }
            // "tcp" & lainnya: tidak perlu settings tambahan.
        }

        when (cfg.tlsMode) {
            "tls" -> {
                stream.put("security", "tls")
                stream.put("tlsSettings", JSONObject().apply {
                    put("serverName", cfg.sni.ifBlank { cfg.hostHeader.ifBlank { cfg.address } })
                    put("allowInsecure", cfg.allowInsecure)
                    // FIX "tunnel nyala tapi trafik nyata gak balik" (khusus network=="ws"):
                    // kalau ALPN dibiarkan kosong, TLS stack bisa nego ke "h2" (banyak
                    // reverse-proxy/CDN di baliknya prioritaskan h2 kalau client
                    // menawarkannya), padahal transport ws BUTUH http/1.1 murni --
                    // TLS handshake tetap sukses (makanya step sebelumnya kelihatan
                    // hijau), tapi request WS upgrade-nya sendiri tidak pernah nyampe
                    // ke backend asli. v2rayNG/NekoBox/HTTP Custom & sejenisnya SELALU
                    // set ini eksplisit -- di sini disamakan: pakai alpn dari link kalau
                    // ada, kalau tidak fallback ke default aman per transport.
                    val alpnList = resolveAlpn(cfg)
                    if (alpnList.isNotEmpty()) {
                        put("alpn", JSONArray().apply { alpnList.forEach { put(it) } })
                    }
                    if (cfg.realityFingerprint.isNotBlank()) put("fingerprint", cfg.realityFingerprint)
                })
            }
            "reality" -> {
                stream.put("security", "reality")
                stream.put("realitySettings", JSONObject().apply {
                    put("serverName", cfg.sni.ifBlank { cfg.address })
                    put("fingerprint", cfg.realityFingerprint.ifBlank { "chrome" })
                    put("publicKey", cfg.realityPublicKey)
                    put("shortId", cfg.realityShortId)
                    if (cfg.flow.isNotBlank()) put("spiderX", "")
                })
            }
            else -> stream.put("security", "none")
        }

        return stream
    }

    /**
     * ALPN untuk TLS: pakai apa yang tertulis di link (bisa berisi lebih dari satu,
     * dipisah koma, mis. "h2,http/1.1") kalau ada, kalau tidak fallback ke default
     * de-facto ekosistem Xray per jenis transport -- KHUSUSNYA "ws" WAJIB
     * "http/1.1" murni (lihat catatan panjang di pemanggilnya), sedangkan "h2"
     * ya wajar minta "h2". Transport lain (tcp/grpc/kcp/dst.) dibiarkan kosong
     * (tidak set field alpn sama sekali -- perilaku Xray default, yang untuk
     * transport-transport itu memang tidak bermasalah).
     */
    private fun resolveAlpn(cfg: XrayOutboundConfig): List<String> {
        if (cfg.alpn.isNotBlank()) {
            return cfg.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return when (cfg.network) {
            "ws", "httpupgrade", "xhttp" -> listOf("http/1.1")
            "h2" -> listOf("h2")
            else -> emptyList()
        }
    }
}
