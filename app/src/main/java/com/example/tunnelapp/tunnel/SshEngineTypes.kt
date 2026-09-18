package com.example.tunnelapp.tunnel

import com.example.tunnelapp.model.ServerConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Socket

/**
 * Abstraksi engine SSH yang dipakai app ini: sshj (SshjTunnelManager),
 * satu-satunya engine yang didukung sekarang -- mendukung kompresi zlib
 * (lihat VpnSettingsStore.compressionEnabled).
 *
 * Ditambahkan supaya [Socks5Server] (yang isinya murni protokol SOCKS5 buatan
 * sendiri) TIDAK perlu tahu/terikat ke tipe koneksi milik satu library SSH
 * tertentu -- dia cukup tahu "channel direct-tcpip" secara umum lewat
 * [DirectTcpipForwarder], dan "koneksi SSH yang sedang aktif" secara umum
 * lewat [SshConnectionHandle].
 */

/**
 * Satu channel "direct-tcpip" (RFC 4254 SS7.2) yang sudah terbuka lewat
 * tunnel SSH yang sedang aktif -- dipakai [Socks5Server] utk meneruskan satu
 * koneksi TCP (perintah CONNECT) atau satu query DNS-over-TCP (fallback UDP
 * ASSOCIATE tanpa UDPGW).
 */
interface DirectTcpipForwarder {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

/**
 * Satu koneksi SSH yang sedang aktif -- cukup tahu cara buka channel
 * direct-tcpip baru ke host:port tertentu. Diimplementasikan oleh
 * SshjConnectionHandle (bungkus net.schmizz.sshj.SSHClient).
 */
interface SshConnectionHandle {
    @Throws(Exception::class)
    fun openDirectTcpip(host: String, port: Int): DirectTcpipForwarder
}

/**
 * Kontrak seragam "engine SSH" dipakai [SshjTunnelManager] -- MyVpnService
 * cukup memanggil method-method ini tanpa perlu tahu detail implementasi
 * engine di baliknya.
 */
interface SshEngineHandle {
    @Throws(Exception::class)
    fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
        protectDatagram: ((DatagramSocket) -> Boolean)?,
        // FITUR BARU (permintaan user, "bikin resolusi DNS untuk raw connect
        // sekuat HTTP Custom/DarkTunnel yang resolve sendiri ke DNS eksplisit,
        // bukan gantung ke resolver sistem"): SELALU disediakan (tidak null,
        // beda dari protectDatagram di atas yang sengaja opsional/gated khusus
        // fitur device-side DNS bypass Socks5Server) -- dipakai ConnectRelay
        // buat query DNS manual (UDP mentah ke config.dns1/dns2 atau fallback
        // publik) sebelum raw TCP connect, lihat ConnectRelay.resolveHostExplicit.
        dnsProtect: (DatagramSocket) -> Boolean,
        performanceMode: Boolean,
        compressionEnabled: Boolean,
        onUnexpectedDisconnect: (String) -> Unit
    )

    fun disconnect()
    fun disconnectForReconnect()
    fun isConnected(): Boolean
}
