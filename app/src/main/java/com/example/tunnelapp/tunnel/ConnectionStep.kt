package com.example.tunnelapp.tunnel

import com.example.tunnelapp.model.ServerConfig

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    ERROR,
    SKIPPED
}

data class ConnectionStep(
    val id: String,
    val label: String,
    val status: StepStatus = StepStatus.PENDING,
    val detail: String? = null
)

/**
 * ID tahap koneksi, dipakai bersama oleh MyVpnService, ConnectRelay, dan
 * SshjTunnelManager supaya semua pihak melapor ke StatusBus dengan id yang sama.
 */
object StepId {
    const val TUN = "tun"
    const val CONNECT_SERVER = "connect_server"
    const val PROXY_CONNECT = "proxy_connect"
    const val TLS = "tls"
    const val PAYLOAD = "payload"
    const val WEBSOCKET = "websocket"
    const val SSH_HANDSHAKE = "ssh_handshake"
    const val SSH_AUTH = "ssh_auth"
    const val SOCKS5 = "socks5"
    const val TUNNEL_ACTIVE = "tunnel_active"

    // Khusus mode XRAY (lihat XrayTunnelManager) -- menggantikan
    // CONNECT_SERVER/TLS/PAYLOAD/WEBSOCKET/SSH_HANDSHAKE/SSH_AUTH/SOCKS5 di atas,
    // karena semua tahap itu ditangani internal oleh Xray-core sendiri.
    const val XRAY_PARSE = "xray_parse"
    const val XRAY_START = "xray_start"
}

/**
 * Susun daftar tahap sesuai konfigurasi yang dipilih user, supaya log yang
 * ditampilkan hanya berisi tahap yang benar-benar akan dijalankan
 * (misal: tahap TLS/payload/proxy disembunyikan kalau memang tidak dipakai
 * pada mode yang sedang dipilih).
 */
fun buildStepsFor(config: ServerConfig): List<ConnectionStep> {
    val steps = mutableListOf(
        ConnectionStep(StepId.TUN, "Membuat antarmuka VPN (TUN)")
    )

    if (config.usesXray()) {
        steps += ConnectionStep(StepId.XRAY_PARSE, "Membaca link Xray (vmess/vless/trojan)")
        steps += ConnectionStep(StepId.XRAY_START, "Menjalankan Xray-core & SOCKS5 lokal")
        steps += ConnectionStep(StepId.TUNNEL_ACTIVE, "Mengaktifkan tunnel ke seluruh trafik device")
        return steps
    }

    val usesProxy = config.usesProxy()
    if (usesProxy) {
        val proxyPort = config.proxyPort ?: config.port
        if (config.proxyRawMode) {
            steps += ConnectionStep(
                StepId.CONNECT_SERVER,
                "Menghubungkan (raw, tanpa CONNECT) ke ${config.proxyHost}:$proxyPort"
            )
            steps += ConnectionStep(
                StepId.PROXY_CONNECT,
                "CONNECT dilewati (raw passthrough)",
                status = StepStatus.SKIPPED
            )
        } else {
            steps += ConnectionStep(StepId.CONNECT_SERVER, "Menghubungkan ke proxy ${config.proxyHost}:$proxyPort")
            steps += ConnectionStep(StepId.PROXY_CONNECT, "CONNECT ke ${config.host}:${config.port} lewat proxy")
        }
    } else {
        steps += ConnectionStep(StepId.CONNECT_SERVER, "Menghubungkan ke server ${config.host}:${config.port}")
    }

    if (config.usesTls()) {
        steps += ConnectionStep(StepId.TLS, "Melakukan TLS handshake (SSL)")
    }
    if (!config.payload.isNullOrEmpty()) {
        steps += ConnectionStep(StepId.PAYLOAD, "Mengirim payload custom")
    }
    // WebSocket genuine (RFC 6455) cuma dicoba kalau TIDAK ada payload custom --
    // lihat ServerConfig.attemptsFormalWebSocket(). Kalau payload diisi, payload
    // itu sendiri sudah dipercaya sebagai satu-satunya trik HTTP yang dipakai
    // (sama seperti DarkTunnel/HTTP Custom), jadi step ini tidak akan pernah
    // benar-benar dijalankan dan sebaiknya tidak ditampilkan di log supaya
    // tidak membingungkan.
    if (config.attemptsFormalWebSocket()) {
        steps += ConnectionStep(StepId.WEBSOCKET, "Mencoba handshake WebSocket (fallback ke raw jika ditolak)")
    }
    steps += ConnectionStep(StepId.SSH_HANDSHAKE, "Melakukan handshake protokol SSH")
    steps += ConnectionStep(StepId.SSH_AUTH, "Autentikasi SSH (username/password)")
    steps += ConnectionStep(StepId.SOCKS5, "Mengaktifkan SOCKS5 lokal")
    steps += ConnectionStep(StepId.TUNNEL_ACTIVE, "Mengaktifkan tunnel ke seluruh trafik device")
    return steps
}
